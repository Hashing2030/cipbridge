package dev.cip.bridge.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.google.gson.JsonObject;

import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.cip.bridge.http.Json;

/**
 * 玩家聊天框里发出的 {@code #dsh <文本>} 捕获（CB-14，SPEC v1.1 §4.8）。
 *
 * <p><b>为什么用 {@link ClientChatEvent} 而不是 {@link ChatCapture}</b>：{@code ChatCapture} 订阅的是
 * {@code ClientChatReceivedEvent}（"收到"聊天），本卡要的是"玩家<b>发送</b>"这条路径。21.1.248 提供
 * {@code ClientChatEvent}（实现了 {@code ICancellableEvent}，只在逻辑客户端触发）；取消后
 * {@code ClientHooks.onClientSendMessage} 返回空串，vanilla {@code ClientPacketListener.sendChat}
 * 因此直接 return，消息既不会发给服务器（不广播），也不会被当成命令执行。
 * 该链路由 merged jar 的字节码核对过，详见 {@code cipbridge/tasks/logs/CB-14-journal.md}。</p>
 *
 * <p><b>取消优先</b>：命中前缀后<b>第一件事</b>就是 {@code setCanceled(true)}，之后才做字符串处理、
 * 去重与落盘。这样即使写文件失败（磁盘满、权限、目录被删），消息也不会因为"入队失败"而被放回服务器。</p>
 *
 * <p><b>异常不得进事件总线</b>：本类对外的入口是事件回调，任何异常若抛回去会打断其它 mod 的聊天处理
 * （极端情况下让一次聊天发送直接失败）。因此：读消息、取消、落盘、显示提示四处分别 try/catch，
 * 全部只记日志。</p>
 *
 * <p><b>线程</b>：回调在客户端线程；写文件也在客户端线程（一行追加，微秒级）。读方是外部桥脚本
 * （{@code cipbridge/cip_chat_bridge.py}），按行追加写 + 单行原子性足够，不需要额外锁文件。</p>
 */
public final class ChatListener {

    /** 触发前缀；与正文大小写无关（{@code #DSH} / {@code #Dsh} 都算）。 */
    public static final String PREFIX = "#dsh";

    /** inbox 文件：SPEC §4.8 固定路径，外部桥脚本按此读取，**不要**改。 */
    public static final String INBOX_PATH = "/tmp/cip/chat-inbox.jsonl";

    /** 去重窗口：同一正文 1 s 内重复出现按"事件重复触发"处理（卡面 §防御性去重）。 */
    public static final long DEDUPE_WINDOW_MS = 1_000L;

    /** 正文为空时只本地提示，不写 inbox（卡面约束）。 */
    public static final String USAGE_HINT = "用法：#dsh <内容>";

    /** 入队成功的本地提示：让玩家知道这条已经被 DSH 接走，而不是"发出去了没反应"。 */
    public static final String ACCEPTED_HINT = "[CIP] 已交给 DSH，等待回复…";

    /** inbox 行时间格式（SPEC §4.8：{@code YYYY-MM-DD HH:MM:SS}）。 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private static final Logger LOG = LoggerFactory.getLogger("cipbridge");

    private static final ChatListener INSTANCE = new ChatListener();

    /** 保护 {@link #installed} 与去重字段；事件回调与（可能的）重复 install 共用。 */
    private final Object lock = new Object();

    /** 事件监听是否已挂上（幂等保护）。 */
    private volatile boolean installed;

    /** 上一次入队的正文与墙钟毫秒（去重用）。受 {@link #lock} 保护。 */
    private String lastText;

    private long lastAtMillis;

    private ChatListener() {
    }

    /** 全局单例：事件总线只应订阅这一个实例。 */
    public static ChatListener get() {
        return INSTANCE;
    }

    /**
     * 把发送聊天监听挂到 {@code NeoForge.EVENT_BUS}（只订阅一次）。
     *
     * <p>只应由 {@code CipBridge} 在模组构造期调用一次；重复调用是无操作。</p>
     */
    public void install() {
        if (installed) {
            return;
        }
        synchronized (lock) {
            if (installed) {
                return;
            }
            // 显式声明参数类型：addListener 是 <T extends Event>，方法引用无法脱离目标类型推断。
            NeoForge.EVENT_BUS.addListener((ClientChatEvent event) -> onClientChat(event));
            installed = true;
            LOG.info("[cipbridge] #dsh chat capture installed (inbox: {})", INBOX_PATH);
        }
    }

    /**
     * 事件回调（客户端线程）。命中前缀 → 立刻取消 → 剥离正文 → 空正文提示 / 去重 / 追加 inbox / 本地提示。
     *
     * <p>除"前缀不匹配"外，任何分支都不把异常抛回事件总线。</p>
     */
    private void onClientChat(ClientChatEvent event) {
        if (event == null) {
            return;
        }
        String message;
        try {
            message = event.getMessage();
        } catch (RuntimeException e) {
            // 连消息都读不出来就什么都不做：不动这条消息，也不取消它
            LOG.warn("[cipbridge] could not read outgoing chat message; left untouched", e);
            return;
        }

        String body = bodyOf(message);
        if (body == null) {
            return; // 不是 #dsh 消息：完全不介入
        }

        // ── 1) 先取消，再干活 ───────────────────────────────────────────────
        try {
            event.setCanceled(true);
        } catch (RuntimeException e) {
            // 取消失败意味着消息会被发出去：此时不能再往 inbox 写，否则 DSH 会处理一条玩家也发了的消息
            LOG.warn("[cipbridge] failed to cancel #dsh message; capture aborted", e);
            return;
        }

        // ── 2) 入队（异常绝不外泄）──────────────────────────────────────────
        try {
            handle(body);
        } catch (RuntimeException e) {
            LOG.warn("[cipbridge] failed to enqueue #dsh message", e);
        }
    }

    /**
     * 前缀判定 + 正文剥离（纯函数，无 I/O、无 MC，便于 headless 自测与代码评审）。
     *
     * <p>{@code message.strip()} 后<b>小写</b>以 {@value #PREFIX} 开头才算命中（大小写不敏感）；
     * 剥离前缀后再 {@code strip()} 得到正文。命中但正文为空返回空串 {@code ""}（调用方只本地提示）；
     * 不命中返回 {@code null}（调用方完全不介入）。</p>
     *
     * @param message 玩家输入（可为 {@code null}）
     * @return 正文（可能为空串），或 {@code null}＝不是 #dsh 消息
     */
    public static String bodyOf(String message) {
        if (message == null) {
            return null;
        }
        String stripped = message.strip();
        if (!stripped.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return null;
        }
        return stripped.substring(PREFIX.length()).strip();
    }

    /** 取消之后的全部动作；每个失败点都只降级为日志。 */
    private void handle(String body) {
        if (body.isEmpty()) {
            // 卡面：剥离后正文为空 → 只本地提示「用法：#dsh <内容>」，不写 inbox
            showLocalHint(USAGE_HINT);
            return;
        }
        if (isDuplicate(body)) {
            LOG.debug("[cipbridge] dropped duplicate #dsh message within {} ms", DEDUPE_WINDOW_MS);
            return;
        }
        if (appendToInbox(body)) {
            showLocalHint(ACCEPTED_HINT);
        }
    }

    /**
     * 防御性去重：同一正文在 {@value #DEDUPE_WINDOW_MS} ms 内再次出现则忽略（不写 inbox、不提示）。
     *
     * <p>窗口每次命中都刷新（即"连续重复只算一条"），避免同一消息被连续按下发送键刷屏。</p>
     */
    private boolean isDuplicate(String body) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            boolean duplicate = lastText != null
                    && lastText.equals(body)
                    && now - lastAtMillis <= DEDUPE_WINDOW_MS;
            lastText = body;
            lastAtMillis = now;
            return duplicate;
        }
    }

    /** 追加一行到 {@value #INBOX_PATH}；失败只记日志并返回 {@code false}。 */
    private static boolean appendToInbox(String body) {
        return appendLine(Path.of(INBOX_PATH), body);
    }

    /**
     * 追加一行 {@code {"t":"YYYY-MM-DD HH:MM:SS","text":"..."}}（UTF-8、追加写、不覆盖历史）。
     *
     * <p>公开静态是为了在 headless 冒烟里对<b>真实</b>落盘格式取证（零 MC、零事件依赖），
     * 生产路径仍然是 {@value #INBOX_PATH} 这一处调用。</p>
     *
     * <p>写失败（目录建不出来 / 权限 / 磁盘满）**必须**被吞掉：事件回调里抛异常会污染事件总线。
     * 首次创建文件时尽力设 600（inbox 里是玩家的原话，可能含敏感内容）；非 POSIX 文件系统退回默认权限。</p>
     *
     * @return {@code true} 写成功
     */
    public static boolean appendLine(Path path, String body) {
        if (path == null || body == null) {
            return false;
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ensureOwnerOnlyFile(path);
            JsonObject json = Json.obj();
            json.addProperty("t", LocalDateTime.now().format(TIME_FORMAT));
            json.addProperty("text", body);
            Files.writeString(path, Json.toJson(json) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            // 注意：不打印 body 内容，避免把玩家原话写进游戏日志
            LOG.warn("[cipbridge] failed to append to chat inbox {}: {}", path, e.toString());
            return false;
        }
    }

    /** 文件不存在时以 600 创建；已存在则不动（也绝不截断已有历史）。 */
    private static void ensureOwnerOnlyFile(Path path) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（如部分 Windows）：退回默认权限
            Files.createFile(path);
        } catch (FileAlreadyExistsException e) {
            // 并发创建：别人已经建好了，继续追加即可
        }
    }

    /** 本地提示：进世界就显示到聊天框，没进世界只记日志（不抛异常）。 */
    private static void showLocalHint(String hint) {
        try {
            if (!ChatDisplay.showToPlayer(hint)) {
                LOG.info("[cipbridge] (not ingame) local hint: {}", hint);
            }
        } catch (RuntimeException e) {
            LOG.debug("[cipbridge] could not show local hint", e);
        }
    }
}
