package dev.cip.bridge;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * CIP Bridge 配置项（SPEC v1.1 §3）。
 *
 * <p>配置类型为 {@link net.neoforged.fml.config.ModConfig.Type#CLIENT}：本模组是客户端侧模组，
 * 配置按客户端实例保存。所有默认值以 SPEC §3 为准，不得在此处擅自更改：
 * {@code enabled=true}、{@code port=8642}、{@code allow_mutations=false}、{@code rate_limit=5}、
 * {@code scan_budget=4}、{@code radius_max=128}、{@code token=启动生成}。</p>
 *
 * <p>注意：{@link ModConfigSpec.ConfigValue#get()} 在配置文件加载完成前调用会抛
 * {@link IllegalStateException}，因此本类的值只能在 FML 生命周期事件（如 FMLClientSetupEvent）
 * 之后读取，不能在 {@code CipBridge} 构造函数里读。</p>
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 总开关。默认 true（SPEC §3）。 */
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("总开关。false 时不启动 HTTP 服务。",
                    "Master switch. When false the HTTP server is not started.")
            .define("enabled", true);

    /** 监听端口。默认 8642；被占用时自动 +1 重试（最多 10 次，见 HttpApi）。 */
    public static final ModConfigSpec.IntValue PORT = BUILDER
            .comment("HTTP 端口。默认 8642；冲突时自动 +1 重试至多 10 次。",
                    "HTTP port. Default 8642; on conflict the bridge retries port+1 up to 10 times.")
            .defineInRange("port", 8642, 1, 65535);

    /** 写权限总闸。默认 false（SPEC §2.6 / §3）。 */
    public static final ModConfigSpec.BooleanValue ALLOW_MUTATIONS = BUILDER
            .comment("写权限总闸：/cmd /batch /batch-file 是否可用。默认 false（只读）。",
                    "Write gate for /cmd, /batch and /batch-file. Default false (read-only).")
            .define("allow_mutations", false);

    /** 限流：每秒请求数。默认 5。 */
    public static final ModConfigSpec.IntValue RATE_LIMIT = BUILDER
            .comment("限流：每秒允许的请求数（滑动窗口）。默认 5。",
                    "Rate limit: requests per second (sliding window). Default 5.")
            .defineInRange("rate_limit", 5, 1, 1000);

    /** 每 tick 扫描区块预算。默认 4。 */
    public static final ModConfigSpec.IntValue SCAN_BUDGET = BUILDER
            .comment("每 tick 扫描的区块数预算。默认 4。",
                    "Chunk-scan budget per tick. Default 4.")
            .defineInRange("scan_budget", 4, 1, 64);

    /** 快照半径上限。默认 128。 */
    public static final ModConfigSpec.IntValue RADIUS_MAX = BUILDER
            .comment("快照半径上限（方块）。默认 128。",
                    "Maximum snapshot radius in blocks. Default 128.")
            .defineInRange("radius_max", 128, 16, 512);

    /** 鉴权 token。留空表示启动时生成并写入 config/cipbridge/token.txt（SPEC §3）。 */
    public static final ModConfigSpec.ConfigValue<String> TOKEN = BUILDER
            .comment("鉴权 token（请求头 X-CIP-Token）。留空 = 启动时自动生成 32 字节随机 hex，",
                    "写入 config/cipbridge/token.txt（权限 600）。",
                    "",
                    "Authentication token (header X-CIP-Token). Empty means: generate a 32-byte",
                    "random hex token at startup and persist it to config/cipbridge/token.txt (mode 600).")
            .define("token", "");

    /** 配置规格，须由模组容器注册（ModConfig.Type.CLIENT）。 */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
