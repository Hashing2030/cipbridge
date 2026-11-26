package dev.cip.bridge.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块调色板（SPEC v1.1 §4.3）：{@link BlockState} → {@code uint8} 下标。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>{@code minecraft:air} 固定在<b>下标 0</b>（SPEC §4.3 硬要求），且所有
 *       {@link BlockState#isAir()} 为真的状态（{@code air}/{@code cave_air}/{@code void_air}）
 *       都归到下标 0——快照协议里"是不是空气"只看下标是否为 0，不区分空气种类；</li>
 *   <li>按<b>方块注册名</b>去重（`BuiltInRegistries.BLOCK.getKey(state.getBlock())`），
 *       同一方块的不同 {@code BlockState}（朝向/水位等）共用同一个下标；</li>
 *   <li>下标容器是 <b>uint16</b>（CB-07 16-bit 模式）：最多 65536 个 entry，air 占掉 0，因此非 air
 *       最多 {@value #MAX_NON_AIR} 种，超出抛 {@link OverflowException}（现实世界不可达，纯防御）；</li>
 *   <li>字节宽度不在这里决定：{@link SnapshotCodec} 按 {@code palette.size() > 256} 选
 *       {@code blocks16.bin}（uint16 小端）或 {@code blocks.bin}（uint8）。</li>
 * </ul>
 *
 * <p>线程：<b>非</b>线程安全，但只在单次扫描内由服务端线程独占使用（每个快照 new 一个）。</p>
 *
 * <p>性能：按 {@link Block} 缓存"方块 → 下标"，避免逐格调
 * {@code registry.getKey(...)}（那次调用会分配 ResourceKey/Optional/字符串）。扫描 400 万格时这是
 * 主要的热路径之一。</p>
 */
public final class Palette {

    /** air 的固定下标（SPEC §4.3）。 */
    public static final int AIR_INDEX = 0;

    /** air 的注册名（palette[0] 恒为该值）。 */
    public static final String AIR_NAME = "minecraft:air";

    /** 下标位宽上限：uint16 最多 65536 个 entry（下标 0..65535）。 */
    public static final int MAX_ENTRIES = 1 << 16;

    /** 非 air 种类上限（air 占掉下标 0）。 */
    public static final int MAX_NON_AIR = MAX_ENTRIES - 1;

    /** 未注册方块（`getKey` 返回 null，正常不应发生）的兜底名字。 */
    private static final String UNKNOWN_NAME = "unknown:unregistered";

    /** 下标 → 注册名；下标 0 恒为 {@link #AIR_NAME}。 */
    private final List<String> names = new ArrayList<>(64);

    /** 注册名 → 下标（按名字去重的权威来源）。 */
    private final Map<String, Integer> byName = new HashMap<>(64);

    /** 方块 → 下标（热路径缓存；名字→下标仍是权威，二者只做加速）。 */
    private final Map<Block, Integer> byBlock = new HashMap<>(64);

    public Palette() {
        register(AIR_NAME);
    }

    /**
     * 状态的调色板下标：空气恒 {@link #AIR_INDEX}，其它按注册名去重分配（从 1 开始）。
     *
     * @throws OverflowException 非 air 种类超过 {@value #MAX_NON_AIR}（uint16 装不下；纯防御）
     */
    public int id(BlockState state) {
        if (state.isAir()) {
            return AIR_INDEX;
        }
        Block block = state.getBlock();
        Integer cached = byBlock.get(block);
        if (cached != null) {
            return cached;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String name = key == null ? UNKNOWN_NAME : key.toString();
        Integer known = byName.get(name);
        int index;
        if (known != null) {
            index = known;
        } else {
            if (names.size() >= MAX_ENTRIES) {
                throw new OverflowException("block palette overflow: more than " + MAX_NON_AIR
                        + " distinct non-air block types in the requested area (uint16 index limit)");
            }
            index = register(name);
        }
        byBlock.put(block, index);
        return index;
    }

    /** 已登记的种类数（含 air）。 */
    public int size() {
        return names.size();
    }

    /** 注册名表（下标即数组下标；[0] 恒为 {@link #AIR_NAME}）。 */
    public String[] toArray() {
        return names.toArray(new String[0]);
    }

    private int register(String name) {
        int index = names.size();
        names.add(name);
        byName.put(name, index);
        return index;
    }

    /** 下标容器（uint16）耗尽：纯防御分支，handler 不再有专门的 400 映射（CB-07）。 */
    public static final class OverflowException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OverflowException(String message) {
            super(message);
        }
    }
}
