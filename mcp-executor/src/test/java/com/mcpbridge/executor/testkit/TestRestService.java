package com.mcpbridge.executor.testkit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 桥接平台专用测试 REST 服务（假上游）。
 *
 * <p>在单元测试目录下实现、用 JDK 内置 {@link HttpServer} 起真实 HTTP 端口，零新增依赖：
 * 不拉 Spring 上下文，也不碰任何外部组件。它模拟一个「等着被桥接的存量业务系统」，
 * 用于验证桥接平台的端到端链路——而不是像多数单测那样只做白盒断言。
 *
 * <h2>两个业务域</h2>
 * 内置 order（订单）与 user（用户）两个独立域，各暴露一份 OpenAPI 3.0 文档
 * （见 {@link OpenApiDocs}）。同一 MCP Server 下注册两份文档即可演示
 * 「一个 Server 聚合多个 REST 服务、同名 tool 自动加服务前缀」的多上游语义。
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * try (TestRestService upstream = new TestRestService(0).start()) {
 *     // 注册/发布时把 baseUrls 指向 upstream.baseUrl()
 *     // 自动化测试直接拿 snapshot 配 baseUrl 后经 UpstreamInvoker 打真实 HTTP
 *     upstream.failNext(1, 503);          // 注入一次 503，验证平台重试/熔断
 *     upstream.latency(Duration.ofMillis(800));  // 注入延迟，验证平台超时
 *     List<CapturedRequest> hits = upstream.captured(); // 断言平台透传的 method/path/query/headers/body
 * }
 * }</pre>
 *
 * <h2>手工/UI 验收</h2>
 * 在 IDE 里直接运行 {@link #main}（默认 18080，可传端口参数），
 * 然后在平台「注册文档」里填 {@code http://127.0.0.1:18080/v3/api-docs?scope=order}。
 * 注意 manager 默认有 SSRF 防护、拒绝回环地址，需设
 * {@code MCP_MANAGER_PARSE_ALLOW_PRIVATE_NETWORKS=true} 后才能按 URL 注册本地文档
 * （或直接粘贴 {@link OpenApiDocs#order()} 的内容）。
 */
public final class TestRestService implements AutoCloseable {

    /** 常驻模式默认端口（与文档里 servers.url 一致）。 */
    public static final int DEFAULT_PORT = 18080;

    /** 一次被捕获的上游请求（用于断言桥接层透传正确）。 */
    public record CapturedRequest(
            String method,
            String path,
            String rawQuery,
            Map<String, String> headers,
            String body,
            long atMillis) {
    }

    private static final Set<String> ORDER_STATUSES = Set.of("CREATED", "PAID", "SHIPPED", "CANCELLED");

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final AtomicLong nextOrderId = new AtomicLong(1000);
    private final AtomicLong nextUserId = new AtomicLong(1000);
    private final Map<Long, Map<String, Object>> orders = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> users = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<CapturedRequest> captured = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Integer> pendingFaults = new ConcurrentLinkedQueue<>();
    private volatile long latencyMillis;

    /** 随机端口（port=0）或指定端口。用 {@link #start()} 启动。 */
    public TestRestService(int port) {
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            this.server.setExecutor(Executors.newCachedThreadPool(daemonThreads()));
            this.server.createContext("/", this::dispatch);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        seed();
    }

    /** 默认端口 {@link #DEFAULT_PORT}。 */
    public TestRestService() {
        this(DEFAULT_PORT);
    }

    public TestRestService start() {
        server.start();
        return this;
    }

    /** 实际监听端口（随机端口时在此查询）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 自动化测试把 snapshot 的上游 baseUrls 指向这里。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    // ------------------------------------------------------------------ 行为注入

    /** 让接下来每个请求先睡这么久再处理（测平台超时/慢上游）。传 null 或 0 关闭。 */
    public TestRestService latency(Duration latency) {
        this.latencyMillis = latency == null ? 0 : latency.toMillis();
        return this;
    }

    /** 让接下来 count 个业务请求直接返回指定状态码（测平台重试/熔断）。 */
    public TestRestService failNext(int count, int status) {
        for (int i = 0; i < count; i++) {
            pendingFaults.add(status);
        }
        return this;
    }

    /** 清空延迟、故障注入与请求记录，回到干净状态。 */
    public TestRestService reset() {
        pendingFaults.clear();
        latencyMillis = 0;
        captured.clear();
        return this;
    }

    /** 已捕获的上游请求（按到达顺序）。 */
    public List<CapturedRequest> captured() {
        return List.copyOf(captured);
    }

    public int capturedCount() {
        return captured.size();
    }

    // ------------------------------------------------------------------ 数据访问（断言与预置用）

    public Map<Long, Map<String, Object>> orders() {
        return Map.copyOf(orders);
    }

    public Map<Long, Map<String, Object>> users() {
        return Map.copyOf(users);
    }

    /** 预置一条订单（id=1）与一条用户（id=1），便于开箱即测详情/更新/删除。 */
    private void seed() {
        orders.put(1L, order(1L, 7L, 99.5, "PAID"));
        users.put(1L, user(1L, "alice", "alice@example.com"));
    }

    // ------------------------------------------------------------------ 路由与分发

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            if (latencyMillis > 0) {
                try {
                    Thread.sleep(latencyMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase();
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            capture(exchange, rawBody);

            // 故障注入：优先级最高，业务端点与文档端点都先被拦（重试/熔断只对业务有意义）
            Integer fault = pendingFaults.poll();
            if (fault != null && (path.startsWith("/api/") || path.startsWith("/v3/api-docs"))) {
                sendJson(exchange, fault, Map.of("code", "UPSTREAM_FAULT",
                        "message", "TestRestService 注入故障", "status", fault));
                return;
            }

            if ("GET".equals(method) && "/healthz".equals(path)) {
                sendJson(exchange, 200, Map.of("status", "ok"));
                return;
            }
            if ("GET".equals(method) && "/v3/api-docs".equals(path)) {
                serveDocs(exchange, exchange.getRequestURI().getRawQuery());
                return;
            }
            if (path.startsWith(OpenApiDocs.ORDER_PATH_PREFIX)) {
                routeOrders(exchange, method, path, rawBody);
                return;
            }
            if (path.startsWith(OpenApiDocs.USER_PATH_PREFIX)) {
                routeUsers(exchange, method, path, rawBody);
                return;
            }
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND",
                    "message", "未知路由: " + method + " " + path));
        } finally {
            exchange.close();
        }
    }

    private void serveDocs(HttpExchange exchange, String rawQuery) throws IOException {
        Map<String, List<String>> query = parseQuery(rawQuery);
        String scope = first(query, "scope", "order");
        String doc = "user".equalsIgnoreCase(scope) ? OpenApiDocs.user() : OpenApiDocs.order();
        byte[] body = doc.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    // ------------------------------------------------------------------ order 域

    private void routeOrders(HttpExchange exchange, String method, String path, byte[] rawBody) throws IOException {
        if ("/api/orders".equals(path)) {
            if ("GET".equals(method)) {
                listOrders(exchange, exchange.getRequestURI().getRawQuery());
                return;
            }
            if ("POST".equals(method)) {
                createOrder(exchange, rawBody);
                return;
            }
            sendJson(exchange, 405, Map.of("code", "METHOD_NOT_ALLOWED", "message", "订单列表仅支持 GET/POST"));
            return;
        }
        String rest = path.substring(OpenApiDocs.ORDER_PATH_PREFIX.length());
        if (!rest.startsWith("/") || rest.length() <= 1) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未知订单路由: " + path));
            return;
        }
        Long orderId = parseId(exchange, rest.substring(1));
        if (orderId == null) {
            return;
        }
        switch (method) {
            case "GET" -> sendOrder(exchange, orderId);
            case "PUT" -> updateOrder(exchange, orderId, rawBody);
            case "DELETE" -> deleteOrder(exchange, orderId);
            default -> sendJson(exchange, 405,
                    Map.of("code", "METHOD_NOT_ALLOWED", "message", "订单详情仅支持 GET/PUT/DELETE"));
        }
    }

    private void listOrders(HttpExchange exchange, String rawQuery) throws IOException {
        Map<String, List<String>> query = parseQuery(rawQuery);
        String statusFilter = first(query, "status", null);
        int page = Math.max(1, intOf(first(query, "page", "1")));
        int size = Math.max(1, Math.min(100, intOf(first(query, "size", "20"))));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> order : orders.values()) {
            if (statusFilter == null || statusFilter.equalsIgnoreCase((String) order.get("status"))) {
                items.add(order);
            }
        }
        items.sort((a, b) -> Long.compare((Long) b.get("id"), (Long) a.get("id")));
        int from = Math.min((page - 1) * size, items.size());
        int to = Math.min(from + size, items.size());

        sendJson(exchange, 200, Map.of(
                "items", items.subList(from, to),
                "total", (long) items.size(),
                "page", page,
                "size", size));
    }

    private void createOrder(HttpExchange exchange, byte[] rawBody) throws IOException {
        Map<String, Object> body = parseBody(exchange, rawBody);
        if (body == null) {
            return;
        }
        if (!body.containsKey("customerId") || !body.containsKey("amount")) {
            sendJson(exchange, 400, Map.of("code", "BAD_REQUEST",
                    "message", "缺少必填字段 customerId / amount"));
            return;
        }
        long id = nextOrderId.getAndIncrement();
        Map<String, Object> order = order(id, ((Number) body.get("customerId")).longValue(),
                ((Number) body.get("amount")).doubleValue(), "CREATED");
        orders.put(id, order);
        exchange.getResponseHeaders().set("Location", "/api/orders/" + id);
        sendJson(exchange, 201, order);
    }

    private void updateOrder(HttpExchange exchange, Long orderId, byte[] rawBody) throws IOException {
        if (!orders.containsKey(orderId)) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "订单 " + orderId + " 不存在"));
            return;
        }
        Map<String, Object> body = parseBody(exchange, rawBody);
        if (body == null) {
            return;
        }
        String status = String.valueOf(body.get("status"));
        if (!ORDER_STATUSES.contains(status)) {
            sendJson(exchange, 400, Map.of("code", "BAD_REQUEST",
                    "message", "status 必须是 " + ORDER_STATUSES));
            return;
        }
        orders.get(orderId).put("status", status);
        sendJson(exchange, 200, orders.get(orderId));
    }

    private void deleteOrder(HttpExchange exchange, Long orderId) throws IOException {
        if (orders.remove(orderId) == null) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "订单 " + orderId + " 不存在"));
            return;
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void sendOrder(HttpExchange exchange, Long orderId) throws IOException {
        Map<String, Object> order = orders.get(orderId);
        if (order == null) {
            sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "订单 " + orderId + " 不存在"));
            return;
        }
        sendJson(exchange, 200, order);
    }

    // ------------------------------------------------------------------ user 域

    private void routeUsers(HttpExchange exchange, String method, String path, byte[] rawBody) throws IOException {
        if ("/api/users".equals(path) && "POST".equals(method)) {
            createUser(exchange, rawBody);
            return;
        }
        String rest = path.substring(OpenApiDocs.USER_PATH_PREFIX.length());
        if (rest.startsWith("/") && rest.length() > 1) {
            Long userId = parseId(exchange, rest.substring(1));
            if (userId == null) {
                return;
            }
            if ("GET".equals(method)) {
                Map<String, Object> user = users.get(userId);
                if (user == null) {
                    sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "用户 " + userId + " 不存在"));
                } else {
                    sendJson(exchange, 200, user);
                }
                return;
            }
        }
        sendJson(exchange, 404, Map.of("code", "NOT_FOUND", "message", "未知用户路由: " + method + " " + path));
    }

    private void createUser(HttpExchange exchange, byte[] rawBody) throws IOException {
        Map<String, Object> body = parseBody(exchange, rawBody);
        if (body == null) {
            return;
        }
        if (!body.containsKey("name") || !body.containsKey("email")) {
            sendJson(exchange, 400, Map.of("code", "BAD_REQUEST",
                    "message", "缺少必填字段 name / email"));
            return;
        }
        long id = nextUserId.getAndIncrement();
        Map<String, Object> user = user(id, String.valueOf(body.get("name")), String.valueOf(body.get("email")));
        users.put(id, user);
        exchange.getResponseHeaders().set("Location", "/api/users/" + id);
        sendJson(exchange, 201, user);
    }

    // ------------------------------------------------------------------ 基础工具

    private void capture(HttpExchange exchange, byte[] rawBody) {
        captured.add(new CapturedRequest(
                exchange.getRequestMethod().toUpperCase(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestURI().getRawQuery(),
                exchange.getRequestHeaders().entrySet().stream().collect(
                        LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey().toLowerCase(), String.join(", ", e.getValue())),
                        Map::putAll),
                rawBody == null || rawBody.length == 0 ? null : new String(rawBody, StandardCharsets.UTF_8),
                System.currentTimeMillis()));
    }

    private Map<String, Object> parseBody(HttpExchange exchange, byte[] rawBody) {
        if (rawBody.length == 0) {
            sendJsonQuietly(exchange, 400, Map.of("code", "BAD_REQUEST", "message", "请求体为空"));
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = json.readValue(rawBody, LinkedHashMap.class);
            return map;
        } catch (IOException e) {
            sendJsonQuietly(exchange, 400, Map.of("code", "BAD_REQUEST",
                    "message", "请求体不是合法 JSON: " + e.getMessage()));
            return null;
        }
    }

    private Long parseId(HttpExchange exchange, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            sendJsonQuietly(exchange, 400, Map.of("code", "BAD_REQUEST",
                    "message", "路径参数必须为数字，实际: " + raw));
            return null;
        }
    }

    private Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = decode(eq < 0 ? "" : pair.substring(eq + 1));
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String first(Map<String, List<String>> query, String key, String fallback) {
        List<String> values = query.get(key);
        return values == null || values.isEmpty() ? fallback : values.get(0);
    }

    private static int intOf(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** 在无法向上抛（参数解析失败分支已处于处理中）时兜底写响应，吞掉二次 IO 异常。 */
    private void sendJsonQuietly(HttpExchange exchange, int status, Object body) {
        try {
            sendJson(exchange, status, body);
        } catch (IOException ignored) {
            // 已尽力响应；连接层异常在上层 finally 关闭时自然处理
        }
    }

    private static Map<String, Object> order(long id, long customerId, double amount, String status) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", id);
        order.put("customerId", customerId);
        order.put("amount", amount);
        order.put("status", status);
        order.put("createdAt", Instant.now().toString());
        return order;
    }

    private static Map<String, Object> user(long id, String name, String email) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("name", name);
        user.put("email", email);
        user.put("createdAt", Instant.now().toString());
        return user;
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "test-rest-service");
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * 手工/UI 验收入口：常驻启动，默认 18080，可用第一个参数覆盖端口。
     * 启动后打印业务端点与文档地址，Ctrl-C 退出。
     */
    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        try (TestRestService upstream = new TestRestService(port).start()) {
            System.out.println("TestRestService 已启动（MCP 桥接平台假上游）：");
            System.out.println("  业务端点   " + upstream.baseUrl() + "/api/orders/{id}");
            System.out.println("  order 文档 " + upstream.baseUrl() + "/v3/api-docs?scope=order");
            System.out.println("  user  文档 " + upstream.baseUrl() + "/v3/api-docs?scope=user");
            System.out.println("  健康检查   " + upstream.baseUrl() + "/healthz");
            System.out.println("按 Ctrl-C 退出");
            new CountDownLatch(1).await();
        }
    }
}
