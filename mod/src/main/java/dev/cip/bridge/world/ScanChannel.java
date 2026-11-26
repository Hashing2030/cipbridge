package dev.cip.bridge.world;

/**
 * 可选快照通道名（CB-07）：{@code /snapshot?channels=} 里除 {@code blocks}/{@code height}/
 * {@code entities} 之外的三条"重通道"。
 *
 * <p><b>本枚举零 Minecraft import</b>，因此 HTTP 线程（{@code SnapshotHandler}）可以安全地拿它
 * 做"请求字符串 → 枚举"的映射，再把 {@code Set<ScanChannel>} 放进纯值
 * {@link Scanner.Params} 投递给服务端线程；真正读光照/生物群系/方块状态的动作只发生在
 * {@link Scanner#scan} 内（{@code ThreadBridge.onServer} 的 supplier）。</p>
 */
public enum ScanChannel {

    /** {@code light.bin}：每格方块光照 0..15（{@code LightLayer.BLOCK}）。 */
    LIGHT,

    /** {@code flags.bin}：每格 8 位方块属性（见 {@code SnapshotCodec.FLAGS_LEGEND}）。 */
    FLAGS,

    /** {@code biome.bin}：每 4×4 列一个生物群系调色板下标。 */
    BIOME
}
