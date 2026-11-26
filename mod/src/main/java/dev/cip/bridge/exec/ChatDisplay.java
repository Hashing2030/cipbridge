package dev.cip.bridge.exec;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 把一行文本显示到玩家聊天框（CB-14，SPEC v1.1 §4.7）。
 *
 * <p><b>这是本卡唯一的"显示边界"</b>：{@code /chat-say} 的处理器（{@code http.ChatSayHandler}）
 * 与聊天捕获（{@link ChatListener}）都只通过本类碰 {@code Minecraft}/{@code LocalPlayer}，
 * 于是"HTTP 线程零 MC 访问"可以用一条 grep 审阅——`ChatSayHandler` 的常量池里没有任何
 * {@code net/minecraft}，真正的 MC 调用只出现在这里，并且只经
 * {@link ThreadBridge#onClient(java.util.function.Supplier)} 在客户端线程执行。</p>
 *
 * <p>不缓存 {@code Minecraft}/{@code LocalPlayer}/{@code Component}：每次调用现取，
 * 因为组件不是线程安全的，且玩家会换世界/退出。</p>
 */
public final class ChatDisplay {

    private ChatDisplay() {
    }

    /**
     * 在客户端线程把文本显示到聊天框（不是 actionbar，第二个参数恒 {@code false}）。
     *
     * <p><b>只允许在客户端线程调用</b>（{@link ThreadBridge#onClient} 内部），
     * 本类自身不做线程判断，避免把"投递"这件事在两个地方各实现一遍。</p>
     *
     * @param text 已校验/已截断的纯文本（非 {@code null}）
     * @return {@code true} 已显示；{@code false} 还没进世界（{@code Minecraft} 或 player 为 {@code null}），
     *         调用方据此回 503 {@code not_ingame}
     */
    public static boolean showToPlayer(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return false;
        }
        minecraft.player.displayClientMessage(Component.literal(text), false);
        return true;
    }
}
