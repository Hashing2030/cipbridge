package dev.cip.bridge.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.cip.bridge.exec.CommandRunner;
import dev.cip.bridge.exec.CommandValidator;

/**
 * {@code GET /cmd}：发一条命令并拿到回显（CB-04，SPEC v1.1 §4.2）。
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：本类在 {@code cipbridge-http-*} 线程上运行，
 * <b>不 import 任何 net.minecraft 类型</b>，也绝不直接发命令——校验（{@code /} 前缀、denylist）、
 * 写闸门（{@code allow_mutations}）全部在这里完成，真正 {@code sendCommand} 由
 * {@link CommandRunner#run(String, int)} 经 {@code ThreadBridge.onClient} 投递到客户端线程。
 * 因此"HTTP 线程零 MC 访问"可以在编译期一眼审阅：{@code handle} 里没有任何 MC 引用。</p>
 *
 * <p>校验顺序是刻意的（也是验收 2b 的前提）：{@code /} 前缀 → denylist → 写闸门 → 执行。
 * 先判 denylist，{@code allow_mutations=false} 时 {@code /stop} 回的是"命令被禁"而不是
 * "写操作被关"，错误语义更准确。具体规则已抽到 {@link CommandValidator}（CB-08 起与
 * {@code /batch} 共用同一份实现，避免两个写端点各写一套 denylist 而漂移）。</p>
 *
 * <p><b>第二道写闸（CB-10）</b>：第一道闸（denylist + {@code allow_mutations}）通过后，
 * {@link CommandValidator#isDangerous(String)} 判为危险的命令还要求 query 参数 {@code confirm}
 * 等于 bridge token，否则 403 {@code confirmation_required}；不危险的命令<b>不需要</b> confirm。
 * 比较由 {@link ConfirmGate} 在<b>本线程</b>用 {@code MessageDigest.isEqual} 完成（无投递、无 MC 访问）。</p>
 *
 * <p>{@code allowMutations} 传的是 {@link BooleanSupplier} 而不是布尔值：一是让 http 包不依赖
 * {@code Config}（可在裸 JDK 进程里用 {@code () -> false} / {@code () -> true} 直接构造做冒烟），
 * 二是每次请求都实时读配置，改配置后不必重启 HTTP 服务。</p>
 */
public final class CommandHandler implements HttpApi.Handler {

    /** 禁止通过写端点执行的命令首 token（SPEC §4.2）；CB-08 起与 {@code /batch} 共用同一个集合。 */
    public static final Set<String> DENYLIST = CommandValidator.DENYLIST;

    /** {@code wait} 默认值（SPEC §4.2）。 */
    public static final int DEFAULT_WAIT_MS = CommandRunner.DEFAULT_WAIT_MS;

    /** {@code wait} 上限（SPEC §4.2）。 */
    public static final int MAX_WAIT_MS = CommandRunner.MAX_WAIT_MS;

    private final BooleanSupplier allowMutations;

    private final Supplier<String> token;

    /**
     * @param allowMutations 写闸门：返回 {@code true} 才允许执行；运行期传
     *                       {@code () -> Config.ALLOW_MUTATIONS.get()}（默认 false）
     * @param token          bridge token 提供者（CB-10：危险命令的 {@code confirm} 与之比对）；
     *                       运行期传 {@code () -> token}，由 {@code CipBridge} 在启动时注入
     */
    public CommandHandler(BooleanSupplier allowMutations, Supplier<String> token) {
        this.allowMutations = allowMutations;
        this.token = token;
    }

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        // ↓ 运行在 HTTP 线程：只做纯字符串校验与 JSON 组装，无任何 MC 引用。
        String raw = request.query("c");
        if (raw == null || raw.isBlank()) {
            return HttpApi.Response.error(400, "bad_request", "missing required query parameter 'c'");
        }
        String command = raw.strip();
        // 校验（前缀 → 控制字符 → 空命令 → denylist → 写闸门）与 CB-08 的 /batch 共用
        // {@link CommandValidator}，顺序与语义和 CB-04 落地时完全一致。
        CommandValidator.Validation validation = CommandValidator.validate(command, allowMutations.getAsBoolean());
        if (!validation.ok()) {
            return HttpApi.Response.error(validation.status(), validation.code(), validation.error());
        }

        // ── 第二道写闸（CB-10，SPEC §7）─────────────────────────────────────────────
        // 只有第一道闸（denylist + allow_mutations）通过后才谈 confirm：allow_mutations=false 时
        // 危险命令回的仍是 mutations_disabled，而不是让调用方以为"补个 confirm 就能跑"。
        // confirm 比较就在本线程完成（纯字节比较，零 MC 访问、无投递）。
        if (CommandValidator.isDangerous(command)
                && !ConfirmGate.matches(token, request.query("confirm"))) {
            return ConfirmGate.required(command);
        }

        int waitMs = CommandRunner.clampWait(request.queryInt("wait", DEFAULT_WAIT_MS));
        CommandRunner.Result result = CommandRunner.run(command, waitMs);
        if (!result.ok()) {
            return HttpApi.Response.error(result.status(), result.code(), result.error());
        }

        JsonObject json = Json.obj();
        json.addProperty("ok", true);
        json.addProperty("sent", command);
        json.addProperty("took_ms", result.tookMs());
        JsonArray reply = Json.arr();
        for (String line : result.reply()) {
            reply.add(line);
        }
        json.add("reply", reply);
        json.addProperty("truncated", result.truncated());
        if (result.reply().isEmpty()) {
            json.addProperty("hint", "no chat output");
        }
        return HttpApi.Response.json(json);
    }
}
