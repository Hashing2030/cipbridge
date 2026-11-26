package dev.cip.bridge.exec;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * 线程桥（SPEC v1.1 §2.4 / §6）：把世界访问投递到正确的游戏线程。
 *
 * <p><b>铁律</b>：HTTP 线程（{@code cipbridge-http-*}）永远不直接读 level。任何需要世界的操作
 * 都必须包成 {@link Supplier} 交给本类：</p>
 * <ul>
 *   <li>{@link #onServer(Supplier)}：单机存档 → {@code IntegratedServer.submit(...)}（服务端线程）；</li>
 *   <li>无单机服务器（多人游戏 / 未进入世界）→ 退回 {@link #onClient(Supplier)}（客户端线程）；</li>
 *   <li>若已经在目标线程上调用，直接内联执行，避免自己等自己造成死锁。</li>
 * </ul>
 *
 * <p>返回的 {@link CompletableFuture} 由调用方用 {@link #await(CompletableFuture)} 或
 * {@link #await(CompletableFuture, long)} 取结果，超时抛 {@link BridgeException}（{@code timeout=true}），
 * 便于 HTTP 层映射成 504/500，而不是把 HTTP 线程永久挂住。</p>
 *
 * <p>线程安全：本类无状态。</p>
 */
public final class ThreadBridge {

    /** 默认等待上限：/snapshot 最坏 10 s（SPEC §6）。 */
    public static final long DEFAULT_TIMEOUT_MS = 10_000L;

    private ThreadBridge() {
    }

    /** 投递到客户端线程。必须在客户端环境调用。 */
    public static <T> CompletableFuture<T> onClient(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            throw new BridgeException("Minecraft client is not available yet", false);
        }
        if (minecraft.isSameThread()) {
            return inline(task);
        }
        return minecraft.submit(task);
    }

    /** 投递到服务端线程；没有单机服务器时退回客户端线程。 */
    public static <T> CompletableFuture<T> onServer(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        IntegratedServer server = currentServer();
        if (server == null) {
            return onClient(task);
        }
        if (server.isSameThread()) {
            return inline(task);
        }
        return server.submit(task);
    }

    /** {@link #onServer(Supplier)} 的无返回值版本。 */
    public static CompletableFuture<Void> onServer(Runnable task) {
        Objects.requireNonNull(task, "task");
        return onServer(() -> {
            task.run();
            return null;
        });
    }

    /** {@link #onClient(Supplier)} 的无返回值版本。 */
    public static CompletableFuture<Void> onClient(Runnable task) {
        Objects.requireNonNull(task, "task");
        return onClient(() -> {
            task.run();
            return null;
        });
    }

    /** 当前是否存在可用的单机服务器（未进入单人世界时为 null）。 */
    public static IntegratedServer currentServer() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.getSingleplayerServer();
    }

    /** 等待结果，超时 {@value #DEFAULT_TIMEOUT_MS} ms。 */
    public static <T> T await(CompletableFuture<T> future) {
        return await(future, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 等待结果，超时后取消任务并抛 {@link BridgeException}。
     *
     * <p>若任务本身抛了 RuntimeException，会被原样重新抛出（保留游戏侧异常类型）；
     * 其它 Throwable 包成 {@link BridgeException}。</p>
     */
    public static <T> T await(CompletableFuture<T> future, long timeoutMs) {
        Objects.requireNonNull(future, "future");
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be > 0: " + timeoutMs);
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BridgeException("timed out after " + timeoutMs + " ms", true, e);
        } catch (CancellationException e) {
            throw new BridgeException("task was cancelled", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BridgeException("interrupted while waiting for game thread", true, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new BridgeException("task failed: " + cause, false, cause);
        }
    }

    private static <T> CompletableFuture<T> inline(Supplier<T> task) {
        try {
            return CompletableFuture.completedFuture(task.get());
        } catch (RuntimeException e) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        } catch (Error e) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /** 桥接层异常：{@link #isTimeout()} 区分"等超时"与"任务失败"。 */
    public static final class BridgeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final boolean timeout;

        public BridgeException(String message, boolean timeout) {
            super(message);
            this.timeout = timeout;
        }

        public BridgeException(String message, boolean timeout, Throwable cause) {
            super(message, cause);
            this.timeout = timeout;
        }

        public boolean isTimeout() {
            return timeout;
        }
    }
}
