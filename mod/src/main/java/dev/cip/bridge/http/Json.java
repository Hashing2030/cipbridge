package dev.cip.bridge.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Gson 的薄封装（SPEC §2.2：JSON 一律用 Minecraft 自带的 Gson，不引入第三方 JSON 库）。
 *
 * <p>本类<b>不得</b> import 任何 Minecraft 类：{@code dev.cip.bridge.dev.HttpApiSmoke} 要在
 * 纯 JDK 进程里启动 HTTP 服务做自测。</p>
 *
 * <p>关键设置：{@code disableHtmlEscaping()}——默认 Gson 会把 {@code < > & = '} 转成
 * {@code \u003c} 之类的转义，AI 侧读 JSON 时会看到不必要的一团糟，故关闭。</p>
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private Json() {
    }

    /** 共享的 Gson 实例（只读，线程安全）。 */
    public static Gson gson() {
        return GSON;
    }

    /** 新建空 JSON 对象。 */
    public static JsonObject obj() {
        return new JsonObject();
    }

    /** 新建空 JSON 数组。 */
    public static JsonArray arr() {
        return new JsonArray();
    }

    /** 对象 → JSON 字符串。 */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /** JSON 元素 → 字符串。 */
    public static String toJson(JsonElement element) {
        return GSON.toJson(element);
    }

    /** 解析任意 JSON 文本为元素。 */
    public static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    /** 解析 JSON 对象；文本不是对象时抛 {@link com.google.gson.JsonSyntaxException}。 */
    public static JsonObject parseObject(String json) {
        return parse(json).getAsJsonObject();
    }

    /** 反序列化为指定类型。 */
    public static <T> T fromJson(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
