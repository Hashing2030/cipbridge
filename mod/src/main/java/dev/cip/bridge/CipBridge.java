package dev.cip.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.HexFormat;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.cip.bridge.exec.ChatCapture;
import dev.cip.bridge.exec.ChatListener;
import dev.cip.bridge.http.BatchFileHandler;
import dev.cip.bridge.http.BatchHandler;
import dev.cip.bridge.http.ChatSayHandler;
import dev.cip.bridge.http.CommandHandler;
import dev.cip.bridge.http.FindHandler;
import dev.cip.bridge.http.HttpApi;
import dev.cip.bridge.http.PingHandler;
import dev.cip.bridge.http.RequestLog;
import dev.cip.bridge.http.SnapshotHandler;
import dev.cip.bridge.http.StateHandler;

/**
 * CIP Bridge 模组入口（CB-02：配置 + 生命周期 + HTTP 骨架接入）。
 *
 * <p>本类只负责"接线"：注册配置、在客户端 setup 阶段启动 {@link HttpApi}、
 * 在游戏退出时停止它、以及准备 token（生成 / 落盘 / 读取）。</p>
 *
 * <p>职责边界（重要）：</p>
 * <ul>
 *   <li>token 的"生成与落盘"与 {@link HttpApi} 解耦——HttpApi 只接收 token 字符串做校验，
 *       因此可以在裸 JDK 进程里自测（{@code dev.cip.bridge.dev.HttpApiSmoke}）。</li>
 *   <li>具体端点由各任务卡以独立处理器类注册（CB-03 起：{@code /state} 与带 tick 的 {@code /ping}），
 *       HttpApi 本体保持无 Minecraft import；</li>
 *   <li>硬约束（SPEC §2）：客户端侧、零第三方库、不加 mixin、只绑 127.0.0.1、
 *       世界访问一律经 {@code dev.cip.bridge.exec.ThreadBridge} 投递。</li>
 * </ul>
 */
@Mod(CipBridge.MOD_ID)
public final class CipBridge {

    /** 模组 id，须与 neoforge.mods.toml 的 modId 一致。 */
    public static final String MOD_ID = "cipbridge";

    /** token 目录名（相对于 FML 的 config 目录）。 */
    private static final String TOKEN_DIR_NAME = "cipbridge";

    /** token 文件名。 */
    private static final String TOKEN_FILE_NAME = "token.txt";

    /** SPEC §3：token 为 32 字节随机数 → 64 个 hex 字符。 */
    private static final int TOKEN_BYTES = 32;

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Object lifecycleLock = new Object();

    private volatile HttpApi httpApi;

    /** 请求日志落盘端（CB-10）；未启动/已停止/headless 降级时为 {@code null}。 */
    private volatile RequestLog requestLog;

    private volatile Path tokenFile;

    private volatile boolean shutdownHookInstalled;

    /**
     * NeoForge 模组入口构造。
     *
     * <p>构造函数里<b>不能</b>读 {@link Config} 的值：配置文件在构造之后才加载，
     * {@code get()} 会抛 IllegalStateException。因此这里只注册配置与事件监听。</p>
     */
    public CipBridge(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
        modEventBus.addListener(this::onClientSetup);
        NeoForge.EVENT_BUS.addListener(this::onGameShuttingDown);
        // 聊天回显捕获只订阅一次，且与配置无关（不依赖 enabled/allow_mutations），
        // 放在构造期是为了不丢"HTTP 起来之前"的聊天——缓冲是固定容量环形数组，代价可忽略。
        ChatCapture.get().install();
        // CB-14：「发送」聊天（#dsh）捕获——可取消事件，独立于上面的「收到」聊天缓冲。
        // 同样与配置无关（不依赖 enabled/allow_mutations）：取消语义不该因为写闸门被关掉而失效。
        ChatListener.get().install();
        LOG.info("[{}] loaded; client config registered", MOD_ID);
    }

    /** 客户端 setup：此时 CLIENT 配置已加载，可以安全读取。 */
    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                startHttp();
            } catch (RuntimeException e) {
                // 桥接服务起不来不能连累游戏启动：记录并放弃（配置可修正后重启游戏）
                LOG.error("[{}] unexpected failure while starting HTTP bridge", MOD_ID, e);
            }
        });
    }

    /** 游戏退出（Forge 事件总线，物理客户端会触发一次）。 */
    private void onGameShuttingDown(GameShuttingDownEvent event) {
        stopHttp();
    }

    /** 启动 HTTP 服务；失败只记日志，绝不让模组加载失败。 */
    private void startHttp() {
        synchronized (lifecycleLock) {
            if (httpApi != null) {
                return;
            }
            if (!Config.ENABLED.get()) {
                LOG.info("[{}] disabled via config (enabled=false); HTTP not started", MOD_ID);
                return;
            }

            String token;
            try {
                token = resolveToken();
            } catch (IOException e) {
                LOG.error("[{}] cannot prepare token file; HTTP not started", MOD_ID, e);
                return;
            }

            HttpApi api = new HttpApi(Config.PORT.get(), token, Config.RATE_LIMIT.get());
            // 请求日志（CB-10 10.2，SPEC §7）：游戏日志 + logs/cipbridge.log 双写。
            // RequestLog 本身零 MC/FML 引用；FML 路径服务不可用时 openRequestLog() 返回 no-op 实例。
            RequestLog requestLog = openRequestLog();
            api.setLogger(line -> {
                LOG.info("[{}] http {}", MOD_ID, line);
                requestLog.accept(line);
            });
            registerEndpoints(api, token);
            try {
                int boundPort = api.start();
                httpApi = api;
                this.requestLog = requestLog;
                installShutdownHook();
                LOG.info("[{}] HTTP listening on 127.0.0.1:{} (allow_mutations={}, rate_limit={}/s, token file: {})",
                        MOD_ID,
                        boundPort,
                        Config.ALLOW_MUTATIONS.get(),
                        Config.RATE_LIMIT.get(),
                        tokenFile);
                if (requestLog.isEnabled()) {
                    LOG.info("[{}] request log -> {}", MOD_ID, requestLog.file());
                } else {
                    // headless / FML 路径服务未建立：降级为 no-op，但要让用户知道日志没落盘
                    LOG.warn("[{}] request log disabled (no game directory); requests are not persisted", MOD_ID);
                }
            } catch (IOException e) {
                api.stop();
                requestLog.close();
                LOG.error("[{}] failed to start HTTP server on 127.0.0.1:{} (ports +0..+{} tried)",
                        MOD_ID, Config.PORT.get(), HttpApi.PORT_RETRIES - 1, e);
            }
        }
    }

    /**
     * 注册需要 Minecraft 的端点。
     *
     * <p>处理器放在独立类里（HttpApi 保持无 MC import，可在裸 JDK 进程自测）：
     * <ul>
     *   <li>{@code GET /state}：玩家状态快照，经 ThreadBridge 投递到客户端线程（CB-03）；</li>
     *   <li>{@code GET /ping}：覆盖 HttpApi 内置的纯 JDK 版本，额外附带可选 tick
     *       （读不到就省略，探活语义不变）；</li>
     *   <li>{@code GET /cmd}：发命令 + 回显捕获（CB-04）。写闸门传的是 supplier，
     *       每请求实时读 {@code Config.ALLOW_MUTATIONS}（默认 false）。</li>
     *   <li>{@code GET /snapshot}：区域方块快照（CB-05）。参数校验在 HTTP 线程完成，
     *       扫描经 {@code ThreadBridge.onServer} 在服务端线程同步执行，ZIP 由零 MC 的
     *       {@code world.SnapshotCodec} 打包；{@code entities} 通道（CB-06）同样走
     *       {@code ThreadBridge.onServer}。</li>
     *   <li>{@code GET /find}：按方块注册名定向查询坐标（CB-06）。校验在 HTTP 线程，
     *       区块遍历经 {@code ThreadBridge.onServer} 在服务端线程执行。</li>
     *   <li>{@code POST /batch}：批量命令（CB-08）。body 解析与<b>全量校验</b>在 HTTP 线程完成
     *       （校验实现与 {@code /cmd} 共用 {@code exec.CommandValidator}），任一非法即 403 且
     *       一条都不执行；通过后逐条经 {@code ThreadBridge.onClient} 投递下发，间隔
     *       {@code spacing_ticks × 50 ms}。</li>
     *   <li>{@code POST /batch-file}：读本地文件批量执行（CB-09）。白名单 {@code /tmp/cip/}
     *       （normalize + 分量比较）、读文件与按行切分全在 HTTP 线程（零 MC 访问），之后与
     *       {@code /batch} 共用<b>同一个</b> {@code BatchHandler.executeBatch}——校验顺序、
     *       写闸门、失败语义与响应字段完全一致。</li>
     *   <li>{@code POST /chat-say}：把文本显示到玩家聊天框（CB-14）。body 解析与长度截断在 HTTP 线程，
     *       显示经 {@code ThreadBridge.onClient} 投递到客户端线程的 {@code exec.ChatDisplay}；
     *       <b>不受</b> {@code allow_mutations} 限制（纯客户端显示，不写世界），但 token 照常校验。</li>
     * </ul>
     *
     * <p><b>token 注入（CB-10）</b>：三个写端点除了 {@code allow_mutations} 还拿到 {@code () -> token}，
     * 用于危险命令的第二道闸（{@code confirm=<token>}）。token 已经在本方法调用前解析完成，
     * 因此这里传的是<b>值捕获的 lambda</b>——HTTP 重启会重新解析 token 并重建处理器，不会用到旧值。</p>
     *
     * @param token 本次启动解析出的 bridge token（非空）
     */
    private static void registerEndpoints(HttpApi api, String token) {
        api.route("GET", "/state", new StateHandler());
        api.route("GET", "/ping", new PingHandler(api));
        api.route("GET", "/cmd", new CommandHandler(() -> Config.ALLOW_MUTATIONS.get(), () -> token));
        // 半径上限用 supplier 实时读配置（默认 128；handler 内再与 128 硬顶取小）
        api.route("GET", "/snapshot", new SnapshotHandler(() -> Config.RADIUS_MAX.get()));
        api.route("GET", "/find", new FindHandler(() -> Config.RADIUS_MAX.get()));
        // 写闸门同样是 supplier：每请求实时读配置，默认 false
        BatchHandler batch = new BatchHandler(() -> Config.ALLOW_MUTATIONS.get(), () -> token);
        api.route("POST", "/batch", batch);
        // /batch-file 复用同一个 BatchHandler（同一写闸门、同一 executeBatch 语义）
        api.route("POST", "/batch-file", new BatchFileHandler(batch));
        // CB-14：只显示、不写世界 → 不传 allowMutations，只靠 HttpApi 的 token 校验。
        api.route("POST", "/chat-say", new ChatSayHandler());
    }

    /**
     * 打开请求日志（CB-10 10.2，SPEC §7）：{@code <gameDir>/logs/cipbridge.log}，追加写。
     *
     * <p>路径取 {@code FMLPaths.GAMEDIR}（FML 的路径服务，不是 Minecraft 对象），因此这条链路上
     * 没有任何 {@code net.minecraft} 引用；裸 JDK 进程里 FML 路径服务未建立时会抛
     * {@link RuntimeException}，此时与 {@code GAMEDIR == null} 一样降级为 no-op 实例——
     * <b>不崩、不建文件</b>，只是请求日志不落盘。</p>
     */
    private static RequestLog openRequestLog() {
        Path gameDir;
        try {
            gameDir = FMLPaths.GAMEDIR.get();
        } catch (RuntimeException e) {
            return RequestLog.disabled();
        }
        return RequestLog.open(gameDir, message -> LOG.warn("[{}] {}", MOD_ID, message));
    }

    /** 幂等停止。 */
    private void stopHttp() {
        synchronized (lifecycleLock) {
            HttpApi api = httpApi;
            httpApi = null;
            if (api != null) {
                api.stop();
                LOG.info("[{}] HTTP stopped", MOD_ID);
            }
            // 先停服务再关日志：api.stop() 自己会写一行 "stopped"
            RequestLog log = requestLog;
            requestLog = null;
            if (log != null) {
                log.close();
            }
        }
    }

    /**
     * 解析 token：配置里显式配置则优先；否则复用 {@code config/cipbridge/token.txt}，
     * 文件不存在/为空则生成 32 字节随机 hex 并落盘（权限 600）。
     */
    private String resolveToken() throws IOException {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(TOKEN_DIR_NAME);
        Files.createDirectories(dir);
        Path file = dir.resolve(TOKEN_FILE_NAME);
        this.tokenFile = file;

        String configured = Config.TOKEN.get();
        String token;
        String source;
        if (configured != null && !configured.isBlank()) {
            token = configured.trim();
            source = "config";
        } else if (Files.isReadable(file)) {
            String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (existing.isEmpty()) {
                token = generateToken();
                source = "generated (existing file was empty)";
            } else {
                token = existing;
                source = "token.txt";
            }
        } else {
            token = generateToken();
            source = "generated";
        }

        Files.writeString(file, token + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        restrictToOwner(file);

        if (!"token.txt".equals(source)) {
            LOG.info("[{}] token from {} and mirrored to {}", MOD_ID, source, file);
        }
        return token;
    }

    /** 尽力设置 600；不支持 POSIX 权限的文件系统（如部分 Windows）只告警。 */
    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            LOG.warn("[{}] POSIX permissions unavailable; token file is not restricted to owner (600): {}",
                    MOD_ID, file);
        } catch (IOException e) {
            LOG.warn("[{}] failed to set 600 on token file: {}", MOD_ID, file, e);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** JVM 退出兜底：HttpApi 的线程都是 daemon，不会卡住退出；这里再 stop 一次保证端口释放。 */
    private void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stopHttp, "cipbridge-shutdown"));
        } catch (IllegalStateException e) {
            // JVM 已经在关闭流程中，无法再挂 hook；GameShuttingDownEvent 会覆盖这种情况
            LOG.warn("[{}] could not install shutdown hook (JVM already shutting down)", MOD_ID, e);
        }
    }
}
