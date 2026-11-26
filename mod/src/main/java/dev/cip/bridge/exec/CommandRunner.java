package dev.cip.bridge.exec;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

/**
 * {@code /cmd} 的执行侧（CB-04，SPEC v1.1 §4.2）：投递命令 + 收集回显 + 日志兜底。
 * CB-08 起另提供 {@link #sendOnly(String)}：只投递、不回显，供 {@code /batch} 的 quiet 模式
 * （SPEC §4.5）逐条下发使用——两条路径共用同一个 {@link #send(String)} MC 边界。
 *
 * <p><b>线程分工（SPEC §2.4）</b>：{@link #run(String, int)} 由 HTTP 线程调用，方法体内
 * 对 Minecraft 的访问只有一处——{@link #send(String)}，而它**只经
 * {@link ThreadBridge#onClient(java.util.function.Supplier)} 投递到客户端线程执行**。
 * 命令字符串的校验、denylist、写闸门都在 {@code CommandHandler}（HTTP 线程，零 MC）。
 * 等待窗口、读取环形缓冲、读日志文件都不碰 MC 状态。</p>
 *
 * <p><b>失败语义</b>：未进世界 → {@link Result} 里 503 {@code not_ingame}；
 * 客户端线程 2 s 内没接住"发送"这个动作 → 504 {@code timeout}。
 * 都映射为可读 JSON，不把 HTTP 线程永久挂住，也不让异常穿透 {@code HttpApi} 变成 500。</p>
 */
public final class CommandRunner {

    /** {@code wait} 默认窗口（SPEC §4.2）。 */
    public static final int DEFAULT_WAIT_MS = 2_000;

    /** {@code wait} 上限（SPEC §4.2）。 */
    public static final int MAX_WAIT_MS = 5_000;

    /** 等待"命令投递到客户端线程"这一个瞬间动作的上限；正常是微秒级。 */
    private static final long SEND_TIMEOUT_MS = 2_000L;

    /** 窗口最短观察时长（卡标题"1–2 s 聊天回显捕获"）：再早就提前收束会漏掉同行输出。 */
    private static final int MIN_WINDOW_MS = 1_000;

    /** 已有回显后，静默多久认为输出结束。 */
    private static final int QUIET_MS = 300;

    /** 窗口轮询间隔。 */
    private static final long POLL_MS = 50L;

    /** 单次回显最多回多少行（防止 /help 之类的长输出把 JSON 撑爆）。 */
    private static final int MAX_REPLY_LINES = 100;

    /** 兜底日志只看尾部这段字节。 */
    private static final int LOG_TAIL_BYTES = 64 * 1024;

    /** 兜底日志最多回多少行。 */
    private static final int LOG_TAIL_LINES = 20;

    /** 日志里聊天行的标记（卡/SPEC 约定）。 */
    private static final String CHAT_MARKER = "[CHAT]";

    /** 日志行首时间戳 {@code [HH:MM:SS]}。 */
    private static final Pattern LOG_TIME = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})\\]");

    /** 日志时间与墙钟的容差（日志写入与 System.currentTimeMillis 之间有几十毫秒抖动）。 */
    private static final long LOG_CLOCK_TOLERANCE_MS = 1_000L;

    private CommandRunner() {
    }

    /**
     * 一次 /cmd 的结果。
     *
     * @param status    建议的 HTTP 状态码：200 成功，503 未进世界，504 游戏线程超时
     * @param code      失败时的错误码（对应 SPEC 的统一错误体 {@code code}）
     * @param error     失败时的可读原因
     * @param reply     回显行（按到达顺序；可能来自事件缓冲或日志兜底）
     * @param tookMs    从进入本方法到拿到结果的毫秒数
     * @param truncated 窗口内是否因环形缓冲覆盖而丢了行
     */
    public record Result(int status, String code, String error, List<String> reply, long tookMs, boolean truncated) {

        /** 是否成功（HTTP 层据此决定 200 还是错误体）。 */
        public boolean ok() {
            return status == 200;
        }
    }

    /**
     * 一次"只下发、不采集回显"的结果（CB-08 {@code /batch} quiet 模式）。
     *
     * @param status  建议的 HTTP 状态码：200 成功，503 未进世界，504 游戏线程超时
     * @param code    失败时的错误码
     * @param error   失败时的可读原因
     * @param tookMs  从进入 {@link #sendOnly(String)} 到拿到结果的毫秒数
     */
    public record SendResult(int status, String code, String error, long tookMs) {

        /** 是否成功（成功才计入 {@code sent}）。 */
        public boolean ok() {
            return status == 200;
        }
    }

    /** 执行一条命令（{@code command} 含前导 {@code /}），并收集窗口内的回显。 */
    public static Result run(String command, int waitMs) {
        long startedNanos = System.nanoTime();
        String bareCommand = stripLeadingSlash(command);

        ChatCapture capture = ChatCapture.get();
        long marker = capture.mark();
        long markerMillis = System.currentTimeMillis();
        int windowMs = clampWait(waitMs);

        try {
            // 唯一一处 MC 访问：send() 只在客户端线程执行（见 send 的 Javadoc）。
            ThreadBridge.await(ThreadBridge.onClient(() -> send(bareCommand)), SEND_TIMEOUT_MS);
        } catch (ThreadBridge.BridgeException e) {
            long took = tookMs(startedNanos);
            if (e.isTimeout()) {
                return new Result(504, "timeout",
                        "timed out after " + SEND_TIMEOUT_MS + " ms waiting for the client thread",
                        List.of(), took, false);
            }
            return new Result(503, "not_ingame", "no minecraft", List.of(), took, false);
        }

        awaitWindow(capture, marker, markerMillis, windowMs);

        ChatCapture.Window window = capture.since(marker);
        List<String> reply = window.texts();
        boolean truncated = window.truncated();
        if (reply.isEmpty()) {
            // 事件一条都没来：兜底读日志（文件 I/O 在 HTTP 线程是允许的，不碰 MC 状态）
            reply = readChatFromLog(markerMillis);
            truncated = false;
        }
        if (reply.size() > MAX_REPLY_LINES) {
            reply = List.copyOf(reply.subList(0, MAX_REPLY_LINES));
            truncated = true;
        }
        return new Result(200, "ok", "", reply, tookMs(startedNanos), truncated);
    }

    /**
     * 只下发、不采集回显（CB-08 {@code /batch} 的 quiet 模式）。
     *
     * <p>与 {@link #run(String, int)} 共用同一道 MC 边界：<b>唯一一处 MC 访问</b>是
     * {@link #send(String)}，且只经 {@link ThreadBridge#onClient(Runnable)} 投递到客户端线程。
     * 本方法不碰 {@link ChatCapture}、不读日志，因此单条开销只有"投递 + 一次 {@code sendCommand}",
     * 批量也不会被回显窗口拖成分钟级。</p>
     *
     * <p>失败语义与 {@code run} 一致：未进世界 → 503 {@code not_ingame}；客户端线程 2 s 内
     * 没接住"发送"这个动作 → 504 {@code timeout}。</p>
     */
    public static SendResult sendOnly(String command) {
        long startedNanos = System.nanoTime();
        String bareCommand = stripLeadingSlash(command);
        try {
            // 唯一一处 MC 访问：send() 只在客户端线程执行（见 send 的 Javadoc）。
            ThreadBridge.await(ThreadBridge.onClient(() -> send(bareCommand)), SEND_TIMEOUT_MS);
        } catch (ThreadBridge.BridgeException e) {
            long took = tookMs(startedNanos);
            if (e.isTimeout()) {
                return new SendResult(504, "timeout",
                        "timed out after " + SEND_TIMEOUT_MS + " ms waiting for the client thread", took);
            }
            return new SendResult(503, "not_ingame", "no minecraft", took);
        }
        return new SendResult(200, "ok", "", tookMs(startedNanos));
    }

    /** 把 wait 参数钳制到 [0, MAX_WAIT_MS]。 */
    public static int clampWait(int waitMs) {
        if (waitMs < 0) {
            return 0;
        }
        return Math.min(waitMs, MAX_WAIT_MS);
    }

    // ─────────────────────────── 客户端线程 ───────────────────────────

    /**
     * 真正发命令：<b>只允许在客户端线程执行</b>（由 {@link ThreadBridge#onClient} 投递）。
     *
     * <p>玩家/连接为空说明还没进世界，抛 {@link ThreadBridge.BridgeException}（非超时），
     * 由 {@link #run(String, int)} 映射成 503 {@code not_ingame}。</p>
     */
    private static void send(String bareCommand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || minecraft.player.connection == null) {
            throw new ThreadBridge.BridgeException("no minecraft player", false);
        }
        minecraft.player.connection.sendCommand(bareCommand);
    }

    // ─────────────────────── 等待窗口（HTTP 线程，纯 JDK） ───────────────────────

    /**
     * 等窗口结束：至少 {@code min(window, MIN_WINDOW_MS)}，最多 {@code window}。
     *
     * <p>已收到回显且静默 {@value #QUIET_MS} ms 时提前收束——典型 {@code /say hi} 约 1 s 返回，
     * 而不是白等满 {@code wait}。{@code wait} 始终是硬上限，不会被突破。</p>
     */
    private static void awaitWindow(ChatCapture capture, long marker, long markerMillis, int windowMs) {
        if (windowMs <= 0) {
            return;
        }
        long deadline = markerMillis + windowMs;
        long earliestExit = markerMillis + Math.min(windowMs, MIN_WINDOW_MS);
        while (true) {
            long now = System.currentTimeMillis();
            long remaining = deadline - now;
            if (remaining <= 0L) {
                return;
            }
            if (now >= earliestExit && capture.countSince(marker) > 0
                    && now - capture.lastAtMillis() >= QUIET_MS) {
                return;
            }
            sleep(Math.min(POLL_MS, remaining));
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─────────────────────────── 日志兜底（HTTP 线程） ───────────────────────────

    /**
     * 读 {@code logs/latest.log} 尾部含 {@code [CHAT]} 的行。
     *
     * <p>路径用 {@link FMLPaths#GAMEDIR}（FML 的路径服务，不是 Minecraft 对象），因此本方法
     * 在 HTTP 线程上调用也不违反"HTTP 线程零 MC 访问"。行首的 {@code [HH:MM:SS]} 与
     * {@code markerMillis} 比对，避免把窗口之前的旧聊天当成这次命令的回显；没有时间戳的行
     * 一律保留（尽力而为）。跨午夜的时间比较会误判——只影响兜底路径，不影响主路径。</p>
     */
    private static List<String> readChatFromLog(long markerMillis) {
        Path log = latestLogPath();
        if (log == null || !Files.isReadable(log)) {
            return List.of();
        }
        LocalTime since = LocalTime.ofInstant(
                Instant.ofEpochMilli(markerMillis - LOG_CLOCK_TOLERANCE_MS), ZoneId.systemDefault());
        List<String> found = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long from = Math.max(0L, length - LOG_TAIL_BYTES);
            file.seek(from);
            byte[] buffer = new byte[(int) (length - from)];
            file.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            // 从中间截断时第一行可能是半行，跳过
            int start = from > 0L ? 1 : 0;
            for (int i = start; i < lines.length; i++) {
                String line = lines[i];
                int at = line.indexOf(CHAT_MARKER);
                if (at < 0) {
                    continue;
                }
                if (!isAfter(line, since)) {
                    continue;
                }
                String message = line.substring(at + CHAT_MARKER.length()).strip();
                if (!message.isEmpty()) {
                    found.add(message);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        if (found.size() > LOG_TAIL_LINES) {
            found = new ArrayList<>(found.subList(found.size() - LOG_TAIL_LINES, found.size()));
        }
        return List.copyOf(found);
    }

    /** {@code logs/latest.log} 路径；FML 尚未初始化（无游戏目录）时返回 {@code null}。 */
    private static Path latestLogPath() {
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            return gameDir == null ? null : gameDir.resolve("logs").resolve("latest.log");
        } catch (RuntimeException e) {
            // FML 路径尚未建立（例如裸 JDK 进程）：没有日志可兜底，不是错误
            return null;
        }
    }

    /** 行首 {@code [HH:MM:SS]} 是否不早于 {@code since}；无时间戳则保留。 */
    private static boolean isAfter(String line, LocalTime since) {
        Matcher matcher = LOG_TIME.matcher(line);
        if (!matcher.find()) {
            return true;
        }
        try {
            LocalTime at = LocalTime.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
            return !at.isBefore(since);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static String stripLeadingSlash(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.strip();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static long tookMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
