package dev.cip.bridge.http;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * CIP Bridge 的本地 HTTP 服务骨架（SPEC v1.1 §4 / §2）。
 *
 * <p>职责：监听 {@code 127.0.0.1}、路由分发、token 鉴权、滑动窗口限流、统一错误 JSON、
 * 请求日志钩子。具体端点（/state、/cmd、/snapshot…）由后续任务卡通过
 * {@link #route(String, String, Handler)} 注入，本类<b>不</b> import 任何 Minecraft 类，
 * 因此可以在纯 JDK 进程里自测（见 {@code dev.cip.bridge.dev.HttpApiSmoke}）。</p>
 *
 * <p>硬约束（SPEC §2）：只绑 {@code 127.0.0.1}，绝不监听 {@code 0.0.0.0}；HTTP 线程绝不直接
 * 访问 level——处理器需要世界数据时必须走 {@code dev.cip.bridge.exec.ThreadBridge} 投递到
 * 服务端线程。</p>
 *
 * <p>请求处理顺序：解析 → token 校验（403）→ 限流（429）→ 路由（404 / 405）→ 处理器。
 * 鉴权先于限流是刻意的：未授权请求不消耗配额，验收判定确定。</p>
 */
public final class HttpApi implements AutoCloseable {

    /** 鉴权请求头名（SPEC §4 通用）。 */
    public static final String TOKEN_HEADER = "X-CIP-Token";

    /** 唯一允许监听的地址（SPEC §2.5）。 */
    public static final String LOOPBACK_HOST = "127.0.0.1";

    /** 端口冲突时最多尝试的端口个数（SPEC §3：冲突自动 +1 重试至多 10 次）。 */
    public static final int PORT_RETRIES = 10;

    /** 请求体上限（/batch 等 POST 用），1 MiB。 */
    private static final int MAX_BODY_BYTES = 1 << 20;

    /** 限流滑动窗口长度：1 秒。 */
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

    /** 单端点处理器。返回 {@link Response} 即可支持 JSON 与（CB-03 的）ZIP 二进制。 */
    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }

    /** 处理器可抛出该异常直接指定 HTTP 状态码与错误码。 */
    public static final class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /** 一次请求的只读视图（不含任何 MC 类型）。 */
    public static final class Request {
        private final String method;
        private final String path;
        private final String rawQuery;
        private final Map<String, String> query;
        private final Map<String, String> headers;
        private final byte[] body;
        private final String remote;

        private Request(String method, String path, String rawQuery, Map<String, String> query,
                        Map<String, String> headers, byte[] body, String remote) {
            this.method = method;
            this.path = path;
            this.rawQuery = rawQuery;
            this.query = query;
            this.headers = headers;
            this.body = body;
            this.remote = remote;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public String rawQuery() {
            return rawQuery;
        }

        /** 解码后的查询参数（同名参数取第一个）。 */
        public Map<String, String> query() {
            return query;
        }

        /** 查询参数原始值，缺省返回 {@code null}。 */
        public String query(String name) {
            return query.get(name);
        }

        /** 查询参数按整数解析；缺省用 {@code fallback}，非法值 → 400。 */
        public int queryInt(String name, int fallback) {
            String value = query.get(name);
            if (value == null || value.isEmpty()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "bad_request",
                        "query parameter '" + name + "' is not an integer: " + value);
            }
        }

        /** 请求头（大小写不敏感，取第一个值）。 */
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        /** 请求体字节（仅 POST/PUT/PATCH 读取，上限 1 MiB）。 */
        public byte[] body() {
            return body;
        }

        public String remote() {
            return remote;
        }
    }

    /** 响应：状态码 + Content-Type + 字节体。 */
    public static final class Response {
        public static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

        private final int status;
        private final String contentType;
        private final byte[] body;

        private Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String contentType() {
            return contentType;
        }

        public byte[] body() {
            return body;
        }

        /** 200 JSON。 */
        public static Response json(JsonObject json) {
            return json(200, json);
        }

        /** 指定状态码的 JSON。 */
        public static Response json(int status, JsonObject json) {
            return new Response(status, CONTENT_TYPE_JSON,
                    Json.toJson(Objects.requireNonNull(json, "json")).getBytes(StandardCharsets.UTF_8));
        }

        /** 200 的任意二进制（CB-03 的 /snapshot ZIP 用）。 */
        public static Response bytes(byte[] body, String contentType) {
            return new Response(200, contentType, Objects.requireNonNull(body, "body"));
        }

        /** 统一错误 JSON：{@code {"ok":false,"error":"...","code":"..."}}（SPEC §4 通用）。 */
        public static Response error(int status, String code, String message) {
            JsonObject json = new JsonObject();
            json.addProperty("ok", false);
            json.addProperty("error", message == null ? "" : message);
            json.addProperty("code", code);
            return json(status, json);
        }
    }

    private final int basePort;
    private final byte[] tokenBytes;
    private final int rateLimit;
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final SlidingWindowRateLimiter rateLimiter;
    private final Object startStopLock = new Object();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile Consumer<String> logger = line -> {
    };
    private volatile long startedAtNanos = System.nanoTime();

    /**
     * @param port            首选端口（被占用时 +1 重试至多 {@value #PORT_RETRIES} 次）
     * @param token           鉴权 token，不可空；生成/落盘由调用方负责（CipBridge）
     * @param rateLimitPerSecond 每秒请求数上限；{@code <= 0} 表示不限流
     */
    public HttpApi(int port, String token, int rateLimitPerSecond) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("token must not be empty");
        }
        this.basePort = port;
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        this.rateLimit = rateLimitPerSecond;
        this.rateLimiter = new SlidingWindowRateLimiter(rateLimitPerSecond);
        // /ping 不碰 Minecraft，故意放在这里注册：即使 CB-03 注入失败也能探活。
        route("GET", "/ping", this::handlePing);
    }

    /**
     * 注册路由。路径须以 {@code /} 开头且为精确匹配（本模组端点是固定路径）。
     * 同一 method+path 重复注册会覆盖旧处理器。
     */
    public HttpApi route(String method, String path, Handler handler) {
        Objects.requireNonNull(handler, "handler");
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("method must not be empty");
        }
        routes.put(routeKey(method, path), handler);
        return this;
    }

    /** 请求日志钩子（时间/来源/参数/耗时由调用方决定格式）；传 null 表示静默。 */
    public void setLogger(Consumer<String> requestLogger) {
        this.logger = requestLogger == null ? line -> {
        } : requestLogger;
    }

    /**
     * 启动服务，返回实际绑定端口。
     *
     * <p>端口冲突（{@link BindException}）时依次尝试 {@code port+1 ... port+9}；全部失败则抛最后一次异常。</p>
     */
    public int start() throws IOException {
        synchronized (startStopLock) {
            if (server != null) {
                throw new IllegalStateException("HTTP server already started");
            }
            InetAddress loopback = InetAddress.getByName(LOOPBACK_HOST);
            IOException lastFailure = null;
            HttpServer created = null;
            int boundPort = -1;
            for (int attempt = 0; attempt < PORT_RETRIES; attempt++) {
                int candidate = basePort + attempt;
                if (candidate > 65535) {
                    break;
                }
                try {
                    created = HttpServer.create(new InetSocketAddress(loopback, candidate), 0);
                    boundPort = candidate;
                    break;
                } catch (BindException e) {
                    lastFailure = e;
                }
            }
            if (created == null) {
                throw lastFailure != null ? lastFailure
                        : new IOException("no free port in " + basePort + ".." + (basePort + PORT_RETRIES - 1));
            }
            ExecutorService pool = Executors.newCachedThreadPool(daemonThreadFactory("cipbridge-http"));
            created.setExecutor(pool);
            created.createContext("/", this::dispatch);
            created.start();
            this.executor = pool;
            this.server = created;
            this.startedAtNanos = System.nanoTime();
            log("listening on " + LOOPBACK_HOST + ":" + boundPort);
            return boundPort;
        }
    }

    /** 实际绑定端口；未启动返回 -1。 */
    public int port() {
        HttpServer current = server;
        return current == null ? -1 : current.getAddress().getPort();
    }

    public boolean isRunning() {
        return server != null;
    }

    /** 服务启动至今的毫秒数（/ping 用；纯 JDK，不碰任何游戏对象）。 */
    public long uptimeMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** 停止服务（幂等，可在任意线程调用）。 */
    public void stop() {
        synchronized (startStopLock) {
            HttpServer current = this.server;
            this.server = null;
            if (current != null) {
                current.stop(0);
                log("stopped");
            }
            ExecutorService pool = this.executor;
            this.executor = null;
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    // ────────────────────────────── 内部分发 ──────────────────────────────

    private void dispatch(HttpExchange exchange) {
        long began = System.nanoTime();
        String method = "?";
        String path = "?";
        String remote = "?";
        int status = 500;
        try {
            Request request = parse(exchange);
            method = request.method();
            path = request.path();
            remote = request.remote();

            if (!tokenMatches(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                status = 403;
                send(exchange, Response.error(403, "forbidden", "missing or invalid " + TOKEN_HEADER));
                return;
            }
            if (!rateLimiter.tryAcquire()) {
                status = 429;
                exchange.getResponseHeaders().set("Retry-After", "1");
                send(exchange, Response.error(429, "rate_limited",
                        "exceeded " + rateLimit + " request(s) per second"));
                return;
            }

            Handler handler = routes.get(routeKey(method, path));
            if (handler == null) {
                final String lookupPath = path;
                boolean pathExists = routes.keySet().stream()
                        .anyMatch(key -> key.substring(key.indexOf(' ') + 1).equals(lookupPath));
                status = pathExists ? 405 : 404;
                Response error = pathExists
                        ? Response.error(405, "method_not_allowed", method + " not allowed on " + path)
                        : Response.error(404, "not_found", "no route for " + path);
                send(exchange, error);
                return;
            }

            Response response = handler.handle(request);
            status = response.status();
            send(exchange, response);
        } catch (ApiException e) {
            status = e.status();
            sendQuietly(exchange, status, e.code(), e.getMessage());
        } catch (Exception e) {
            status = 500;
            sendQuietly(exchange, 500, "internal_error", "handler failed: " + e);
            log("ERROR while handling " + method + " " + path + ": " + e);
        } finally {
            long elapsedMs = (System.nanoTime() - began) / 1_000_000L;
            log(remote + " " + method + " " + path + " -> " + status + " (" + elapsedMs + " ms)");
            exchange.close();
        }
    }

    private Response handlePing(Request request) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        json.addProperty("mod", "cipbridge");
        json.addProperty("port", port());
        json.addProperty("uptime_ms", uptimeMs());
        return Response.json(json);
    }

    private static Request parse(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String rawQuery = uri.getRawQuery();
        Map<String, String> query = parseQuery(rawQuery);

        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });

        byte[] body = new byte[0];
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            body = readBody(exchange);
        }
        return new Request(method, path, rawQuery, Collections.unmodifiableMap(query),
                Collections.unmodifiableMap(headers), body, String.valueOf(exchange.getRemoteAddress()));
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > MAX_BODY_BYTES) {
                    throw new ApiException(413, "payload_too_large", "body exceeds " + MAX_BODY_BYTES + " bytes");
                }
            } catch (NumberFormatException ignored) {
                // 非法 Content-Length 交给下面的实际读取兜底
            }
        }
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new ApiException(413, "payload_too_large", "body exceeds " + MAX_BODY_BYTES + " bytes");
        }
        return body;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return parsed;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            parsed.putIfAbsent(name, value);
        }
        return parsed;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "bad_request", "malformed percent-encoding: " + raw);
        }
    }

    private boolean tokenMatches(String provided) {
        if (provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), tokenBytes);
    }

    private void send(HttpExchange exchange, Response response) throws IOException {
        byte[] body = response.body();
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    /** 仅在响应还没发出去时补一个错误体，避免 sendResponseHeaders 二次调用。 */
    private void sendQuietly(HttpExchange exchange, int status, String code, String message) {
        if (exchange.getResponseCode() != -1) {
            return;
        }
        try {
            send(exchange, Response.error(status, code, message));
        } catch (IOException ignored) {
            // 客户端已断开，无法回写
        }
    }

    private void log(String line) {
        Consumer<String> sink = logger;
        try {
            sink.accept(line);
        } catch (RuntimeException ignored) {
            // 日志后端出错不能影响请求处理
        }
    }

    private static String routeKey(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 滑动窗口限流（SPEC §3 rate_limit 默认 5 req/s）。 */
    private static final class SlidingWindowRateLimiter {
        private final int maxPerWindow;
        private final ArrayDeque<Long> accepted = new ArrayDeque<>();

        SlidingWindowRateLimiter(int maxPerWindow) {
            this.maxPerWindow = maxPerWindow;
        }

        synchronized boolean tryAcquire() {
            if (maxPerWindow <= 0) {
                return true;
            }
            long now = System.nanoTime();
            while (!accepted.isEmpty() && now - accepted.peekFirst() >= RATE_WINDOW_NANOS) {
                accepted.pollFirst();
            }
            if (accepted.size() >= maxPerWindow) {
                return false;
            }
            accepted.addLast(now);
            return true;
        }
    }
}
