package dev.cip.bridge.world;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * delta 基线缓存（CB-07 §7.5）：保存<b>最近一次全量</b> blocks 数组与 key（dim/origin/size/tick）。
 *
 * <p><b>线程铁律（SPEC §2.4 / §6）</b>：本类<b>只允许在服务端线程</b>访问——唯一调用点是
 * {@code SnapshotHandler} 里 {@code ThreadBridge.onServer(...)} 的 supplier 内部。
 * 本类不做锁/同步：HTTP 线程永远碰不到这里（静态字段读写都发生在同一服务端线程）。</p>
 *
 * <p>delta 只覆盖 {@code blocks} 通道：{@code height/flags/light/biome} 在 delta 模式仍回全量。</p>
 *
 * <p>基线 key 按卡是 {@code dim+origin+size}；这里<b>额外</b>要求调色板逐项相等，否则按
 * "缓存不匹配 → 退回全量"处理。原因：调色板下标是"扫描中首次遇到该方块的顺序"，两次快照若
 * 调色板不同，同一个数值在不同快照里可能指向不同方块，直接 diff 会静默产出错误方块数据。</p>
 */
public final class SnapshotCache {

    /** 上一次全量快照的基线；{@code null} = 无缓存。只在服务端线程读写。 */
    private static Baseline last;

    private SnapshotCache() {
    }

    /**
     * 一次 delta 的编码结果（纯值，HTTP 线程可安全持有）。
     *
     * @param encoded  已编码的 {@code blocks_delta.bin}：{@code varint(索引差) + uint8|uint16(新值)} 交替，
     *                 索引差从 0 起算（LEB128 无符号）
     * @param baseTick 基线快照的 tick（{@code header.delta.base_tick}）
     * @param changed  变化的格数（{@code header.delta.changed}）
     */
    public record Delta(byte[] encoded, long baseTick, int changed) {
    }

    /**
     * 一次扫描 + 可能的 delta（纯值）。supplier 返回它，让 HTTP 线程一次投递就拿到全部数据。
     *
     * @param scan  本次扫描结果（非 null）
     * @param delta delta 结果；未请求/无基线/key 不匹配时为 {@code null}（调用方按全量输出）
     */
    public record Captured(Scanner.ScanResult scan, Delta delta) {
    }

    /**
     * 用本次扫描结果对比基线并刷新缓存。必须在<b>服务端线程</b>调用。
     *
     * <p>无论是否请求 delta、是否命中 key，本次 full blocks 都会写入缓存（卡 §7.5：每次都刷新）。</p>
     *
     * @param scan      本次全量扫描；{@code null} 表示没有单机服务器/维度不可达，直接原样返回 {@code null}
     * @param wantDelta 调用方是否请求 delta（false 时只刷新缓存，不做 diff）
     * @return {@code scan == null ? null : new Captured(scan, deltaOrNull)}；{@code deltaOrNull} 仅在
     *         {@code wantDelta &&} 基线存在且 key 匹配时非 null
     */
    public static Captured capture(Scanner.ScanResult scan, boolean wantDelta) {
        if (scan == null) {
            return null;
        }
        Baseline base = last;
        Delta delta = (wantDelta && base != null && base.matches(scan)) ? diff(base, scan) : null;
        last = new Baseline(scan);
        return new Captured(scan, delta);
    }

    /** 清空基线（仅供测试/切世界时调用；同样只允许服务端线程）。 */
    public static void clear() {
        last = null;
    }

    private static Delta diff(Baseline base, Scanner.ScanResult current) {
        short[] oldBlocks = base.blocks;
        short[] newBlocks = current.blocks();
        if (oldBlocks.length != newBlocks.length) {
            // key 已保证 size 相同，这里是纯防御：长度不同无法按索引 diff
            return null;
        }
        int width = SnapshotCodec.bytesPerBlock(current.palette().length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        int previousIndex = 0;
        int changed = 0;
        for (int index = 0; index < newBlocks.length; index++) {
            if (newBlocks[index] == oldBlocks[index]) {
                continue;
            }
            writeVarint(out, index - previousIndex);
            writeValue(out, newBlocks[index] & 0xFFFF, width);
            previousIndex = index;
            changed++;
        }
        return new Delta(out.toByteArray(), base.tick, changed);
    }

    /** LEB128 无符号 varint（7 bit/字节，MSB=继续位，CB-07 §7.5）。 */
    static void writeVarint(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    /** {@code uint8|uint16} 小端值。 */
    static void writeValue(ByteArrayOutputStream out, int value, int width) {
        out.write(value & 0xFF);
        if (width == 2) {
            out.write((value >>> 8) & 0xFF);
        }
    }

    /** 上一次全量快照（不可变，delta 对比用）。 */
    private static final class Baseline {

        private final String dim;
        private final int[] origin;
        private final int nx;
        private final int ny;
        private final int nz;
        private final String[] palette;
        private final short[] blocks;
        private final long tick;

        private Baseline(Scanner.ScanResult scan) {
            this.dim = scan.dim();
            this.origin = scan.origin().clone();
            this.nx = scan.nx();
            this.ny = scan.ny();
            this.nz = scan.nz();
            this.palette = scan.palette().clone();
            this.blocks = scan.blocks().clone();
            this.tick = scan.tick();
        }

        private boolean matches(Scanner.ScanResult scan) {
            return dim.equals(scan.dim())
                    && nx == scan.nx()
                    && ny == scan.ny()
                    && nz == scan.nz()
                    && Arrays.equals(origin, scan.origin())
                    // 额外的安全条件：调色板必须完全一致（下标含义才一致），否则退回全量
                    && Arrays.equals(palette, scan.palette());
        }
    }
}
