package dev.cip.bridge.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

import dev.cip.bridge.exec.ThreadBridge;
import dev.cip.bridge.world.Scanner;

/**
 * {@code GET /find}：按方块注册名定向查询坐标（SPEC v1.1 §4.4 / CB-06）。
 *
 * <p><b>线程铁律（SPEC §2.4 / §6）</b>：本类在 {@code cipbridge-http-*} 线程上运行，
 * <b>不 import 任何 net.minecraft 类型</b>，也不直接读 level——参数校验全部在这里做完，
 * 随后 ① {@code ThreadBridge.onClient(Scanner::probe)} 取玩家纯值信息，
 * ② {@code ThreadBridge.onServer(() -> Scanner.find(block, probe, params, limit))} 在
 * <b>服务端线程</b>遍历区块。因此"HTTP 线程零 MC 访问"在字节码层可审阅：
 * {@link #handle(HttpApi.Request)} 里没有任何 {@code net/minecraft} 引用。</p>
 *
 * <p>参数（卡 + SPEC §4.4）：</p>
 * <ul>
 *   <li>{@code block}：<b>必填</b>，方块注册名（可省 {@code minecraft:}，大小写不敏感）；缺失 → 400，
 *       非法/未注册 → 400 {@code unknown_block}（注册表查询在服务端线程做）；</li>
 *   <li>{@code r}：默认 {@value #DEFAULT_RADIUS}，上限 {@code min(Config.RADIUS_MAX, 128)}
 *       （CB-06 接线，128 硬顶）；<b>不</b>对齐到 16（/find 回的是精确半径内的命中）；</li>
 *   <li>{@code y0}/{@code y1}：默认玩家 y ± {@value #DEFAULT_Y_SPAN}，钳制到
 *       {@value #MIN_Y}..{@value #MAX_Y}；</li>
 *   <li>{@code limit}：默认 {@value #DEFAULT_LIMIT}，上限 {@value #MAX_LIMIT}，凑满即早退。</li>
 * </ul>
 *
 * <p>失败语义：无游戏/多人无单机服务器 → 503 {@code not_ingame}；服务端线程 10 s 未返回 →
 * 504 {@code timeout}；方块名非法/未注册 → 400 {@code unknown_block}。</p>
 */
public final class FindHandler implements HttpApi.Handler {

    /** {@code r} 默认值（CB-06 卡）。 */
    public static final int DEFAULT_RADIUS = 32;

    /** {@code r} 的 SPEC §6 硬上限；有效上限还要再与 {@code Config.RADIUS_MAX} 取小。 */
    public static final int MAX_RADIUS = 128;

    /** {@code y0}/{@code y1} 缺省时的 y ± 跨度（CB-06 卡：默认玩家 y±64）。 */
    public static final int DEFAULT_Y_SPAN = 64;

    /** y 下界（与 SPEC §4.3 钳制一致）。 */
    public static final int MIN_Y = -64;

    /** y 上界（与 SPEC §4.3 钳制一致）。 */
    public static final int MAX_Y = 320;

    /** {@code limit} 默认值（CB-06 卡）。 */
    public static final int DEFAULT_LIMIT = 32;

    /** {@code limit} 上限（CB-06 卡）。 */
    public static final int MAX_LIMIT = 256;

    /** 等玩家简要信息的超时：正常微秒级，5 s 只用于把"卡死"变成 504。 */
    private static final long PROBE_TIMEOUT_MS = 5_000L;

    /** 等查找结果的超时（与 /snapshot 的扫描同一量级，SPEC §6：10 s）。 */
    private static final long FIND_TIMEOUT_MS = 10_000L;

    /** 配置里的半径上限（每次请求实时读；CB-06 接线，配置只能收紧 128 硬顶）。 */
    private final IntSupplier radiusMax;

    /**
     * @param radiusMax 运行期读 {@code Config.RADIUS_MAX}（与 /snapshot、/cmd 同法：http 包不依赖
     *                  {@code Config}，headless 冒烟可传 {@code () -> 128}）
     */
    public FindHandler(IntSupplier radiusMax) {
        this.radiusMax = Objects.requireNonNull(radiusMax, "radiusMax");
    }

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        // ↓ 运行在 HTTP 线程：只有纯参数校验 + ThreadBridge 投递，无任何 MC 引用。

        // 1) block 必填（验收 2：缺 block → 400，先于任何游戏状态检查）
        String rawBlock = request.query("block");
        if (rawBlock == null || rawBlock.isBlank()) {
            return HttpApi.Response.error(400, "bad_request", "missing required query parameter 'block'");
        }
        String blockKey = Scanner.normalizeBlockKey(rawBlock);

        // 2) 半径：默认 32；上限 = min(Config.RADIUS_MAX, 128)
        int radius = request.queryInt("r", DEFAULT_RADIUS);
        if (radius < 1) {
            throw new HttpApi.ApiException(400, "bad_request", "'r' must be >= 1: " + radius);
        }
        int effectiveRadius = Math.min(radius, effectiveMaxRadius());

        // 3) limit：默认 32、上限 256
        int requestedLimit = request.queryInt("limit", DEFAULT_LIMIT);
        if (requestedLimit < 1) {
            throw new HttpApi.ApiException(400, "bad_request", "'limit' must be >= 1: " + requestedLimit);
        }
        int limit = Math.min(requestedLimit, MAX_LIMIT);

        // 4) y 范围：显式值先钳制；缺省值要等探针拿到玩家 y 之后才算
        Integer explicitY0 = optionalInt(request, "y0");
        Integer explicitY1 = optionalInt(request, "y1");

        // 5) 玩家简要信息（客户端线程；纯值）
        Scanner.PlayerProbe probe;
        try {
            probe = ThreadBridge.await(ThreadBridge.onClient(Scanner::probe), PROBE_TIMEOUT_MS);
        } catch (ThreadBridge.BridgeException e) {
            return bridgeFailure(e);
        }
        if (probe == null) {
            return notIngame("no minecraft");
        }

        int centerY = (int) Math.floor(probe.y());
        int y0 = explicitY0 != null ? clampY(explicitY0) : clampY(centerY - DEFAULT_Y_SPAN);
        int y1 = explicitY1 != null ? clampY(explicitY1) : clampY(centerY + DEFAULT_Y_SPAN);
        if (y0 > y1) {
            throw new HttpApi.ApiException(400, "bad_request",
                    "'y0' must be <= 'y1' after clamping to " + MIN_Y + ".." + MAX_Y
                            + ": y0=" + y0 + ", y1=" + y1);
        }

        // 6) 查找（服务端线程；唯一的 level 访问）
        Scanner.Params params = new Scanner.Params(effectiveRadius, y0, y1);
        Scanner.FindResult result;
        try {
            CompletableFuture<Scanner.FindResult> pending =
                    ThreadBridge.onServer(() -> Scanner.find(blockKey, probe, params, limit));
            result = ThreadBridge.await(pending, FIND_TIMEOUT_MS);
        } catch (Scanner.UnknownBlockException e) {
            return HttpApi.Response.error(400, "unknown_block", e.getMessage());
        } catch (ThreadBridge.BridgeException e) {
            return bridgeFailure(e);
        }
        if (result == null) {
            return notIngame("find requires a singleplayer world (no integrated server)");
        }

        // 7) 组装响应（零 MC）
        JsonObject json = Json.obj();
        json.addProperty("ok", true);
        json.addProperty("block", blockKey);
        json.addProperty("r", effectiveRadius);
        JsonArray found = Json.arr();
        for (int[] pos : result.found()) {
            JsonArray point = Json.arr();
            point.add(pos[0]);
            point.add(pos[1]);
            point.add(pos[2]);
            found.add(point);
        }
        json.add("found", found);
        json.addProperty("visited_chunks", result.visitedChunks());
        json.addProperty("ms", result.ms());
        json.addProperty("truncated", result.truncated());
        return HttpApi.Response.json(json);
    }

    /** 有效半径上限：{@code min(Config.RADIUS_MAX, 128)}（CB-06 接线；配置只能收紧硬顶）。 */
    private int effectiveMaxRadius() {
        return Math.max(1, Math.min(radiusMax.getAsInt(), MAX_RADIUS));
    }

    /** 缺省/空串当"未提供"；非法整数仍由 {@link HttpApi.Request#queryInt} 抛 400。 */
    private static Integer optionalInt(HttpApi.Request request, String name) {
        String raw = request.query(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return request.queryInt(name, 0);
    }

    private static int clampY(int y) {
        return Math.max(MIN_Y, Math.min(MAX_Y, y));
    }

    private static HttpApi.Response bridgeFailure(ThreadBridge.BridgeException e) {
        if (e.isTimeout()) {
            return HttpApi.Response.error(504, "timeout",
                    "timed out waiting for the game thread: " + e.getMessage());
        }
        // Minecraft 未就绪 / 任务被取消：对调用方都是"现在拿不到游戏状态"
        return notIngame("no minecraft");
    }

    private static HttpApi.Response notIngame(String message) {
        return HttpApi.Response.error(503, "not_ingame", message);
    }
}
