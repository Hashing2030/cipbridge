package dev.cip.bridge.http;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;

import dev.cip.bridge.exec.ThreadBridge;

/**
 * {@code GET /ping}：存活探测 + 可选的游戏 tick（CB-03 卡里"给 /ping 加 tick"的可选项）。
 *
 * <p>HTTP 线程约束同 {@link StateHandler}：{@link #handle(HttpApi.Request)} 只碰
 * {@link ThreadBridge}，{@code level.getGameTime()} 的读取在 {@link ThreadBridge#onClient}
 * 的 supplier 内、由客户端线程执行。</p>
 *
 * <p>探活语义优先：tick 只是附加信息——未进世界、客户端未就绪、或 500 ms 内拿不到时
 * 就省略 {@code tick} 字段，/ping 仍返回 200，不会因为"不在游戏里"变成 503/504。</p>
 *
 * <p>为什么不由 HttpApi 直接注册：HttpApi 必须保持无 MC import（可在裸 JDK 进程里自测），
 * 所以带世界读取的 /ping 由 {@code CipBridge} 用本类覆盖注册。</p>
 */
public final class PingHandler implements HttpApi.Handler {

    /** tick 的等待上限：探活要快，拿不到就算了。 */
    private static final long TICK_TIMEOUT_MS = 500L;

    private final HttpApi api;

    public PingHandler(HttpApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        // ↓ HTTP 线程：仅 ThreadBridge 调度 + 纯 JDK/JSON 组装。
        JsonObject json = Json.obj();
        json.addProperty("ok", true);
        json.addProperty("mod", "cipbridge");
        json.addProperty("port", api.port());
        json.addProperty("uptime_ms", api.uptimeMs());
        Long tick = tick();
        if (tick != null) {
            json.addProperty("tick", tick.longValue());
        }
        return HttpApi.Response.json(json);
    }

    /** 读取当前世界 tick；任何失败（未就绪/超时）都返回 null，由调用方省略字段。 */
    private static Long tick() {
        try {
            CompletableFuture<Long> pending = ThreadBridge.onClient(PingHandler::gameTime);
            return ThreadBridge.await(pending, TICK_TIMEOUT_MS);
        } catch (RuntimeException e) {
            // Minecraft 未就绪（BridgeException）或等待超时：tick 是可选字段，探活不能失败。
            return null;
        }
    }

    // 客户端线程：唯一碰 level 的地方。
    private static Long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getGameTime();
    }
}
