package dev.cip.bridge.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 区域扫描器（SPEC v1.1 §4.3 / §6 / CB-05）：把玩家周围的一块长方体读成调色板下标。
 *
 * <p><b>线程铁律（SPEC §2.4 / §6）</b>：本类是<b>唯一</b>读 level 的类，且入口固定，各自
 * 只允许在对应的线程上执行：</p>
 * <ul>
 *   <li>{@link #probe()} —— 只读客户端自身的玩家状态（{@code LocalPlayer}），经
 *       {@code ThreadBridge.onClient} 在<b>客户端线程</b>执行；</li>
 *   <li>{@link #scan(PlayerProbe, Params)} —— 读 {@code ServerLevel}／区块数据，经
 *       {@code ThreadBridge.onServer} 在<b>服务端线程</b>执行（SPEC §6：同步执行、不分片、
 *       不打 chunk ticket）；</li>
 *   <li>{@link #find(String, PlayerProbe, Params, int)}（CB-06 {@code /find}）与
 *       {@link #collectEntities(PlayerProbe, int)}（CB-06 {@code entities} 通道）—— 同样读
 *       {@code ServerLevel}，只允许在 {@code ThreadBridge.onServer} 的 supplier 内执行。</li>
 * </ul>
 * <p>{@code http/SnapshotHandler}（HTTP 线程）只持有 {@link PlayerProbe}/{@link Params}/{@link ScanResult}
 * 这些纯值对象，因此"HTTP 线程零 MC 访问"可在字节码层审阅。</p>
 *
 * <p>坐标约定（SPEC §4.3）：{@code origin = [minX, y0, minZ]}，三个分量都<b>向下对齐到
 * {@value #ORIGIN_ALIGN} 的网格</b>（CB-12 修复，见 {@link #originX(int, int)} /
 * {@link #originY(int, int)}），所以玩家不一定正好在窗口中心，但同一区块内的小幅移动
 * 不改变 origin —— 这是 {@link SnapshotCache} 的 delta 能命中的前提；
 * {@code size = [nx, ny, nz]}；下标 {@code (yi*nz + zi)*nx + xi}（<b>x 最快</b>）。</p>
 */
public final class Scanner {

    private static final Logger LOG = LoggerFactory.getLogger("cipbridge");

    /** 某列没有任何非空气方块时 {@code height.bin} 的值（SPEC §4.3）。 */
    public static final int NO_HEIGHT = -32768;

    /** 记录 {@code unloaded} 时最多打印多少条 warning（防大半径下刷屏）。 */
    private static final int MAX_UNLOADED_LOGS = 8;

    /**
     * 实体通道的半径硬上限（CB-06 卡：{@code min(r, 48)}）。
     *
     * <p>与方块通道的 128 分开：实体 AABB 遍历还要过滤/排序，48 足够覆盖"附近有什么"的
     * 使用场景，同时把服务端线程的单次开销压住。</p>
     */
    public static final int MAX_ENTITY_RADIUS = 48;

    /** 实体通道最多返回多少条（CB-06 卡：超出置 {@code truncated=true}）。 */
    public static final int MAX_ENTITIES = 64;

    private Scanner() {
    }

    // ---- origin 网格对齐（CB-12 修复） -------------------------------------------------
    // 这三个函数是<b>纯算术</b>（无 MC 类型），因此可以脱离游戏做 headless 断言。

    /** origin 对齐粒度：与区块边长一致（16），使同一区块内的移动不改变 origin。 */
    public static final int ORIGIN_ALIGN = 16;

    /** origin y 下界（SPEC §4.3 钳制 -64..320，与 {@code SnapshotHandler.MIN_Y} 同值）。 */
    public static final int ORIGIN_MIN_Y = -64;

    /** origin y 上界（SPEC §4.3 钳制 -64..320，与 {@code SnapshotHandler.MAX_Y} 同值）。 */
    public static final int ORIGIN_MAX_Y = 320;

    /**
     * 水平 origin 分量：{@code floorDiv(centerBlock - n/2, 16) * 16}（CB-12 修复）。
     *
     * <p>对齐前 {@code origin = 玩家方块坐标 - n/2}，玩家每水平走一格 origin 就漂一格，
     * {@link SnapshotCache} 的 key（dim+origin+size+palette）永远不命中。对齐到 16 网格后，
     * 同一区块内的移动不改变 origin；跨区块才按区块跳变。</p>
     *
     * @param centerBlock 玩家方块坐标分量
     * @param n           该轴窗口长度（{@code nx} / {@code nz}，恒为偶数）
     */
    public static int originX(int centerBlock, int n) {
        return Math.floorDiv(centerBlock - n / 2, ORIGIN_ALIGN) * ORIGIN_ALIGN;
    }

    /** 与 {@link #originX(int, int)} 同式（z 轴另立一个名字，便于阅读与断言）。 */
    public static int originZ(int centerBlock, int n) {
        return originX(centerBlock, n);
    }

    /**
     * y origin 分量：先向下对齐 16 网格，再钳制使 {@code [y0, y0+ny-1]} 落在
     * {@value #ORIGIN_MIN_Y}..{@value #ORIGIN_MAX_Y} 内（CB-12 修复）。
     *
     * <p>入参 {@code y0} 来自 {@code SnapshotHandler}，已经钳制过；对齐只会让它更小，所以上界天然
     * 不会越界，下面的钳制是纯防御（{@code ny > 385} 这种超出 SPEC 跨度的输入才会触发）。</p>
     */
    public static int originY(int y0, int ny) {
        int aligned = Math.floorDiv(y0, ORIGIN_ALIGN) * ORIGIN_ALIGN;
        if (aligned < ORIGIN_MIN_Y) {
            aligned = ORIGIN_MIN_Y;
        }
        int maxStart = ORIGIN_MAX_Y - ny + 1;
        if (aligned > maxStart) {
            aligned = Math.max(ORIGIN_MIN_Y, maxStart);
        }
        return aligned;
    }

    /**
     * 客户端线程可读的玩家简要信息。
     *
     * <p>故意只含基本类型 + {@link UUID} + {@link String}：它是从客户端线程传给服务端线程的
     * "纯值快照"，也是 HTTP 线程能安全持有的对象。</p>
     *
     * @param uuid     玩家 UUID（服务端线程用它在 {@code PlayerList} 里找回 ServerPlayer，从而确定维度）
     * @param x        精确 x
     * @param y        精确 y
     * @param z        精确 z
     * @param yaw      朝向（度）
     * @param hp       当前血量
     * @param gameMode 游戏模式名（creative/survival/…；未知为 "unknown"）
     * @param dim      维度 id（如 {@code minecraft:overworld}）
     */
    public record PlayerProbe(UUID uuid, double x, double y, double z, float yaw, float hp,
                              String gameMode, String dim) {
    }

    /**
     * 已归一化的扫描参数（HTTP 线程用纯算术算出，不含任何 MC 类型）。
     *
     * @param halfExtent 水平半径（方块）。{@code /snapshot} 传 16 的倍数（SPEC §4.3 对齐，
     *                   {@code nx = nz = 2*halfExtent}）；{@code /find} 传精确 r、不做对齐
     *                   （CB-06 卡：回的是 r 半径内命中，不能因对齐多扫一圈）
     * @param y0         起始 y（含），已钳制到 -64..320
     * @param y1         结束 y（含），已钳制到 -64..320，且保证 {@code y0 <= y1}
     * @param channels   额外请求的可选通道（CB-07）；空集合 = 只要 blocks/height。
     *                   {@link ScanChannel} 是纯枚举，HTTP 线程可安全持有
     */
    public record Params(int halfExtent, int y0, int y1, Set<ScanChannel> channels) {

        /** 默认参数（无额外通道），供 {@code /find} 等只关心方块几何的调用方使用。 */
        public Params(int halfExtent, int y0, int y1) {
            this(halfExtent, y0, y1, Set.of());
        }
    }

    /**
     * 一次扫描的结果（SPEC §4.3 的 blocks/height/palette/counts/unloaded/几何）。
     *
     * <p>除卡里点名的字段外，这里还带了 {@code tick/world/dim/player/ms}：这些值<b>只能</b>在服务端线程
     * （tick/world/dim）或客户端线程（player）读到，放进结果对象可以让 {@code SnapshotCodec} 保持
     * 零 Minecraft import，也让调用点只有一次投递。</p>
     *
     * @param blocks    调色板下标（<b>uint16</b> 容器，用 {@code & 0xFFFF} 解释），长度
     *                  {@code nx*ny*nz}，下标 {@code (yi*nz+zi)*nx+xi}
     * @param height    每列最高非空气方块的 y，长度 {@code nx*nz}（下标 {@code zi*nx+xi}）；无则 {@value #NO_HEIGHT}
     * @param palette   注册名表，[0] 恒为 {@code minecraft:air}
     * @param counts    按调色板下标的方块计数（长度 = palette.length；[0] 为空气数，header 不输出）
     * @param unloaded  加载失败的区块坐标对 {@code [chunkX, chunkZ]}（对应位置填 0，不可信）
     * @param nx        x 方向长度
     * @param ny        y 方向长度
     * @param nz        z 方向长度
     * @param origin    {@code [minX, y0, minZ]}
     * @param tick      扫描时的 {@code level.getGameTime()}
     * @param world     存档名
     * @param dim       维度 id
     * @param player    玩家简要信息
     * @param ms        扫描耗时（毫秒）
     * @param channels  可选通道数据（CB-07；未请求的通道数组为 {@code null}，见 {@link OptionalChannels}）
     */
    public record ScanResult(short[] blocks, short[] height, String[] palette, int[] counts, int[][] unloaded,
                             int nx, int ny, int nz, int[] origin, long tick, String world, String dim,
                             PlayerProbe player, long ms, OptionalChannels channels) {

        /** 兼容构造器：不请求任何可选通道（等价 CB-06 之前的行为）。 */
        public ScanResult(short[] blocks, short[] height, String[] palette, int[] counts, int[][] unloaded,
                          int nx, int ny, int nz, int[] origin, long tick, String world, String dim,
                          PlayerProbe player, long ms) {
            this(blocks, height, palette, counts, unloaded, nx, ny, nz, origin, tick, world, dim, player, ms,
                    OptionalChannels.NONE);
        }
    }

    /**
     * 可选通道的原始数据（CB-07）：数组为 {@code null} 表示该通道<b>未被请求</b>，不参与 ZIP。
     *
     * <p>全部是纯数组/字符串，服务端线程填充后由 HTTP 线程交给零 MC 的
     * {@code SnapshotCodec} 打包，因此本 record 也是线程安全的"值快照"。</p>
     *
     * @param flags        长度 {@code nx*ny*nz}，每格 8 位方块属性（LSB 起，顺序同 blocks）
     * @param light        长度 {@code nx*ny*nz}，每格 {@code LightLayer.BLOCK} 光照 0..15
     * @param biomeIndex   长度 {@code ceil(nx/4)*ceil(nz/4)}（下标 {@code bj*bw+bi}），值为
     *                     {@code biomePalette} 下标
     * @param biomePalette 生物群系注册名表（按首次遇到顺序）
     */
    public record OptionalChannels(byte[] flags, byte[] light, byte[] biomeIndex, String[] biomePalette) {

        /** 未请求任何可选通道。 */
        public static final OptionalChannels NONE = new OptionalChannels(null, null, null, null);
    }

    /**
     * 一条实体摘要（CB-06 {@code entities} 通道，纯值，HTTP 线程可安全持有）。
     *
     * @param id        实体类型注册名（如 {@code minecraft:zombie}）
     * @param x         精确 x
     * @param y         精确 y
     * @param z         精确 z
     * @param dist      到玩家的 3D 距离（方块）
     * @param hp        血量；非 {@code LivingEntity}（船/掉落物/经验球等）记 0
     * @param name      自定义名；未命名时为 {@code null}（JSON 里该键省略，见 SnapshotCodec）
     * @param passenger 该实体当前是否骑乘在别的实体上（{@link Entity#isPassenger()}）
     */
    public record EntityInfo(String id, double x, double y, double z, double dist, double hp,
                             String name, boolean passenger) {
    }

    /**
     * {@code entities} 通道的结果（CB-06）：按距离升序，最多 {@value #MAX_ENTITIES} 条。
     *
     * @param entities  实体摘要（按距离升序）
     * @param truncated 是否因超过 {@value #MAX_ENTITIES} 条被截断
     */
    public record EntitySnapshot(List<EntityInfo> entities, boolean truncated) {

        /** 未请求 {@code entities} 通道时的空结果。 */
        public static final EntitySnapshot EMPTY = new EntitySnapshot(List.of(), false);
    }

    /**
     * {@code /find} 的结果（CB-06 / SPEC §4.4）。
     *
     * @param found         命中的方块坐标 {@code [x, y, z]}（遍历顺序，不排序——便于早退）
     * @param visitedChunks 实际成功读取的区块数（加载失败的区块不计）
     * @param truncated     是否因凑满 {@code limit} 提前停止（此时可能还有未上报的命中）
     * @param ms            查找耗时（毫秒）
     */
    public record FindResult(List<int[]> found, int visitedChunks, boolean truncated, long ms) {
    }

    /** 方块注册名非法或未注册：handler 映射成 400（CB-06）。 */
    public static final class UnknownBlockException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnknownBlockException(String message) {
            super(message);
        }
    }

    /**
     * 把用户给的方块名归一化成完整注册名（纯字符串，HTTP 线程可调用）。
     *
     * <p>只做"去空白 + 小写 + 无命名空间前缀补 {@code minecraft:}"；合法性与是否注册由
     * {@link #find} 在服务端线程查注册表判定，避免 HTTP 线程碰任何 MC 类型。</p>
     */
    public static String normalizeBlockKey(String raw) {
        String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.indexOf(':') >= 0) {
            return key;
        }
        return "minecraft:" + key;
    }

    // ───────────────────────────── 客户端线程 ─────────────────────────────

    /**
     * 读玩家简要信息。<b>只允许在客户端线程调用</b>（{@code ThreadBridge.onClient} 的 supplier 内）。
     *
     * @return 玩家信息；客户端未就绪/未进世界返回 {@code null}（由 handler 映射成 503）
     */
    public static PlayerProbe probe() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return null;
        }
        return new PlayerProbe(player.getUUID(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getHealth(), gameMode(minecraft),
                player.level().dimension().location().toString());
    }

    private static String gameMode(Minecraft minecraft) {
        MultiPlayerGameMode manager = minecraft.gameMode;
        if (manager == null) {
            return "unknown";
        }
        GameType type = manager.getPlayerMode();
        return type == null ? "unknown" : type.getName();
    }

    // ───────────────────────────── 服务端线程 ─────────────────────────────

    /**
     * 扫描以玩家为中心、{@code origin} 再向下对齐到 {@value #ORIGIN_ALIGN} 网格的长方体
     * （CB-12：玩家因此最多偏离几何中心 15 格，换取同区块内 origin 稳定）…
     * <b>只允许在服务端线程调用</b>（{@code ThreadBridge.onServer} 的 supplier 内）。
     *
     * <p>区块用 {@code ServerLevel.getChunk(cx, cz)} 自然加载（SPEC §6：不手动打 ticket）；
     * 加载抛异常或拿不到区块的坐标记进 {@link ScanResult#unloaded()}，对应位置填 0。</p>
     *
     * @return 扫描结果；<b>没有单机服务器</b>（多人游戏）或玩家对应的维度取不到时返回 {@code null}
     *         （由 handler 映射成 503，卡要求"多人无单机服时 503"）
     * @throws Palette.OverflowException 非 air 方块种类超过 uint16 上限（纯防御）
     * @throws IllegalStateException     生物群系种类超过 uint8 下标上限（纯防御）
     */
    public static ScanResult scan(PlayerProbe probe, Params params) {
        long beganNanos = System.nanoTime();

        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = resolveLevel(server, probe);
        if (level == null) {
            return null;
        }

        int half = params.halfExtent();
        int nx = half * 2;
        int nz = half * 2;
        int ny = params.y1() - params.y0() + 1;
        BlockPos center = BlockPos.containing(probe.x(), probe.y(), probe.z());
        // CB-12 修复：origin 三个分量都向下对齐到 16 的网格（不再是"玩家坐标 - n/2"）。
        // 玩家在同一区块内小幅移动（水平/垂直几格）时 origin 不变，SnapshotCache 的
        // dim+origin+size+palette key 才能命中；否则 key 每走一格就变，delta 永远退回全量。
        int y0 = originY(params.y0(), ny);
        int x0 = originX(center.getX(), nx);
        int z0 = originZ(center.getZ(), nz);

        int cx0 = x0 >> 4;
        int cx1 = (x0 + nx - 1) >> 4;
        int cz0 = z0 >> 4;
        int cz1 = (z0 + nz - 1) >> 4;
        LevelChunk[][] chunks = new LevelChunk[cz1 - cz0 + 1][cx1 - cx0 + 1];
        List<int[]> unloaded = new ArrayList<>();
        loadChunks(level, chunks, cx0, cx1, cz0, cz1, unloaded);

        Palette palette = new Palette();
        short[] blocks = new short[nx * nz * ny];
        short[] height = new short[nx * nz];
        Arrays.fill(height, (short) NO_HEIGHT);
        int[] counts = new int[Palette.MAX_ENTRIES];

        // CB-07：可选通道按请求分配；未请求 = null（不进 ZIP，也不付采集开销）
        Set<ScanChannel> requested = params.channels();
        boolean wantFlags = requested.contains(ScanChannel.FLAGS);
        boolean wantLight = requested.contains(ScanChannel.LIGHT);
        boolean wantBiome = requested.contains(ScanChannel.BIOME);
        int volume = nx * nz * ny;
        byte[] flags = wantFlags ? new byte[volume] : null;
        byte[] light = wantLight ? new byte[volume] : null;
        // 光照/属性/生物群系逐格要坐标：复用同一个 MutableBlockPos，避免每格分配（百万级格数）
        BlockPos.MutableBlockPos cursor = (wantLight || wantFlags || wantBiome)
                ? new BlockPos.MutableBlockPos()
                : null;
        // 空区块/世界高度之外的格子按 air 处理（只读常量状态，循环里不重复创建）
        BlockState airState = Blocks.AIR.defaultBlockState();

        int minBuildY = level.getMinBuildHeight();
        int maxBuildY = level.getMaxBuildHeight();
        for (int yi = 0; yi < ny; yi++) {
            int y = y0 + yi;
            boolean insideBuildHeight = y >= minBuildY && y < maxBuildY;
            int sectionIndex = insideBuildHeight ? level.getSectionIndex(y) : -1;
            int localY = y & 15;
            for (int zi = 0; zi < nz; zi++) {
                int z = z0 + zi;
                LevelChunk[] chunkRow = chunks[(z >> 4) - cz0];
                int localZ = z & 15;
                // x 最快（SPEC §4.3）：同一 (yi, zi) 的整行 nx 个值连续写
                int rowBase = (yi * nz + zi) * nx;
                int columnBase = zi * nx;
                for (int xi = 0; xi < nx; xi++) {
                    int x = x0 + xi;
                    BlockState state = airState;
                    // known=false：区块未加载/世界高度之外，位置不可信 → blocks 填 0、flags/light 也填 0
                    boolean known = false;
                    if (insideBuildHeight) {
                        LevelChunk chunk = chunkRow[(x >> 4) - cx0];
                        if (chunk != null) {
                            known = true;
                            LevelChunkSection section = chunk.getSection(sectionIndex);
                            if (!section.hasOnlyAir()) {
                                state = section.getStates().get(x & 15, localY, localZ);
                            }
                        }
                    }
                    int index = palette.id(state);
                    blocks[rowBase + xi] = (short) index;
                    if (index != Palette.AIR_INDEX) {
                        counts[index]++;
                        int column = columnBase + xi;
                        if (y > height[column]) {
                            height[column] = (short) y;
                        }
                    }
                    if (flags != null) {
                        // 用非 deprecated 的 (level, pos) 重载（动态发光方块按位置算）；未知位置填 0
                        flags[rowBase + xi] = known
                                ? flagBits(state, level, cursor.set(x, y, z))
                                : 0;
                    }
                    if (light != null) {
                        // 未加载区块不再去查光照（会触发加载/抛异常）；世界高度之外与未知位置都填 0
                        light[rowBase + xi] = known
                                ? (byte) level.getBrightness(LightLayer.BLOCK, cursor.set(x, y, z))
                                : 0;
                    }
                }
            }
        }

        byte[] biomeIndex = null;
        String[] biomePalette = null;
        if (wantBiome) {
            int biomeWidth = (nx + 3) / 4;
            int biomeDepth = (nz + 3) / 4;
            biomeIndex = new byte[biomeWidth * biomeDepth];
            Map<String, Integer> biomeIds = new HashMap<>();
            List<String> biomeNames = new ArrayList<>();
            // 生物群系是 2D 通道：取 y 范围中层采样（3D 生物群系的真实分布留 CB-12 实测）
            int sampleY = y0 + ny / 2;
            for (int bj = 0; bj < biomeDepth; bj++) {
                int z = z0 + bj * 4;
                for (int bi = 0; bi < biomeWidth; bi++) {
                    int x = x0 + bi * 4;
                    String name = biomeName(level, cursor.set(x, sampleY, z));
                    Integer id = biomeIds.get(name);
                    if (id == null) {
                        if (biomeNames.size() >= 256) {
                            throw new IllegalStateException(
                                    "biome palette overflow: more than 256 distinct biomes in one snapshot");
                        }
                        id = biomeNames.size();
                        biomeIds.put(name, id);
                        biomeNames.add(name);
                    }
                    biomeIndex[bj * biomeWidth + bi] = (byte) (int) id;
                }
            }
            biomePalette = biomeNames.toArray(new String[0]);
        }

        String[] paletteNames = palette.toArray();
        long elapsedMs = (System.nanoTime() - beganNanos) / 1_000_000L;
        return new ScanResult(blocks, height, paletteNames,
                Arrays.copyOf(counts, paletteNames.length),
                unloaded.toArray(new int[0][]),
                nx, ny, nz,
                new int[] {x0, y0, z0},
                level.getGameTime(),
                worldName(server),
                level.dimension().location().toString(),
                probe,
                elapsedMs,
                new OptionalChannels(flags, light, biomeIndex, biomePalette));
    }

    /**
     * 定向查找某个方块（CB-06 / SPEC §4.4）。<b>只允许在服务端线程调用</b>。
     *
     * <p>遍历与 {@link #scan} 相同的区块集合（{@code level.getChunk(cx,cz)} 自然加载，SPEC §6
     * 不打 ticket），先用 {@link LevelChunkSection#maybeHas(java.util.function.Predicate)}
     * 按 section 调色板剪枝（不命中整段跳过），再逐格 {@code state.is(target)}；凑满 {@code limit}
     * 立即返回——早退是卡里的性能要求。</p>
     *
     * @param blockKey 方块注册名（可省 {@code minecraft:}，大小写不敏感）
     * @param limit    最多返回多少条（&lt;1 时按 1）
     * @return 查找结果；<b>没有单机服务器</b>或维度取不到时返回 {@code null}（handler 映射 503）
     * @throws UnknownBlockException 名字非法或没注册（handler 映射 400）
     */
    public static FindResult find(String blockKey, PlayerProbe probe, Params params, int limit) {
        long beganNanos = System.nanoTime();

        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = resolveLevel(server, probe);
        if (level == null) {
            return null;
        }
        Block target = resolveBlock(blockKey);
        int maxResults = Math.max(1, limit);

        int radius = Math.max(1, params.halfExtent());
        int y0 = params.y0();
        int y1 = params.y1();
        int nx = radius * 2;
        int nz = radius * 2;
        BlockPos center = BlockPos.containing(probe.x(), probe.y(), probe.z());
        int x0 = center.getX() - radius;
        int z0 = center.getZ() - radius;

        int cx0 = x0 >> 4;
        int cx1 = (x0 + nx - 1) >> 4;
        int cz0 = z0 >> 4;
        int cz1 = (z0 + nz - 1) >> 4;
        LevelChunk[][] chunks = new LevelChunk[cz1 - cz0 + 1][cx1 - cx0 + 1];
        loadChunks(level, chunks, cx0, cx1, cz0, cz1, new ArrayList<>());

        int minBuildY = level.getMinBuildHeight();
        int maxBuildY = level.getMaxBuildHeight();
        List<int[]> found = new ArrayList<>(Math.min(maxResults, 64));
        int visitedChunks = 0;
        boolean truncated = false;

        outer:
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                LevelChunk chunk = chunks[cz - cz0][cx - cx0];
                if (chunk == null) {
                    continue;
                }
                visitedChunks++;
                int fromX = Math.max(x0, cx << 4);
                int toX = Math.min(x0 + nx - 1, (cx << 4) + 15);
                int fromZ = Math.max(z0, cz << 4);
                int toZ = Math.min(z0 + nz - 1, (cz << 4) + 15);
                LevelChunkSection section = null;
                boolean sectionMayContainTarget = false;
                int sectionIndex = -1;
                for (int y = y0; y <= y1; y++) {
                    if (y < minBuildY || y >= maxBuildY) {
                        continue;
                    }
                    int index = level.getSectionIndex(y);
                    if (index != sectionIndex) {
                        sectionIndex = index;
                        section = chunk.getSection(index);
                        // 调色板层面剪枝：整段都没有目标方块就不必逐格比较（无假阴性）
                        sectionMayContainTarget = !section.hasOnlyAir()
                                && section.maybeHas(state -> state.is(target));
                    }
                    if (!sectionMayContainTarget) {
                        continue;
                    }
                    int localY = y & 15;
                    for (int z = fromZ; z <= toZ; z++) {
                        int localZ = z & 15;
                        for (int x = fromX; x <= toX; x++) {
                            if (section.getStates().get(x & 15, localY, localZ).is(target)) {
                                found.add(new int[] {x, y, z});
                                if (found.size() >= maxResults) {
                                    truncated = true;
                                    break outer;
                                }
                            }
                        }
                    }
                }
            }
        }

        long elapsedMs = (System.nanoTime() - beganNanos) / 1_000_000L;
        return new FindResult(found, visitedChunks, truncated, elapsedMs);
    }

    /**
     * 收集玩家附近实体（CB-06 {@code entities} 通道）。<b>只允许在服务端线程调用</b>。
     *
     * <p>用 {@code level.getEntitiesOfClass(Entity.class, AABB, filter)} 一次取回半径
     * {@code min(radius, 48)} 立方体内的存活实体（排除玩家自己），按 3D 距离升序取前
     * {@value #MAX_ENTITIES} 条；超出则 {@code truncated=true}。</p>
     *
     * @param radius 请求半径（方块，会被钳到 {@value #MAX_ENTITY_RADIUS}；&lt;1 按 1）
     * @return 实体列表；<b>没有单机服务器</b>或维度取不到时返回 {@code null}（handler 映射 503）
     */
    public static EntitySnapshot collectEntities(PlayerProbe probe, int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        ServerLevel level = resolveLevel(server, probe);
        if (level == null) {
            return null;
        }

        int r = Math.max(1, Math.min(radius, MAX_ENTITY_RADIUS));
        double px = probe.x();
        double py = probe.y();
        double pz = probe.z();
        AABB area = new AABB(px - r, py - r, pz - r, px + r, py + r, pz + r);
        Entity self = probe.uuid() == null ? null : level.getPlayerByUUID(probe.uuid());
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, area,
                entity -> (self == null || entity != self) && entity.isAlive());

        List<EntityInfo> infos = new ArrayList<>(entities.size());
        for (Entity entity : entities) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            double dx = entity.getX() - px;
            double dy = entity.getY() - py;
            double dz = entity.getZ() - pz;
            Component customName = entity.getCustomName();
            infos.add(new EntityInfo(
                    id == null ? "unknown" : id.toString(),
                    entity.getX(), entity.getY(), entity.getZ(),
                    Math.sqrt(dx * dx + dy * dy + dz * dz),
                    entity instanceof LivingEntity living ? living.getHealth() : 0.0,
                    customName == null ? null : customName.getString(),
                    entity.isPassenger()));
        }
        infos.sort(Comparator.comparingDouble(EntityInfo::dist));
        boolean truncated = infos.size() > MAX_ENTITIES;
        List<EntityInfo> capped = truncated
                ? new ArrayList<>(infos.subList(0, MAX_ENTITIES))
                : infos;
        return new EntitySnapshot(List.copyOf(capped), truncated);
    }

    /** 解析方块注册名；非法/未注册抛 {@link UnknownBlockException}。 */
    private static Block resolveBlock(String blockKey) {
        String normalized = normalizeBlockKey(blockKey);
        ResourceLocation id = ResourceLocation.tryParse(normalized);
        if (id == null) {
            throw new UnknownBlockException(
                    "invalid block id: '" + blockKey + "' (expected e.g. minecraft:diamond_ore)");
        }
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new UnknownBlockException("unknown block '" + id + "' (not registered)");
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    /** 逐区块自然加载；失败/为空的区块坐标记入 {@code unloaded}。 */
    private static void loadChunks(ServerLevel level, LevelChunk[][] chunks,
                                   int cx0, int cx1, int cz0, int cz1, List<int[]> unloaded) {
        int logged = 0;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                try {
                    LevelChunk chunk = level.getChunk(cx, cz);
                    if (chunk == null) {
                        unloaded.add(new int[] {cx, cz});
                        logged = logUnloaded(cx, cz, null, logged);
                    } else {
                        chunks[cz - cz0][cx - cx0] = chunk;
                    }
                } catch (RuntimeException e) {
                    // 区块生成/加载失败不该让整个快照 500：按 SPEC §4.3 记 unloaded，对应位置填 0
                    unloaded.add(new int[] {cx, cz});
                    logged = logUnloaded(cx, cz, e, logged);
                }
            }
        }
    }

    private static int logUnloaded(int cx, int cz, RuntimeException cause, int logged) {
        if (logged >= MAX_UNLOADED_LOGS) {
            return logged;
        }
        if (cause == null) {
            LOG.warn("[cipbridge] scan: chunk [{}, {}] is not available; marked as unloaded", cx, cz);
        } else {
            LOG.warn("[cipbridge] scan: failed to load chunk [{}, {}]; marked as unloaded", cx, cz, cause);
        }
        return logged + 1;
    }

    /** 玩家所在维度：优先用 PlayerList 里的 ServerPlayer（服务端权威），否则按维度 id 反查。 */
    private static ServerLevel resolveLevel(IntegratedServer server, PlayerProbe probe) {
        if (probe.uuid() != null) {
            ServerPlayer serverPlayer = server.getPlayerList().getPlayer(probe.uuid());
            if (serverPlayer != null) {
                return serverPlayer.serverLevel();
            }
        }
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(probe.dim()));
            return server.getLevel(key);
        } catch (RuntimeException e) {
            LOG.warn("[cipbridge] scan: cannot resolve dimension '{}'", probe.dim(), e);
            return null;
        }
    }

    private static String worldName(IntegratedServer server) {
        String name = server.getWorldData().getLevelName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    /**
     * 方块属性位（CB-07 flags 通道）：LSB 起，0x80 保留恒 0。
     *
     * <p>位序与 {@code SnapshotCodec.FLAGS_LEGEND} 严格一一对应；全部用公开 {@link BlockState} API。
     * {@code light_source} 用非 deprecated 的 {@code getLightEmission(BlockGetter, BlockPos)}
     * （等价判据，且能正确处理动态发光方块；卡 §7.2 允许"等价公开 API"）。</p>
     *
     * @param level 仅用于 {@code getLightEmission}（不写 level）
     * @param pos   当前格坐标（调用方复用可变 BlockPos）
     */
    static byte flagBits(BlockState state, BlockGetter level, BlockPos pos) {
        int bits = 0;
        if (state.hasBlockEntity()) {
            bits |= 0x01; // block_entity
        }
        if (!state.getFluidState().isEmpty()) {
            bits |= 0x02; // fluid
        }
        if (state.canOcclude()) {
            bits |= 0x04; // solid
        }
        if (state.canBeReplaced()) {
            bits |= 0x08; // replaceable
        }
        if (state.getLightEmission(level, pos) > 0) {
            bits |= 0x10; // light_source
        }
        if (state.isSignalSource()) {
            bits |= 0x20; // signal_source
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            bits |= 0x40; // climbable
        }
        return (byte) bits; // 0x80 reserved
    }

    /** 生物群系注册名（CB-07 biome 通道）；无注册键（理论上不存在）时给稳定的兜底名。 */
    private static String biomeName(ServerLevel level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        return biome.unwrapKey().map(key -> key.location().toString()).orElse("unknown:unregistered");
    }
}
