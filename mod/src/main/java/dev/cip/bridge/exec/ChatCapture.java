package dev.cip.bridge.exec;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端聊天回显捕获（CB-04，SPEC v1.1 §4.2）。
 *
 * <p><b>为什么不是 arm/disarm</b>：如果"发命令前打开监听、收到一行后关掉"，就必须处理
 * 「打开/关闭」与「事件线程触发」之间的竞态（漏掉窗口第一行、或把下一次命令的行算进这一次）。
 * 这里改成<b>常开 + 序号窗口</b>：{@link #mark()} 取一个单调序号，{@link #since(long)}
 * 取该序号之后的所有行。发送侧只记一个数字，不存在开/关状态。</p>
 *
 * <p><b>线程安全</b>：追加发生在客户端（渲染）线程的事件总线上，读取发生在
 * {@code cipbridge-http-*} HTTP 线程上。全部字段（{@code texts}/{@code atMillis}/{@code written}）
 * 都由同一个 {@code lock} 保护——读侧拿到的永远是某一时刻的一致切片，不会读到写了一半的槽位。
 * 缓冲固定 {@value #CAPACITY} 行，写满后覆盖最老的槽位；被覆盖说明调用方拿到的窗口不完整，
 * 由 {@link Window#truncated()} 显式告知，不静默丢数据。</p>
 *
 * <p>本类不 import 任何 {@code net.minecraft} 类型以外的游戏状态，也不持有
 * {@code Minecraft}/{@code Level}/{@code Player} 引用；只在事件回调里读一次
 * {@code event.getMessage().getString()}，不缓存 Component（Component 不是线程安全的）。</p>
 */
public final class ChatCapture {

    /** 环形缓冲容量（卡要求 ~200）。 */
    public static final int CAPACITY = 200;

    private static final Logger LOG = LoggerFactory.getLogger("cipbridge");

    private static final ChatCapture INSTANCE = new ChatCapture();

    /** 槽位文本（下标 = (seq-1) % CAPACITY）。 */
    private final String[] texts = new String[CAPACITY];

    /** 槽位到达的墙钟毫秒（用于判断"已静默多久"）。 */
    private final long[] atMillis = new long[CAPACITY];

    /** 所有字段的监视器：写入与读取都必须持有它。 */
    private final Object lock = new Object();

    /** 累计写入行数（单调递增，同时也是最后一行的序号）。受 {@link #lock} 保护。 */
    private long written;

    /** 事件监听是否已挂上（幂等保护）。 */
    private volatile boolean installed;

    private ChatCapture() {
    }

    /** 全局单例：事件总线只应订阅这一个实例，避免多份缓冲。 */
    public static ChatCapture get() {
        return INSTANCE;
    }

    /**
     * 把 {@link ClientChatReceivedEvent} 监听挂到 {@code NeoForge.EVENT_BUS}（只订阅一次）。
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
            NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent event) -> onChat(event));
            installed = true;
        }
    }

    /** 事件回调（客户端线程）。任何异常都不能污染事件总线分发。 */
    private void onChat(ClientChatReceivedEvent event) {
        if (event == null) {
            return;
        }
        try {
            append(event.getMessage().getString());
        } catch (RuntimeException e) {
            // 组件取值失败只丢这一行；不能抛回事件总线（会打断其它 mod 的聊天处理）
            LOG.debug("[cipbridge] dropped a chat line that failed to flatten", e);
        }
    }

    /**
     * 取当前序号（"从现在开始看"的锚点）。必须在发送命令<b>之前</b>调用。
     *
     * <p>返回值是已写入的行数；之后写入的行序号都大于它，因此不会漏掉窗口第一行。</p>
     */
    public long mark() {
        synchronized (lock) {
            return written;
        }
    }

    /** 序号 &gt; {@code marker} 的所有行，按到达顺序返回。 */
    public Window since(long marker) {
        synchronized (lock) {
            long total = written;
            long firstRetained = Math.max(1L, total - CAPACITY + 1L);
            long from = Math.max(marker + 1L, firstRetained);
            boolean truncated = marker + 1L < firstRetained;
            if (from > total) {
                return new Window(List.of(), truncated);
            }
            List<Line> lines = new ArrayList<>((int) (total - from + 1L));
            for (long seq = from; seq <= total; seq++) {
                int index = (int) ((seq - 1L) % CAPACITY);
                lines.add(new Line(atMillis[index], texts[index]));
            }
            return new Window(List.copyOf(lines), truncated);
        }
    }

    /** 序号 &gt; {@code marker} 的行数（等待窗口轮询用，避免每次复制整个窗口）。 */
    public int countSince(long marker) {
        synchronized (lock) {
            long total = written;
            long retained = Math.min(total, CAPACITY);
            long oldest = total - retained + 1L;
            long from = Math.max(marker + 1L, oldest);
            if (from > total) {
                return 0;
            }
            return (int) (total - from + 1L);
        }
    }

    /** 最后一行到达的墙钟毫秒；缓冲为空返回 0。 */
    public long lastAtMillis() {
        synchronized (lock) {
            if (written == 0L) {
                return 0L;
            }
            return atMillis[(int) ((written - 1L) % CAPACITY)];
        }
    }

    /** 清空缓冲（仅测试/调试用；运行期不需要）。 */
    public void clear() {
        synchronized (lock) {
            for (int i = 0; i < CAPACITY; i++) {
                texts[i] = null;
                atMillis[i] = 0L;
            }
            written = 0L;
        }
    }

    private void append(String text) {
        if (text == null) {
            return;
        }
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return;
        }
        synchronized (lock) {
            int index = (int) (written % CAPACITY);
            texts[index] = normalized;
            atMillis[index] = System.currentTimeMillis();
            written++;
        }
    }

    /** 一行聊天：到达时间 + 已扁平化的纯文本。 */
    public record Line(long atMillis, String text) {
    }

    /** 一个窗口切片：行 + 是否因环形缓冲覆盖而丢失了窗口内的行。 */
    public record Window(List<Line> lines, boolean truncated) {

        /** 只取文本（HTTP 响应只需要文本）。 */
        public List<String> texts() {
            List<String> out = new ArrayList<>(lines.size());
            for (Line line : lines) {
                out.add(line.text());
            }
            return List.copyOf(out);
        }
    }
}
