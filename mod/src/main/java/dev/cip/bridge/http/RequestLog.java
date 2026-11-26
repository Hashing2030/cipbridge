package dev.cip.bridge.http;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 请求日志的落盘端（CB-10 10.2，SPEC §7「所有请求写 {@code logs/cipbridge.log}（时间/来源/参数/耗时）」）。
 *
 * <p><b>职责边界</b>：内容（方法/路径/状态码/耗时/来源）由 {@link HttpApi} 的日志钩子决定，
 * 本类只负责"加时间戳 + 追加写文件"。因此本类<b>不 import 任何 {@code net.minecraft} 或
 * {@code net.neoforged} 类型</b>，可以在裸 JDK 进程里直接构造、写文件、验证（headless 冒烟即如此）。</p>
 *
 * <p><b>游戏目录从哪来</b>：路径由调用方（{@code CipBridge}）用 {@code FMLPaths.GAMEDIR} 解析后传进来；
 * FML 在裸进程里没建立路径服务、或 {@code GAMEDIR} 为 {@code null} 时，调用方传 {@code null}，
 * 本类退化成 <b>no-op</b>（{@code accept} 直接返回，不抛异常、不建文件）——这是任务卡要求的
 * "GAMEDIR 为 null（headless）时降级为 no-op（不崩）"。</p>
 *
 * <p><b>线程安全</b>：所有状态（{@code writer}/{@code broken}）都在同一个 {@code lock} 上同步；
 * {@link HttpApi} 会在多个 {@code cipbridge-http-*} 线程上并发调用 {@link #accept(String)}，
 * 因此"<b>懒打开</b> + <b>synchronized</b> + 每行带时间戳"三条都在锁内完成。</p>
 *
 * <p><b>失败即停（不连累请求）</b>：写盘抛 {@link IOException} 时把实例标记为 {@code broken} 并关闭句柄，
 * 之后所有写入静默丢弃；只在首次失败时通过 {@code errorSink} 报一次，避免磁盘满时把日志刷成风暴。
 * 异常不向调用方抛——请求日志永远不能影响请求本身。</p>
 */
public final class RequestLog implements Consumer<String>, AutoCloseable {

    /** 日志文件名（放在游戏目录的 {@code logs/} 下，SPEC §7）。 */
    public static final String FILE_NAME = "cipbridge.log";

    /** 每行前缀的时间戳格式（本地时区，毫秒精度，与 {@code latest.log} 的 {@code [HH:MM:SS]} 可肉眼对齐）。 */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 目标文件；{@code null} 表示禁用（headless / GAMEDIR 不可用），所有写入为 no-op。 */
    private final Path file;

    /** 失败上报出口（CipBridge 传 {@code LOG::warn}；裸 JDK 里默认 {@code System.err}）。 */
    private final Consumer<String> errorSink;

    /** 保护 {@link #writer}/{@link #broken}；读日志的并发调用都串行化在这一把锁上。 */
    private final Object lock = new Object();

    /** 懒打开的文件句柄（首次写入时创建）；只在 {@link #lock} 内访问。 */
    private BufferedWriter writer;

    /** 写盘曾经失败：之后不再尝试，避免每次请求都撞一次 IO 错误。 */
    private boolean broken;

    private RequestLog(Path file, Consumer<String> errorSink) {
        this.file = file;
        this.errorSink = errorSink;
    }

    /**
     * 打开 {@code <gameDir>/logs/cipbridge.log}。
     *
     * @param gameDir   游戏目录（{@code FMLPaths.GAMEDIR}）；{@code null} → 返回 no-op 实例
     * @param errorSink 写盘失败时的上报出口（可为 {@code null}，表示静默）
     */
    public static RequestLog open(Path gameDir, Consumer<String> errorSink) {
        return gameDir == null ? disabled() : of(gameDir.resolve("logs").resolve(FILE_NAME), errorSink);
    }

    /**
     * 写到指定文件（不附加 {@code logs/}）。主要用于自测与将来换目录；{@code file} 为 {@code null} 时 no-op。
     */
    public static RequestLog of(Path file, Consumer<String> errorSink) {
        return file == null ? disabled() : new RequestLog(file, errorSink);
    }

    /** 显式禁用：所有写入丢弃（headless 降级路径）。 */
    public static RequestLog disabled() {
        return new RequestLog(null, null);
    }

    /** 是否真的会落盘（{@code false} = headless 降级的 no-op 实例）。 */
    public boolean isEnabled() {
        return file != null;
    }

    /** 目标文件；no-op 实例返回 {@code null}。 */
    public Path file() {
        return file;
    }

    /**
     * 追加一行（{@link Consumer} 形态，可直接交给 {@link HttpApi#setLogger(Consumer)}）。
     * 时间戳由本类添加，传入的 {@code line} 不应自带时间。
     */
    @Override
    public void accept(String line) {
        write(line);
    }

    /**
     * 追加一行日志。禁用/已损坏时直接返回；任何异常都在内部消化。
     *
     * @param line 日志正文；{@code null} 按空串处理（仍写时间戳，便于发现异常调用）
     */
    public void write(String line) {
        if (file == null) {
            return;
        }
        String text = line == null ? "" : line;
        synchronized (lock) {
            if (broken) {
                return;
            }
            try {
                BufferedWriter out = ensureOpen();
                out.write(LocalDateTime.now().format(TIMESTAMP));
                out.write(' ');
                out.write(text);
                out.newLine();
                // 每行 flush：请求量很小（限流默认 5 req/s），换来 tail -f 即时可见
                out.flush();
            } catch (IOException e) {
                broken = true;
                closeWriter();
                report("request log disabled (" + file + ") after write failure: " + e);
            }
        }
    }

    /** 关闭句柄（幂等，可在任意线程调用；此后的写入为 no-op）。 */
    @Override
    public void close() {
        synchronized (lock) {
            closeWriter();
        }
    }

    // ─────────────────────────── 内部：懒打开与关闭 ───────────────────────────

    private BufferedWriter ensureOpen() throws IOException {
        if (writer != null) {
            return writer;
        }
        Path parent = file.getParent();
        if (parent != null) {
            // 先建目录：logs/ 一般已存在，但 headless/自定义 GAMEDIR 下不能假定
            Files.createDirectories(parent);
        }
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return writer;
    }

    private void closeWriter() {
        BufferedWriter out = writer;
        writer = null;
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // 关闭失败无处可报（重启会重新懒打开），忽略
        }
    }

    private void report(String message) {
        Consumer<String> sink = errorSink;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(message);
        } catch (RuntimeException ignored) {
            // 上报出口自己出错也不能再抛
        }
    }

    @Override
    public String toString() {
        return "RequestLog[" + Objects.toString(file, "disabled") + "]";
    }
}
