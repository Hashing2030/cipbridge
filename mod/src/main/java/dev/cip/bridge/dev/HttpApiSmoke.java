package dev.cip.bridge.dev;

import com.google.gson.JsonObject;

import dev.cip.bridge.http.HttpApi;
import dev.cip.bridge.http.Json;

/**
 * HttpApi 独立自测入口（CB-02 验收 2）：不依赖 Minecraft、不进游戏，纯 JDK 进程起一个 HTTP 服务。
 *
 * <p>用法：</p>
 * <pre>
 * java -cp build/classes/java/main:&lt;gson.jar&gt; dev.cip.bridge.dev.HttpApiSmoke [port] [token] [rateLimit]
 * </pre>
 *
 * <p>说明：{@link HttpApi} 用 MC 自带的 Gson 序列化 JSON；裸 {@code java -cp} 里没有游戏 classpath，
 * 所以要把 gson jar 一起放进 -cp（运行游戏时 gson 由 Minecraft 提供，无需打包）。</p>
 *
 * <p>启动后打印 {@code CIP-BRIDGE-SMOKE listening on ...} 然后阻塞，直到进程被杀。</p>
 */
public final class HttpApiSmoke {

    /** 自测默认端口：避开 SPEC 默认的 8642，避免与本机真实实例冲突。 */
    private static final int DEFAULT_PORT = 18642;

    private static final String DEFAULT_TOKEN = "cip-smoke-token";

    private static final int DEFAULT_RATE_LIMIT = 5;

    private HttpApiSmoke() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        String token = args.length > 1 ? args[1] : DEFAULT_TOKEN;
        int rateLimit = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_RATE_LIMIT;

        HttpApi api = new HttpApi(port, token, rateLimit);
        api.setLogger(line -> System.out.println("[smoke] " + line));

        // 演示处理器注入（CB-03 会以同样方式挂 /state /cmd /snapshot 等）。
        api.route("GET", "/echo", request -> {
            JsonObject json = Json.obj();
            json.addProperty("ok", true);
            json.addProperty("method", request.method());
            json.addProperty("path", request.path());
            JsonObject query = Json.obj();
            request.query().forEach(query::addProperty);
            json.add("query", query);
            return HttpApi.Response.json(json);
        });

        int boundPort = api.start();
        String listening = HttpApi.LOOPBACK_HOST + ":" + boundPort;
        System.out.println("CIP-BRIDGE-SMOKE listening on " + listening
                + " token=" + token + " rate_limit=" + rateLimit);
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(api::stop, "cipbridge-smoke-stop"));
        Thread.currentThread().join();
    }
}
