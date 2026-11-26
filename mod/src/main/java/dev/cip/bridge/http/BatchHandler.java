package dev.cip.bridge.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.cip.bridge.exec.CommandRunner;
import dev.cip.bridge.exec.CommandValidator;

/**
 * {@code POST /batch}：批量命令（CB-08，SPEC v1.1 §4.5）。
 *
 * <p><b>语义</b>：先<b>全量校验</b>（任何一条非法 → 403 报首个 {@code index}/{@code command}，
 * 一条都不执行），校验通过才按 {@code spacing_ticks × 50 ms} 的间隔逐条下发；默认静默，
 * 只回成功计数与失败项。</p>
 *
 * <p><b>与 {@code /batch-file} 的关系（CB-09）</b>：真正的执行语义全部在
 * {@link #executeBatch(List, int, boolean, boolean, String)} 里；{@link #handle} 只负责解析
 * {@code /batch} 的 body 然后调用它。{@link BatchFileHandler} 把"本地文件按行切出的命令列表"
 * 交给同一个 {@code executeBatch}，因此两个端点的校验顺序、写闸门与响应字段完全一致——
 * 端点差异只在"命令从哪来"。</p>
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：本类在 {@code cipbridge-http-*} 线程上运行，
 * <b>不 import 任何 {@code net.minecraft} 类型</b>。JSON 解析、schema 校验、denylist、
 * 写闸门全部在这里完成；真正的 {@code sendCommand} 由
 * {@link CommandRunner#sendOnly(String)}（quiet）或 {@link CommandRunner#run(String, int)}
 * （verbose）经 {@code ThreadBridge.onClient} 投递到客户端线程。因此"HTTP 线程零 MC 访问"
 * 可以在字节码层审阅：{@code handle} 里没有任何 MC 引用。</p>
 *
 * <p><b>校验顺序</b>：body/schema 错误（400）→ 全量命令校验（403，denylist 先于写闸门，
 * 与 {@code /cmd} 一致）→ 执行。写闸门关闭时按"整个批次"拒绝（403 {@code mutations_disabled}，
 * 不带 index），而不是逐条报错。</p>
 *
 * <p><b>第二道写闸（CB-10）</b>：全量校验通过后再判 {@code confirm}——body 字段 {@code confirm}
 * 必须等于 bridge token 才允许执行<b>任一条</b>危险命令（{@link CommandValidator#isDangerous(String)}），
 * 否则整批 403 {@code confirmation_required}、一条都不执行。因此失败优先级是
 * schema 400 → 命令非法 403（denylist/index）→ {@code mutations_disabled} 403 → {@code confirmation_required} 403。
 * {@code /batch-file} 走同一个 {@link #executeBatch}，confirm 语义完全一致。</p>
 *
 * <p><b>失败语义</b>：第一条就 {@code not_ingame}/{@code timeout}（一条都没送达）时按 {@code /cmd}
 * 的语义回 503/504 错误体——此时整批没有任何副作用；第一条成功之后才出现的失败记入
 * {@code failed:[{"i":..,"why":".."}]}，{@code stop_on_error=true} 时立即停止后续下发。
 * {@code ok} 表示"没有任何执行失败"。</p>
 *
 * <p><b>响应</b>：quiet = {@code {"ok":true,"sent":N,"failed":[],"ms":..}}；
 * {@code verbose=true} 额外带 {@code "results":[{"i":..,"command":"..","took_ms":..,"reply":[...]}]}
 * （SPEC 只要求"才回逐条输出"，字段名此处定为 {@code results}）。</p>
 */
public final class BatchHandler implements HttpApi.Handler {

    /** 单批命令条数上限（SPEC §4.5）。 */
    public static final int MAX_COMMANDS = 512;

    /** {@code spacing_ticks} 默认值（SPEC §4.5：1 tick = 50 ms）。 */
    public static final int DEFAULT_SPACING_TICKS = 1;

    /** {@code spacing_ticks} 上限（SPEC §4.5）。 */
    public static final int MAX_SPACING_TICKS = 20;

    /** 一个 tick 的毫秒数（20 tps）。 */
    public static final int TICK_MS = 50;

    /** {@code stop_on_error} 默认值（SPEC §4.5）。 */
    public static final boolean DEFAULT_STOP_ON_ERROR = true;

    /** {@code verbose} 默认值（SPEC §4.5）。 */
    public static final boolean DEFAULT_VERBOSE = false;

    private final BooleanSupplier allowMutations;

    private final Supplier<String> token;

    /**
     * @param allowMutations 写闸门：返回 {@code true} 才允许执行；运行期传
     *                       {@code () -> Config.ALLOW_MUTATIONS.get()}（默认 false）
     * @param token          bridge token 提供者（CB-10：危险命令的 {@code confirm} 与之比对）
     */
    public BatchHandler(BooleanSupplier allowMutations, Supplier<String> token) {
        this.allowMutations = allowMutations;
        this.token = token;
    }

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        JsonObject root = parseBody(request);
        List<String> commands = readCommands(root);
        int spacingTicks = readInt(root, "spacing_ticks", DEFAULT_SPACING_TICKS);
        boolean stopOnError = readBoolean(root, "stop_on_error", DEFAULT_STOP_ON_ERROR);
        boolean verbose = readBoolean(root, "verbose", DEFAULT_VERBOSE);
        String confirm = readOptionalString(root, "confirm");
        return executeBatch(commands, spacingTicks, stopOnError, verbose, confirm);
    }

    /**
     * 批量执行的全部"语义"：参数校验 → 全量命令校验 → 逐条下发 → 组装响应。
     *
     * <p>{@code /batch}（{@link #handle}）与 {@code /batch-file}（{@link BatchFileHandler}）
     * 共用本方法，保证两个端点的校验顺序、写闸门、失败语义与响应字段<b>完全一致</b>；
     * 唯一区别只是命令列表的来源（JSON 数组 vs 本地文件按行切分）。</p>
     *
     * <p><b>线程铁律（SPEC §2.4）</b>：本方法在 {@code cipbridge-http-*} 线程上执行，
     * 只做纯 JDK/字符串工作；真正的 {@code sendCommand} 由 {@code CommandRunner} 经
     * {@code ThreadBridge.onClient} 投递到客户端线程。</p>
     *
     * @param commands     已切分好的命令文本（不含空行；调用方保证长度 ≤ {@link #MAX_COMMANDS}）
     * @param spacingTicks 相邻两条之间的间隔（tick），范围 0..{@link #MAX_SPACING_TICKS}，越界 → 400
     * @param stopOnError  首条之后的执行失败是否立即停止
     * @param verbose      {@code true} 才逐条带回显（{@code results}）
     * @param confirm      第二道写闸的确认值（body 字段 {@code confirm}；{@code null} = 未提供）。
     *                     任一条危险且与 token 不符 → 全批 403 {@code confirmation_required}，一条都不执行
     * @return 与 SPEC §4.5/§4.6 一致的响应（200 / 400 / 403 / 503 / 504）
     */
    public HttpApi.Response executeBatch(
            List<String> commands, int spacingTicks, boolean stopOnError, boolean verbose, String confirm) {
        checkSpacing(spacingTicks);

        // ── 全量校验（SPEC §4.5）：任一非法 → 403 报首个，一条都不执行。以下全程零 MC 访问。 ──
        boolean allow = allowMutations.getAsBoolean();
        for (int i = 0; i < commands.size(); i++) {
            CommandValidator.Validation validation = CommandValidator.validate(commands.get(i), allow);
            if (!validation.ok()) {
                if ("mutations_disabled".equals(validation.code())) {
                    // 写闸门按整个批次拒绝：不是某一条命令的问题，故不带 index/command
                    return HttpApi.Response.error(403, validation.code(), validation.error());
                }
                return invalidCommand(validation, i, commands.get(i));
            }
        }

        // ── 第二道写闸（CB-10，SPEC §7）：第一道闸全批通过后才判 confirm。 ──
        // 任一条危险且 confirm 不等于 token → 整批 403，一条都不执行（不做部分执行）。
        // confirm 只比一次（是否"这个调用方知道 token"），不逐条确认 —— 任务卡明确"整批 confirm 即可"。
        for (int i = 0; i < commands.size(); i++) {
            if (CommandValidator.isDangerous(commands.get(i))
                    && !ConfirmGate.matches(token, confirm)) {
                return ConfirmGate.required(commands.get(i));
            }
        }

        // ── 逐条下发（每条都经 ThreadBridge.onClient 投递到客户端线程） ──
        long startedNanos = System.nanoTime();
        int sent = 0;
        List<Failure> failures = new ArrayList<>();
        JsonArray results = verbose ? Json.arr() : null;
        long spacingMs = spacingTicks * (long) TICK_MS;

        for (int i = 0; i < commands.size(); i++) {
            if (i > 0 && spacingMs > 0L) {
                sleep(spacingMs);
            }
            String command = commands.get(i);
            Outcome outcome = verbose ? dispatchVerbose(command) : dispatchQuiet(command);
            if (outcome.ok()) {
                sent++;
                if (verbose) {
                    results.add(verboseEntry(i, command, outcome));
                }
                continue;
            }
            if (i == 0) {
                // 第一条就没送达 → 整批没有任何副作用，按 /cmd 的语义回真正的错误（503/504）
                return HttpApi.Response.error(outcome.status(), outcome.code(), outcome.error());
            }
            failures.add(new Failure(i, outcome.error()));
            if (stopOnError) {
                break;
            }
        }

        JsonObject json = Json.obj();
        json.addProperty("ok", failures.isEmpty());
        json.addProperty("sent", sent);
        JsonArray failed = Json.arr();
        for (Failure failure : failures) {
            JsonObject item = Json.obj();
            item.addProperty("i", failure.index());
            item.addProperty("why", failure.why());
            failed.add(item);
        }
        json.add("failed", failed);
        json.addProperty("ms", (System.nanoTime() - startedNanos) / 1_000_000L);
        if (verbose) {
            json.add("results", results);
        }
        return HttpApi.Response.json(json);
    }

    // ─────────────────────────── body 解析与 schema（纯 JDK） ───────────────────────────

    /** 解析 JSON body（空/语法错/非对象 → 400）；包内可见，供 {@link BatchFileHandler} 复用。 */
    static JsonObject parseBody(HttpApi.Request request) {
        byte[] body = request.body();
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw badRequest("request body must be a JSON object");
        }
        try {
            return Json.parseObject(text);
        } catch (JsonParseException | IllegalStateException e) {
            // JsonSyntaxException（语法错）与"顶层不是对象"（IllegalStateException）都算 400
            throw badRequest("request body is not a valid JSON object: " + e.getMessage());
        }
    }

    private static List<String> readCommands(JsonObject root) {
        JsonElement element = root.get("commands");
        if (element == null || element.isJsonNull()) {
            throw badRequest("missing required field 'commands'");
        }
        if (!element.isJsonArray()) {
            throw badRequest("'commands' must be a JSON array");
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() > MAX_COMMANDS) {
            throw badRequest("'commands' exceeds the limit of " + MAX_COMMANDS + " entries: " + array.size());
        }
        List<String> commands = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw badRequest("commands[" + i + "] must be a string");
            }
            commands.add(item.getAsString());
        }
        return commands;
    }

    /** 读取可选整数；缺省用 {@code fallback}，非整数 → 400。包内可见，供 {@link BatchFileHandler} 复用。 */
    static int readInt(JsonObject root, String name, int fallback) {
        JsonElement element = root.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw badRequest("'" + name + "' must be an integer");
        }
        try {
            int value = element.getAsInt();
            if (element.getAsDouble() != value) {
                throw badRequest("'" + name + "' must be an integer: " + element.getAsString());
            }
            return value;
        } catch (NumberFormatException e) {
            throw badRequest("'" + name + "' must be an integer: " + element.getAsString());
        }
    }

    /** 读取可选布尔；缺省用 {@code fallback}，非布尔 → 400。包内可见，供 {@link BatchFileHandler} 复用。 */
    static boolean readBoolean(JsonObject root, String name, boolean fallback) {
        JsonElement element = root.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw badRequest("'" + name + "' must be a boolean");
        }
        return element.getAsBoolean();
    }

    /** 统一 400 {@code bad_request}（包内可见，供 {@link BatchFileHandler} 复用）。 */
    static HttpApi.ApiException badRequest(String message) {
        return new HttpApi.ApiException(400, "bad_request", message);
    }

    /**
     * 读取可选字符串（CB-10：{@code confirm} 字段）。缺省/JSON {@code null} → {@code null}（表示未提供），
     * 非字符串 → 400。包内可见，供 {@link BatchFileHandler} 复用。
     *
     * <p>注意：空串会原样返回（不是 {@code null}）——它和"没带 confirm"在语义上都过不了
     * {@link ConfirmGate#matches}，但保留原始值能避免在 schema 层替调用方猜意图。</p>
     */
    static String readOptionalString(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw badRequest("'" + name + "' must be a string");
        }
        return element.getAsString();
    }

    /**
     * {@code spacing_ticks} 范围校验（SPEC §4.5：0..20）。{@code /batch} 与 {@code /batch-file}
     * 共用；越界按"请求参数不成立"回 400，且发生在任何命令校验/文件读取之前。
     */
    static void checkSpacing(int spacingTicks) {
        if (spacingTicks < 0 || spacingTicks > MAX_SPACING_TICKS) {
            throw badRequest("'spacing_ticks' must be in 0.." + MAX_SPACING_TICKS + ": " + spacingTicks);
        }
    }

    // ─────────────────────────── 下发（全部经 CommandRunner → ThreadBridge） ───────────────────────────

    /** 一条命令的下发结果（quiet 与 verbose 统一成同一形状，便于共用循环）。 */
    private record Outcome(int status, String code, String error, long tookMs, List<String> reply) {

        boolean ok() {
            return status == 200;
        }
    }

    /** quiet：只投递不回显（{@code /batch} 默认模式）。 */
    private static Outcome dispatchQuiet(String command) {
        CommandRunner.SendResult result = CommandRunner.sendOnly(command);
        return new Outcome(result.status(), result.code(), result.error(), result.tookMs(), List.of());
    }

    /** verbose：逐条带回显（复用 {@code /cmd} 的窗口采集）。 */
    private static Outcome dispatchVerbose(String command) {
        CommandRunner.Result result = CommandRunner.run(command, CommandRunner.DEFAULT_WAIT_MS);
        return new Outcome(result.status(), result.code(), result.error(), result.tookMs(), result.reply());
    }

    private record Failure(int index, String why) {
    }

    /** SPEC §4.5：批量的任一命令非法 → 403（不执行任何一条），带首个 {@code index}/{@code command}。 */
    private static HttpApi.Response invalidCommand(
            CommandValidator.Validation validation, int index, String command) {
        JsonObject json = Json.obj();
        json.addProperty("ok", false);
        json.addProperty("error", validation.error());
        json.addProperty("code", validation.code());
        json.addProperty("index", index);
        json.addProperty("command", command);
        return HttpApi.Response.json(403, json);
    }

    private static JsonObject verboseEntry(int index, String command, Outcome outcome) {
        JsonObject entry = Json.obj();
        entry.addProperty("i", index);
        entry.addProperty("command", command);
        entry.addProperty("took_ms", outcome.tookMs());
        JsonArray reply = Json.arr();
        for (String line : outcome.reply()) {
            reply.add(line);
        }
        entry.add("reply", reply);
        return entry;
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
}
