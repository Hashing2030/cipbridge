package dev.cip.bridge.http;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.cip.bridge.exec.ChatDisplay;
import dev.cip.bridge.exec.ThreadBridge;

/**
 * {@code POST /chat-say}：把一段文本显示到玩家聊天框（CB-14，SPEC v1.1 §4.7）。
 *
 * <p><b>线程铁律（SPEC §2.4）</b>：本类在 {@code cipbridge-http-*} 线程上运行，
 * <b>不 import 任何 net.minecraft 类型</b>——"游戏是否就绪"这种判断也不在这里做，
 * 而是交给 {@link ChatDisplay#showToPlayer(String)} 在客户端线程返回 {@code false}。
 * 因此 {@code javap -v} 本类的常量池里 {@code net/minecraft} 出现次数为 <b>0</b>，
 * 显示走 {@link ThreadBridge#onClient(java.util.function.Supplier)} 投递。</p>
 *
 * <p><b>权限</b>：token 由 {@link HttpApi#dispatch} 统一校验（无 token / token 错 → 403），
 * 本类<b>不受</b> {@code allow_mutations} 限制——纯客户端显示，不写世界（SPEC §4.7）。</p>
 *
 * <p><b>错误语义</b>（SPEC §4.7 + 通用错误体）：</p>
 * <ul>
 *   <li>无 token / token 错 → 403 {@code forbidden}（HttpApi 统一处理，未进本类）；</li>
 *   <li>空 body、缺 {@code text}、{@code text} 非字符串或空串 → 400 {@code bad_request}；</li>
 *   <li>body 不是合法 JSON 对象 → 400 {@code invalid_json}；</li>
 *   <li>游戏未就绪（{@code Minecraft}/{@code player} 为 null）→ 503 {@code not_ingame}；</li>
 *   <li>客户端线程 2 s 内没接住显示动作 → 504 {@code game_timeout}。</li>
 * </ul>
 *
 * <p><b>长度</b>：正文上限 {@value #MAX_TEXT_CHARS} 字符，超出截断并补 {@value #TRUNCATION_SUFFIX}
 * （截断位置若落在代理对中间会向前让一位，避免切出半个 emoji）。</p>
 */
public final class ChatSayHandler implements HttpApi.Handler {

    /** 正文上限（SPEC §4.7）。 */
    public static final int MAX_TEXT_CHARS = 4000;

    /** 截断标记（SPEC §4.7 字面要求）。 */
    public static final String TRUNCATION_SUFFIX = "…(truncated)";

    /** 等待客户端线程显示的超时：显示本身是常数时间，2 s 只防"客户端卡死"。 */
    public static final long DISPLAY_TIMEOUT_MS = 2_000L;

    /** 请求体字段名。 */
    public static final String FIELD_TEXT = "text";

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        // ↓ 运行在 HTTP 线程：只解析/校验/组装 JSON，零 MC 访问（MC 只在 ChatDisplay 里，且只在客户端线程）
        byte[] body = request.body();
        if (body == null || body.length == 0) {
            return HttpApi.Response.error(400, "bad_request",
                    "empty body; expected {\"" + FIELD_TEXT + "\":\"...\"}");
        }

        JsonObject json;
        try {
            json = Json.parseObject(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // JsonSyntaxException（含"是数组/字符串/裸字面量而不是对象"）都归 400
            return HttpApi.Response.error(400, "invalid_json", "body is not a JSON object: " + e.getMessage());
        }
        if (json == null) {
            return HttpApi.Response.error(400, "invalid_json", "body is not a JSON object");
        }

        JsonElement element = json.get(FIELD_TEXT);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return HttpApi.Response.error(400, "bad_request",
                    "missing or non-string field '" + FIELD_TEXT + "'");
        }

        String raw = element.getAsString();
        if (raw.isEmpty()) {
            return HttpApi.Response.error(400, "bad_request", "field '" + FIELD_TEXT + "' is empty");
        }

        Prepared prepared = prepare(raw);
        boolean shown;
        try {
            shown = Boolean.TRUE.equals(ThreadBridge.await(
                    ThreadBridge.onClient(() -> ChatDisplay.showToPlayer(prepared.text())),
                    DISPLAY_TIMEOUT_MS));
        } catch (ThreadBridge.BridgeException e) {
            if (e.isTimeout()) {
                return HttpApi.Response.error(504, "game_timeout",
                        "timed out after " + DISPLAY_TIMEOUT_MS + " ms waiting for the client thread");
            }
            // Minecraft.getInstance() == null / 客户端线程未就绪
            return HttpApi.Response.error(503, "not_ingame", "game is not ready: " + e.getMessage());
        } catch (RuntimeException e) {
            // await 会把客户端线程上的 RuntimeException 原样重抛
            return HttpApi.Response.error(500, "display_failed",
                    "client thread failed to display text: " + e);
        }
        if (!shown) {
            return HttpApi.Response.error(503, "not_ingame", "no client player (game is not ready)");
        }

        JsonObject out = Json.obj();
        out.addProperty("ok", true);
        out.addProperty("chars", prepared.text().length());
        out.addProperty("truncated", prepared.truncated());
        return HttpApi.Response.json(out);
    }

    /**
     * 按 SPEC §4.7 截断：长度超过 {@value #MAX_TEXT_CHARS} 时截断并补 {@value #TRUNCATION_SUFFIX}。
     *
     * <p>公开静态是为了在 headless 冒烟里对截断行为直接取证（零 MC、零 HTTP）；生产路径只有
     * {@link #handle(HttpApi.Request)} 一处调用。</p>
     */
    public static Prepared prepare(String text) {
        if (text == null || text.length() <= MAX_TEXT_CHARS) {
            return new Prepared(text == null ? "" : text, false);
        }
        int cut = MAX_TEXT_CHARS;
        // 别把代理对切成两半（charAt(cut-1) 是高位代理说明 cut 落在配对中间）
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return new Prepared(text.substring(0, cut) + TRUNCATION_SUFFIX, true);
    }

    /** 截断结果：最终要显示的文本 + 是否发生过截断（响应里回 {@code truncated}）。 */
    public record Prepared(String text, boolean truncated) {
    }
}
