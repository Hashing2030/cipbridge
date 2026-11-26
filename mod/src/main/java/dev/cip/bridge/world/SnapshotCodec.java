package dev.cip.bridge.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import dev.cip.bridge.http.Json;

/**
 * 快照编码器（SPEC v1.1 §4.3 / CB-05 / CB-07）：{@link Scanner.ScanResult} → ZIP 字节。
 *
 * <p><b>本类零 Minecraft import</b>：只吃 {@link Scanner.ScanResult}（纯值 record）、吐 {@code byte[]}，
 * 压缩用 JDK 的 {@link ZipOutputStream}（底层 {@code java.util.zip.Deflater}，SPEC §2.2 允许），
 * JSON 用 MC 自带的 Gson（{@link Json}）。因此可以脱离游戏单独审阅/单测。</p>
 *
 * <p>ZIP entry（CB-07 起按 {@code channels} 裁剪，只打请求到的通道）：</p>
 * <ul>
 *   <li>{@code header.json} —— 恒在；</li>
 *   <li>{@code blocks.bin}（uint8）或 {@code blocks16.bin}（<b>uint16 小端</b>）—— 请求 {@code blocks}
 *       且非 delta 模式；调色板 {@code size() > 256} 时用 16-bit（卡 §7.1）；</li>
 *   <li>{@code blocks_delta.bin} —— delta 命中时替代 blocks entry（{@code varint(索引差)+值}，卡 §7.5）；</li>
 *   <li>{@code height.bin}（int16 小端，请求 {@code height}）；</li>
 *   <li>{@code flags.bin}/{@code light.bin}（uint8[nx*ny*nz]，请求对应通道）；</li>
 *   <li>{@code biome.bin}（uint8[ceil(nx/4)*ceil(nz/4)]，请求 {@code biome}）。</li>
 * </ul>
 *
 * <p>二进制布局（SPEC §4.3）：{@code blocks} 索引 {@code (yi*nz+zi)*nx+xi}（<b>x 最快</b>）；
 * {@code height} 索引 {@code zi*nx+xi}；{@code flags/light} 与 blocks 同序；
 * {@code biome} 索引 {@code bj*bw+bi}。多字节整数一律<b>小端</b>。</p>
 *
 * <p>{@code header.bytes_per_block} 恒输出（1|2）：delta 模式下没有 blocks entry，
 * 客户端要靠它解析 {@code blocks_delta.bin} 的值宽度。</p>
 */
public final class SnapshotCodec {

    /** ZIP entry：头部 JSON（固定名）。 */
    public static final String ENTRY_HEADER = "header.json";

    /** ZIP entry：方块调色板下标（uint8，固定名）。 */
    public static final String ENTRY_BLOCKS = "blocks.bin";

    /** ZIP entry：方块调色板下标（uint16 小端，CB-07）。 */
    public static final String ENTRY_BLOCKS16 = "blocks16.bin";

    /** ZIP entry：delta 块差异（CB-07）。 */
    public static final String ENTRY_BLOCKS_DELTA = "blocks_delta.bin";

    /** ZIP entry：每列最高非空气方块 y（固定名）。 */
    public static final String ENTRY_HEIGHT = "height.bin";

    /** ZIP entry：每块 8 位属性（CB-07 可选通道）。 */
    public static final String ENTRY_FLAGS = "flags.bin";

    /** ZIP entry：每块方块光照（CB-07 可选通道）。 */
    public static final String ENTRY_LIGHT = "light.bin";

    /** ZIP entry：4×4 列生物群系下标（CB-07 可选通道）。 */
    public static final String ENTRY_BIOME = "biome.bin";

    /** 通道名：方块（SPEC §4.3）。 */
    public static final String CHANNEL_BLOCKS = "blocks";

    /** 通道名：高度（SPEC §4.3）。 */
    public static final String CHANNEL_HEIGHT = "height";

    /** 通道名：属性位（SPEC §4.3）。 */
    public static final String CHANNEL_FLAGS = "flags";

    /** 通道名：光照（SPEC §4.3）。 */
    public static final String CHANNEL_LIGHT = "light";

    /** 通道名：生物群系（SPEC §4.3）。 */
    public static final String CHANNEL_BIOME = "biome";

    /** SPEC §4.3 默认通道（{@code blocks,height}）。 */
    public static final Set<String> DEFAULT_CHANNELS = Set.of(CHANNEL_BLOCKS, CHANNEL_HEIGHT);

    /**
     * {@code header.flags_legend} 固定 8 名（卡 §7.2）：下标 i 对应位 {@code 1 << i}，最后一位保留恒 0。
     */
    public static final List<String> FLAGS_LEGEND = List.of(
            "block_entity", "fluid", "solid", "replaceable",
            "light_source", "signal_source", "climbable", "reserved");

    /** header 的协议版本（SPEC §4.3 {@code v}）。 */
    public static final int VERSION = 1;

    /** 索引顺序标识（SPEC §4.3 {@code order}）。 */
    public static final String ORDER = "x-fastest";

    /** uint8 能表达的 entry 数；不超过它（含 256 个 entry，下标 0..255）用 blocks.bin。 */
    private static final int UINT8_ENTRIES = 256;

    /** ZIP 初始缓冲（比默认 32 字节合理；后面靠 ByteArrayOutputStream 自增长）。 */
    private static final int INITIAL_BUFFER_BYTES = 1 << 16;

    private SnapshotCodec() {
    }

    /**
     * 把一次扫描结果编码成 ZIP（默认通道 {@code blocks,height}，不带 entities，无 delta）。
     *
     * @param scan 扫描结果（不可为 null）
     * @return 完整 ZIP 字节，可直接作为 {@code application/zip} 响应体
     */
    public static byte[] encode(Scanner.ScanResult scan) {
        return encode(scan, Scanner.EntitySnapshot.EMPTY);
    }

    /**
     * 把扫描结果 + {@code entities} 通道编码成 ZIP（CB-06；默认通道 {@code blocks,height}，无 delta）。
     *
     * @param scan     扫描结果（不可为 null）
     * @param entities 实体快照（不可为 null；未请求该通道时传 {@link Scanner.EntitySnapshot#EMPTY}）
     * @return 完整 ZIP 字节
     */
    public static byte[] encode(Scanner.ScanResult scan, Scanner.EntitySnapshot entities) {
        return encode(scan, entities, DEFAULT_CHANNELS, null);
    }

    /**
     * 完整入口（CB-07）：按 {@code channels} 裁剪 entry，可选 delta。
     *
     * @param scan     扫描结果（不可为 null）
     * @param entities 实体快照（不可为 null；未请求该通道时传 {@link Scanner.EntitySnapshot#EMPTY}）
     * @param channels 请求的通道集合（{@code blocks/height/flags/light/biome}；
     *                 {@code entities} 只在 header 里体现，不产生 entry）
     * @param delta    delta 结果；非 null 时输出 {@code blocks_delta.bin} 且<b>不</b>输出
     *                 {@code blocks.bin}/{@code blocks16.bin}；null = 全量
     * @return 完整 ZIP 字节
     * @throws IllegalStateException 请求了 flags/light/biome 但扫描结果里没有对应数据（编码约定错误）
     */
    public static byte[] encode(Scanner.ScanResult scan, Scanner.EntitySnapshot entities,
                                Set<String> channels, SnapshotCache.Delta delta) {
        Objects.requireNonNull(scan, "scan");
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(channels, "channels");

        int bytesPerBlock = bytesPerBlock(scan.palette().length);
        Scanner.OptionalChannels optional = scan.channels();
        if (channels.contains(CHANNEL_FLAGS)) {
            requireCollected(optional.flags(), CHANNEL_FLAGS);
        }
        if (channels.contains(CHANNEL_LIGHT)) {
            requireCollected(optional.light(), CHANNEL_LIGHT);
        }
        if (channels.contains(CHANNEL_BIOME)) {
            requireCollected(optional.biomeIndex(), CHANNEL_BIOME);
            Objects.requireNonNull(optional.biomePalette(), "biome channel requested but biomePalette is null");
        }

        byte[] header = Json.toJson(buildHeader(scan, entities, bytesPerBlock, delta)).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(INITIAL_BUFFER_BYTES);
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            putEntry(zip, ENTRY_HEADER, header);
            if (delta != null) {
                // 卡 §7.5：delta 命中时不输出 blocks.bin/blocks16.bin（blocks_delta.bin 替代）
                putEntry(zip, ENTRY_BLOCKS_DELTA, delta.encoded());
            } else if (channels.contains(CHANNEL_BLOCKS)) {
                putEntry(zip, blocksEntryName(bytesPerBlock), encodeBlocks(scan.blocks(), bytesPerBlock));
            }
            if (channels.contains(CHANNEL_HEIGHT)) {
                putEntry(zip, ENTRY_HEIGHT, int16LittleEndian(scan.height()));
            }
            if (channels.contains(CHANNEL_FLAGS)) {
                putEntry(zip, ENTRY_FLAGS, optional.flags());
            }
            if (channels.contains(CHANNEL_LIGHT)) {
                putEntry(zip, ENTRY_LIGHT, optional.light());
            }
            if (channels.contains(CHANNEL_BIOME)) {
                putEntry(zip, ENTRY_BIOME, optional.biomeIndex());
            }
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真抛 IO，包一层说明"编码失败"而不是假装成功
            throw new IllegalStateException("failed to build snapshot zip", e);
        }
        return buffer.toByteArray();
    }

    private static byte[] requireCollected(byte[] data, String channel) {
        if (data == null) {
            throw new IllegalStateException("channel '" + channel
                    + "' was requested but Scanner did not collect it (Params.channels mismatch)");
        }
        return data;
    }

    /** 调色板 entry 数 → 每块字节数（1|2）：{@code size > 256} 需要 uint16 下标（卡 §7.1）。 */
    public static int bytesPerBlock(int paletteSize) {
        return paletteSize > UINT8_ENTRIES ? 2 : 1;
    }

    private static String blocksEntryName(int bytesPerBlock) {
        return bytesPerBlock == 2 ? ENTRY_BLOCKS16 : ENTRY_BLOCKS;
    }

    /**
     * {@code short[]（uint16 下标）→ blocks.bin/blocks16.bin 字节}。
     *
     * <p>1 字节模式取低 8 位（palette &le; 256 时下标必 &le; 255）；2 字节模式写<b>小端</b>
     * {@code value & 0xFF, (value >>> 8) & 0xFF}。</p>
     */
    static byte[] encodeBlocks(short[] blocks, int bytesPerBlock) {
        if (bytesPerBlock == 1) {
            byte[] out = new byte[blocks.length];
            for (int i = 0; i < blocks.length; i++) {
                out[i] = (byte) (blocks[i] & 0xFF);
            }
            return out;
        }
        byte[] out = new byte[blocks.length * 2];
        for (int i = 0; i < blocks.length; i++) {
            int value = blocks[i] & 0xFFFF;
            out[i * 2] = (byte) (value & 0xFF);
            out[i * 2 + 1] = (byte) ((value >>> 8) & 0xFF);
        }
        return out;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** {@code short[] → int16 小端字节}。 */
    static byte[] int16LittleEndian(short[] values) {
        byte[] out = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            out[i * 2] = (byte) (value & 0xFF);
            out[i * 2 + 1] = (byte) ((value >> 8) & 0xFF);
        }
        return out;
    }

    /** 组装 {@code header.json}（SPEC §4.3 全字段；CB-07 起 16-bit/delta/flags/biome 有真实值）。 */
    static JsonObject buildHeader(Scanner.ScanResult scan, Scanner.EntitySnapshot entitySnapshot,
                                  int bytesPerBlock, SnapshotCache.Delta delta) {
        JsonObject header = Json.obj();
        header.addProperty("v", VERSION);
        header.addProperty("tick", scan.tick());
        header.addProperty("ms", scan.ms());
        header.addProperty("world", scan.world());
        header.addProperty("dim", scan.dim());
        header.add("origin", intArray(scan.origin()));
        header.add("size", intArray(new int[] {scan.nx(), scan.ny(), scan.nz()}));
        header.addProperty("order", ORDER);
        header.add("palette", stringArray(scan.palette()));
        // CB-07：块值宽度恒输出（delta 模式下客户端靠它解析 blocks_delta.bin 的值）
        header.addProperty("bytes_per_block", bytesPerBlock);
        // flags 的 8 个位名是协议常量（与是否请求 flags 无关），恒输出便于下游无条件读取
        header.add("flags_legend", stringArray(FLAGS_LEGEND.toArray(new String[0])));
        String[] biomePalette = scan.channels().biomePalette();
        header.add("biome_palette", stringArray(biomePalette == null ? new String[0] : biomePalette));
        header.add("player", player(scan.player()));
        header.add("counts", counts(scan));
        header.add("entities", entities(entitySnapshot));
        if (delta != null) {
            // 卡 §7.5：只有真的和基线差分成功才写 delta 键，不伪造 base_tick
            JsonObject deltaJson = Json.obj();
            deltaJson.addProperty("base_tick", delta.baseTick());
            deltaJson.addProperty("changed", delta.changed());
            header.add("delta", deltaJson);
        }
        header.add("unloaded", unloaded(scan.unloaded()));
        // 方块不分片，唯一会截断的是 entities（超过 64 条），故 truncated 直接取实体结果。
        header.addProperty("truncated", entitySnapshot.truncated());
        return header;
    }

    /** {@code entities}：{@code [{id,pos[3],dist,hp,name,passenger}]}（CB-06）。 */
    private static JsonArray entities(Scanner.EntitySnapshot snapshot) {
        JsonArray array = Json.arr();
        for (Scanner.EntityInfo entity : snapshot.entities()) {
            JsonObject json = Json.obj();
            json.addProperty("id", entity.id());
            JsonArray pos = Json.arr();
            pos.add(round(entity.x(), 100.0));
            pos.add(round(entity.y(), 100.0));
            pos.add(round(entity.z(), 100.0));
            json.add("pos", pos);
            json.addProperty("dist", round(entity.dist(), 100.0));
            json.addProperty("hp", round(entity.hp(), 100.0));
            // name 可空：共享 Gson 未开 serializeNulls，null 会让整个键消失（= 未命名），
            // 这比伪造空串语义更干净；下游判定"有 name 键 = 有自定义名"。
            if (entity.name() != null) {
                json.addProperty("name", entity.name());
            }
            json.addProperty("passenger", entity.passenger());
            array.add(json);
        }
        return array;
    }

    private static JsonObject player(Scanner.PlayerProbe player) {
        JsonObject json = Json.obj();
        JsonArray pos = Json.arr();
        pos.add(round(player.x(), 100.0));
        pos.add(round(player.y(), 100.0));
        pos.add(round(player.z(), 100.0));
        json.add("pos", pos);
        json.addProperty("yaw", round(player.yaw(), 10.0));
        json.addProperty("hp", round(player.hp(), 100.0));
        json.addProperty("gm", player.gameMode());
        return json;
    }

    /** {@code counts}：只计非空气（下标 0 = air 不输出），且只输出计数 > 0 的项。 */
    private static JsonObject counts(Scanner.ScanResult scan) {
        JsonObject counts = Json.obj();
        String[] palette = scan.palette();
        int[] perIndex = scan.counts();
        for (int index = 1; index < palette.length && index < perIndex.length; index++) {
            if (perIndex[index] > 0) {
                counts.addProperty(palette[index], perIndex[index]);
            }
        }
        return counts;
    }

    private static JsonArray unloaded(int[][] chunks) {
        JsonArray array = Json.arr();
        for (int[] chunk : chunks) {
            if (chunk == null || chunk.length < 2) {
                continue;
            }
            JsonArray pair = Json.arr();
            pair.add(chunk[0]);
            pair.add(chunk[1]);
            array.add(pair);
        }
        return array;
    }

    private static JsonArray intArray(int[] values) {
        JsonArray array = Json.arr();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray stringArray(String[] values) {
        JsonArray array = Json.arr();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** 四舍五入到 {@code 1/scale}（避免 JSON 里出现 0.30000000000000004 这种噪声）。 */
    private static double round(double value, double scale) {
        return Math.round(value * scale) / scale;
    }
}
