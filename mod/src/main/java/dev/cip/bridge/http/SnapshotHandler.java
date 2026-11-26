package dev.cip.bridge.http;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

import dev.cip.bridge.exec.ThreadBridge;
import dev.cip.bridge.world.ScanChannel;
import dev.cip.bridge.world.Scanner;
import dev.cip.bridge.world.SnapshotCache;
import dev.cip.bridge.world.SnapshotCodec;

/**
 * {@code GET /snapshot}：区域方块快照（SPEC v1.1 §4.3 / §6 / CB-05 / CB-07）。
 *
 * <p><b>线程铁律（SPEC §2.4 / §6）</b>：本类在 {@code cipbridge-http-*} 线程上运行，
 * <b>不 import 任何 net.minecraft 类型</b>，也不直接读 level——参数校验在这里做完，随后
 * ① {@code ThreadBridge.onClient(Scanner::probe)} 取玩家简要信息（纯值 record），
 * ② {@code ThreadBridge.onServer(...)} 在<b>服务端线程同步扫描</b>（SPEC §6：不分片、不打 chunk ticket），
 * 并在<b>同一个 supplier 内</b>跑 {@link SnapshotCache} 的基线对比（缓存只允许服务端线程访问），
 * ③ 用零 MC 的 {@link SnapshotCodec} 打包。因此"HTTP 线程零 MC 访问"在编译期可一眼审阅：
 * {@link #handle(HttpApi.Request)} 里没有任何 {@code net/minecraft} 引用。</p>
 *
 * <p>参数语义（SPEC §4.3 + 卡）：</p>
 * <ul>
 *   <li>{@code r}：默认 {@value #DEFAULT_RADIUS}、上限 {@code min(radius_max, 128)}
 *       （CB-06 接线，128 是 SPEC §6 硬顶）、水平取整到 16 的倍数，
 *       {@code nx = nz = 2*roundUp(r,16)}；窗口以玩家方块坐标为中心取整，但 {@code origin}
 *       由扫描器向下对齐到 16 网格（CB-12），因此玩家不一定正好落在几何中心；</li>
 *   <li>{@code y0}/{@code y1}：默认玩家 y ± {@value #DEFAULT_Y_SPAN}，钳制到
 *       {@value #MIN_Y}..{@value #MAX_Y}；</li>
 *   <li>{@code channels}：默认 {@code blocks,height}；CB-07 起
 *       {@code blocks,height,flags,light,biome,entities} 全部实现，未知名字 → 400；
 *       集合直接交给 codec 裁剪 ZIP entry；</li>
 *   <li>{@code delta}：{@code 1}/{@code true} 开、{@code 0}/{@code false}/缺省关，其它值 → 400；
 *       只在请求了 {@code blocks} 时生效（delta 只覆盖 blocks 通道，卡 §7.5）。</li>
 * </ul>
 *
 * <p>校验顺序是刻意的：{@code delta} → {@code channels} → {@code r/y0/y1} → 探针 → 扫描。
 * 参数错误<b>先于</b>游戏状态检查返回，所以无游戏时 {@code ?channels=bogus} 得到 400，
 * 而合法的 {@code ?channels=light}/{@code ?delta=1} 才走到 503 {@code not_ingame}。</p>
 *
 * <p>失败语义：客户端未就绪/未进世界、或多人游戏没有单机服务器 → <b>503</b> {@code not_ingame}；
 * 游戏线程 10 s 内没扫完 → <b>504</b> {@code timeout}。</p>
 */
public final class SnapshotHandler implements HttpApi.Handler {

    /** {@code r} 默认值（SPEC §4.3）。 */
    public static final int DEFAULT_RADIUS = 32;

    /** {@code r} 上限（SPEC §4.3 / §6 硬上限 128）。 */
    public static final int MAX_RADIUS = 128;

    /** 水平半径对齐粒度：{@code nx=nz=2*roundUp(r,16)}（SPEC §4.3）。 */
    public static final int RADIUS_ALIGN = 16;

    /** {@code y0}/{@code y1} 缺省时的 y ± 跨度（SPEC §4.3）。 */
    public static final int DEFAULT_Y_SPAN = 32;

    /** y 下界（SPEC §4.3 钳制 -64..320）。 */
    public static final int MIN_Y = -64;

    /** y 上界（SPEC §4.3 钳制 -64..320）。 */
    public static final int MAX_Y = 320;

    /** 等玩家简要信息的超时：正常微秒级，5 s 只用于把"卡死"变成 504。 */
    private static final long PROBE_TIMEOUT_MS = 5_000L;

    /** 等扫描结果的超时（SPEC §6：10 s）。 */
    private static final long SCAN_TIMEOUT_MS = 10_000L;

    /** 等实体列表的超时：AABB 遍历也在服务端线程，正常远快于 10 s。 */
    private static final long ENTITY_TIMEOUT_MS = 5_000L;

    /** 响应内容类型（SPEC §4.3：application/zip）。 */
    public static final String CONTENT_TYPE_ZIP = "application/zip";

    /** {@code entities} 通道名（CB-06）。 */
    public static final String CHANNEL_ENTITIES = "entities";

    /** SPEC §4.3 定义的全部通道（有序，用于稳定错误文案）。 */
    public static final List<String> KNOWN_CHANNEL_NAMES = List.of(
            "blocks", "height", "light", "flags", "biome", CHANNEL_ENTITIES);

    /** SPEC §4.3 定义的全部通道（用于区分"未知名字"400）。 */
    public static final Set<String> KNOWN_CHANNELS = Set.copyOf(KNOWN_CHANNEL_NAMES);

    /** 配置里的半径上限（每次请求实时读；CB-06 接线，配置只能收紧 128 硬顶）。 */
    private final IntSupplier radiusMax;

    /**
     * @param radiusMax 运行期读 {@code Config.RADIUS_MAX}（与 CB-04 的 {@code allow_mutations} 同法，
     *                  让 http 包不依赖 {@code Config}，headless 冒烟可传 {@code () -> 128}）。
     *                  有效上限 = {@code min(radiusMax, 128)}：128 是 SPEC §6 硬顶。
     */
    public SnapshotHandler(IntSupplier radiusMax) {
        this.radiusMax = Objects.requireNonNull(radiusMax, "radiusMax");
    }

    @Override
    public HttpApi.Response handle(HttpApi.Request request) {
        // ↓ 本方法运行在 HTTP 线程：只有纯参数校验与 ThreadBridge 投递，无任何 MC 引用。

        // 1) delta：0/1（CB-07；真正差分在服务端线程的 supplier 内做）
        boolean wantDelta = parseDelta(request.query("delta"));

        // 2) channels：解析成集合；空集合 = SPEC 默认 blocks,height（CB-07 用它裁剪 entry）
        Set<String> requestedChannels = validateChannels(request.query("channels"));
        Set<String> channels = requestedChannels.isEmpty()
                ? SnapshotCodec.DEFAULT_CHANNELS
                : requestedChannels;
        boolean wantsEntities = channels.contains(CHANNEL_ENTITIES);

        // 3) 半径：默认 32；上限 = min(Config.RADIUS_MAX, 128)（CB-06 接线）；水平对齐 16
        int radius = request.queryInt("r", DEFAULT_RADIUS);
        if (radius < 1) {
            throw new HttpApi.ApiException(400, "bad_request", "'r' must be >= 1: " + radius);
        }
        int halfExtent = alignUp(Math.min(radius, effectiveMaxRadius()), RADIUS_ALIGN);

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

        // 6) 扫描 + delta（服务端线程；唯一的 level 访问 + 唯一的 SnapshotCache 访问）
        Scanner.Params params = new Scanner.Params(halfExtent, y0, y1, scanChannels(channels));
        boolean wantBlocksDelta = wantDelta && channels.contains(SnapshotCodec.CHANNEL_BLOCKS);
        SnapshotCache.Captured captured;
        try {
            CompletableFuture<SnapshotCache.Captured> pending = ThreadBridge.onServer(
                    () -> SnapshotCache.capture(Scanner.scan(probe, params), wantBlocksDelta));
            captured = ThreadBridge.await(pending, SCAN_TIMEOUT_MS);
        } catch (ThreadBridge.BridgeException e) {
            return bridgeFailure(e);
        }
        if (captured == null) {
            return notIngame("snapshot requires a singleplayer world (no integrated server)");
        }
        Scanner.ScanResult result = captured.scan();

        // 6.5) entities 通道（CB-06）：另一次投递，仍在服务端线程；结果同样是纯值
        Scanner.EntitySnapshot entities = Scanner.EntitySnapshot.EMPTY;
        if (wantsEntities) {
            int entityRadius = Math.min(radius, Scanner.MAX_ENTITY_RADIUS);
            try {
                entities = ThreadBridge.await(
                        ThreadBridge.onServer(() -> Scanner.collectEntities(probe, entityRadius)),
                        ENTITY_TIMEOUT_MS);
            } catch (ThreadBridge.BridgeException e) {
                return bridgeFailure(e);
            }
            if (entities == null) {
                return notIngame("entities require a singleplayer world (no integrated server)");
            }
        }

        // 7) 打包（零 MC）
        byte[] zip = SnapshotCodec.encode(result, entities, channels, captured.delta());
        return HttpApi.Response.bytes(zip, CONTENT_TYPE_ZIP);
    }

    /**
     * 解析并校验 {@code channels}，返回请求的通道集合（CB-07：所有通道均已实现，只区分未知名字）。
     *
     * <p>缺省/空串 → 空集合，语义等同默认 {@code blocks,height}（由调用方归一化）。</p>
     */
    private static Set<String> validateChannels(String raw) {
        Set<String> requested = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String name = part.trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty()) {
                    continue;
                }
                if (!KNOWN_CHANNELS.contains(name)) {
                    throw new HttpApi.ApiException(400, "bad_request",
                            "unknown channel '" + name + "' (known: " + String.join(",", KNOWN_CHANNEL_NAMES) + ")");
                }
                requested.add(name);
            }
        }
        return Set.copyOf(requested);
    }

    /** 通道名 → 扫描器可采集的可选通道（纯枚举，HTTP 线程可安全构造）。 */
    private static Set<ScanChannel> scanChannels(Set<String> channels) {
        EnumSet<ScanChannel> wanted = EnumSet.noneOf(ScanChannel.class);
        if (channels.contains(SnapshotCodec.CHANNEL_FLAGS)) {
            wanted.add(ScanChannel.FLAGS);
        }
        if (channels.contains(SnapshotCodec.CHANNEL_LIGHT)) {
            wanted.add(ScanChannel.LIGHT);
        }
        if (channels.contains(SnapshotCodec.CHANNEL_BIOME)) {
            wanted.add(ScanChannel.BIOME);
        }
        return wanted;
    }

    /** {@code delta}：缺省/0/false = 关；1/true = 开；其它 → 400（SPEC §4.3 只定义了 delta=1）。 */
    private static boolean parseDelta(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("0".equals(value) || "false".equals(value)) {
            return false;
        }
        if ("1".equals(value) || "true".equals(value)) {
            return true;
        }
        throw new HttpApi.ApiException(400, "bad_request", "'delta' must be 0 or 1: " + raw.trim());
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

    private static int alignUp(int value, int align) {
        return ((value + align - 1) / align) * align;
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
