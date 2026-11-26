package dev.cip.bridge.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code POST /batch-file}：读本地文件批量执行命令（CB-09，SPEC v1.1 §4.6）。
 *
 * <p><b>请求</b>：{@code {"path":"/tmp/cip/batch.txt","spacing_ticks":1}}（可选
 * {@code stop_on_error}/{@code verbose}，语义同 {@code /batch}）。文件一行一条命令，
 * 空行/纯空白行跳过，UTF-8，上限 1 MiB / 512 行。</p>
 *
 * <p><b>路径白名单（SPEC §4.6）</b>：{@code path} 先 {@link Path#normalize()} 再与
 * {@code /tmp/cip} <b>按路径分量</b>比较（{@link Path#startsWith(Path)}），不在其下即
 * 403 {@code path_not_allowed}。因此 {@code /tmp/cip/../../etc/hosts} 归一化成
 * {@code /etc/hosts} 会被拒，{@code /tmp/cipX/a.txt} 这种"前缀像但目录不同"的写法也会被拒
 * （分量比较，不是字符串前缀）。白名单范围<b>不</b>放宽、也不额外收紧。</p>
 *
 * <p><b>符号链接加固（CB-10 10.3）</b>：上面只是字符串层面，名字在白名单内、目标在白名单外的软链
 * 仍会绕过去。故白名单判定后再 {@link Path#toRealPath()} 解析符号链接，与 {@code /tmp/cip} 的
 * <b>真实路径</b>再做一次 {@code startsWith}；不通过 → 403 {@code path_not_allowed}。顺序是
 * "先 {@code isRegularFile} 判 404、再 {@code toRealPath} 判 403"——{@code toRealPath} 对不存在的
 * 文件会抛异常，先后颠倒会把 404 误报成 403。此后<b>读的是真实路径</b>，检查与读取是同一个对象。</p>
 *
 * <p><b>第二道写闸</b>：body 字段 {@code confirm} 原样交给 {@code BatchHandler.executeBatch}，
 * 与 {@code /batch} 完全同一套语义（任一条危险命令且 confirm 不等于 token → 整批 403
 * {@code confirmation_required}，一条都不执行）。本类不重复实现判定或比较。</p>
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：本类<b>不 import 任何 {@code net.minecraft} 类型</b>，
 * 在 {@code cipbridge-http-*} 线程上完成 JSON 解析、白名单校验、读文件与按行切分（纯文件 I/O）；
 * 命令的真正下发由 {@link BatchHandler#executeBatch(List, int, boolean, boolean)} 经
 * {@code ThreadBridge.onClient} 投递，因此"读文件在 HTTP 线程、下发在客户端线程"可以在
 * 字节码层审阅。</p>
 *
 * <p><b>错误码</b>：{@code path} 缺失/非字符串/空 → 400 {@code bad_request}；
 * {@code spacing_ticks}/{@code stop_on_error}/{@code verbose}/{@code confirm} 类型非法 → 400
 * （schema 层先于路径检查）；白名单外（含解析符号链接后越界）→ 403 {@code path_not_allowed}；
 * 文件不存在/不是普通文件 → 404 {@code not_found}；
 * 超过 1 MiB → 413 {@code too_large}；非空行超过 512 → 400 {@code bad_request}。
 * 其余（全量命令校验、写闸门、confirm、逐条下发、响应字段）与 {@code /batch} 完全一致。</p>
 */
public final class BatchFileHandler implements HttpApi.Handler {

    /** 白名单根目录（SPEC §4.6：只允许 {@code /tmp/cip/} 前缀）。 */
    public static final Path ALLOWED_ROOT = Path.of("/tmp/cip");

    /** 单文件字节上限（任务卡：1 MB）。 */
    public static final long MAX_FILE_BYTES = 1L << 20;

    /** 单文件最多执行的命令条数（与 {@code /batch} 的 {@code commands} 上限一致）。 */
    public static final int MAX_LINES = BatchHandler.MAX_COMMANDS;

    /** 可选 UTF-8 BOM：不是命令内容，读文件时剥掉（只影响解码，不影响白名单/校验）。 */
    private static final char UTF8_BOM = '\uFEFF';

    private final BatchHandler batch;

    /**
     * @param batch 共享的批量执行器（{@code /batch} 用的同一个实例；只持有写闸门 supplier，
     *              无状态、线程安全）
     */
    public BatchFileHandler(BatchHandler batch) {
        this.batch = Objects.requireNonNull(batch, "batch");
    }

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        JsonObject root = BatchHandler.parseBody(request);
        String rawPath = readPath(root);
        int spacingTicks = BatchHandler.readInt(
                root, "spacing_ticks", BatchHandler.DEFAULT_SPACING_TICKS);
        boolean stopOnError = BatchHandler.readBoolean(
                root, "stop_on_error", BatchHandler.DEFAULT_STOP_ON_ERROR);
        boolean verbose = BatchHandler.readBoolean(root, "verbose", BatchHandler.DEFAULT_VERBOSE);
        String confirm = BatchHandler.readOptionalString(root, "confirm");
        // schema 层 400 先于白名单/读文件（与 /batch 的"body/schema 400 先于命令 403"一致）
        BatchHandler.checkSpacing(spacingTicks);

        // 白名单与读文件都在本线程（纯文件 I/O，零 MC 访问）；命令按下标顺序保持文件行序。
        // 两步判定：① 请求路径按分量落在 /tmp/cip 下（403）；② 解析符号链接后仍落在真实白名单下（403）。
        Path file = resolveAllowed(rawPath);
        Path realFile = resolveRealPath(file);
        List<String> commands = readCommands(realFile);
        // confirm 只对危险命令生效；校验顺序与 /batch 完全相同（共用 executeBatch）
        return batch.executeBatch(commands, spacingTicks, stopOnError, verbose, confirm);
    }

    // ─────────────────────────── body 解析（纯 JDK/Gson） ───────────────────────────

    private static String readPath(JsonObject root) {
        JsonElement element = root.get("path");
        if (element == null || element.isJsonNull()) {
            throw BatchHandler.badRequest("missing required field 'path'");
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw BatchHandler.badRequest("'path' must be a string");
        }
        String path = element.getAsString();
        if (path.isBlank()) {
            throw BatchHandler.badRequest("'path' must not be empty");
        }
        return path;
    }

    // ─────────────────────────── 路径白名单与读文件（零 MC） ───────────────────────────

    /**
     * 第一步：归一化 + 按路径分量的白名单校验。{@code normalize()} 会消掉 {@code .}/{@code ..}，
     * 之后用 {@link Path#startsWith(Path)} 按分量判断，越界一律 403。
     *
     * <p>本步只看"请求写的路径字符串"，不看文件系统；符号链接由第二步
     * {@link #resolveRealPath(Path)} 处理（CB-10 10.3）。</p>
     */
    static Path resolveAllowed(String rawPath) {
        Path normalized;
        try {
            normalized = Path.of(rawPath).normalize();
        } catch (InvalidPathException e) {
            // 非法路径字符：属于"请求参数不成立"，比 403 更准确
            throw BatchHandler.badRequest("'path' is not a valid path: " + rawPath);
        }
        if (!normalized.startsWith(ALLOWED_ROOT)) {
            throw new HttpApi.ApiException(403, "path_not_allowed",
                    "path must be under " + ALLOWED_ROOT + "/");
        }
        return normalized;
    }

    /**
     * 第二步（CB-10 10.3 符号链接加固）：把路径解析成<b>真实路径</b>后，再与<b>真实白名单目录</b>比较。
     *
     * <p>为什么必须做：第一步只是字符串层面的分量比较，{@code /tmp/cip/link.txt} 这种<b>名字</b>在
     * 白名单内、<b>目标</b>在 {@code /etc} 的符号链接会整个绕过去（CB-09 journal 已记录该风险）。
     * {@link Path#toRealPath()} 会解析路径上每一级符号链接，因此拿它和
     * {@code /tmp/cip} 的真实路径做 {@code startsWith}，才等价于"文件真的在白名单目录里"。</p>
     *
     * <p><b>顺序是关键</b>：{@code toRealPath()} 对不存在的文件会抛
     * {@link java.nio.file.NoSuchFileException}，所以必须先 {@link Files#isRegularFile(Path)} 判 404，
     * 否则"文件不存在"会被误报成 403 {@code path_not_allowed}（调用方会以为是白名单问题）。</p>
     *
     * <p>白名单目录本身也可能经过符号链接（macOS 上 {@code /tmp} 就是 {@code /private/tmp} 的链接），
     * 所以必须两边都取真实路径再比；拿真实路径去比 {@code /tmp/cip} 会在 macOS 上<b>全部</b>被判 403。</p>
     *
     * <p><b>不改白名单范围</b>：仍只允许 {@code /tmp/cip/}；本步只堵"名字在内、目标在外"的软链绕过，
     * 不放宽也不额外收紧。解析失败一律按不存在（404）或越界（403）拒绝，即 fail closed。</p>
     *
     * @param file 已通过 {@link #resolveAllowed(String)} 的归一化路径
     * @return 解析符号链接后的真实路径（后续读文件<b>读这个路径</b>，避免检查与读取用不同对象）
     */
    static Path resolveRealPath(Path file) {
        // 1) 先判存在与类型（toRealPath 对不存在的路径抛异常，顺序不能颠倒）
        if (!Files.isRegularFile(file)) {
            // 不存在、目录、断链符号链接：统一 404，不泄露文件系统结构（与 CB-09 语义一致）
            throw new HttpApi.ApiException(404, "not_found", "file not found: " + file);
        }
        Path realFile;
        try {
            // 2) 解析路径上每一级符号链接（读文件也用这个结果）
            realFile = file.toRealPath();
        } catch (IOException e) {
            // 解析不了（权限/竞态/断链）：没有可信的真实路径，按不存在处理
            throw new HttpApi.ApiException(404, "not_found", "file not found: " + file);
        }
        Path realRoot;
        try {
            realRoot = ALLOWED_ROOT.toRealPath();
        } catch (IOException e) {
            // 白名单目录本身不可解析（此时不可能有合法文件）：fail closed
            throw new HttpApi.ApiException(403, "path_not_allowed",
                    "path must be under " + ALLOWED_ROOT + "/");
        }
        // 3) 真实路径必须真的在真实白名单目录之下（分量比较，不是字符串前缀）
        if (!realFile.startsWith(realRoot)) {
            throw new HttpApi.ApiException(403, "path_not_allowed",
                    "path must be under " + ALLOWED_ROOT + "/ (symbolic links are resolved: "
                            + realFile + ")");
        }
        return realFile;
    }

    /** 读文件 → 按行切命令（strip 后跳过空行），并落实 1 MiB / 512 条上限。 */
    static List<String> readCommands(Path file) {
        // 不存在、目录、断链符号链接都没有可执行内容：统一 404，不泄露文件系统结构
        if (!Files.isRegularFile(file)) {
            throw new HttpApi.ApiException(404, "not_found", "file not found: " + file);
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            throw new HttpApi.ApiException(404, "not_found", "file not found: " + file);
        }
        if (size > MAX_FILE_BYTES) {
            throw tooLarge(size);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new HttpApi.ApiException(404, "not_found", "file not found: " + file);
        }
        // 读后复核：文件可能在 size 检查与读取之间变大
        if (bytes.length > MAX_FILE_BYTES) {
            throw tooLarge(bytes.length);
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == UTF8_BOM) {
            text = text.substring(1);
        }

        List<String> commands = new ArrayList<>();
        // split("\n") + strip() 同时兼容 LF 与 CRLF；limit=-1 保留尾部空段（尾随换行不丢行）
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            commands.add(line);
            if (commands.size() > MAX_LINES) {
                throw BatchHandler.badRequest(
                        "file has more than " + MAX_LINES + " non-empty lines: " + file);
            }
        }
        return commands;
    }

    private static HttpApi.ApiException tooLarge(long size) {
        return new HttpApi.ApiException(413, "too_large",
                "file is " + size + " bytes; the limit is " + MAX_FILE_BYTES + " bytes");
    }
}
