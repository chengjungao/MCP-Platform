package com.mcpbridge.common.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tool 命名与锚点规则（BR-1 / BR-2）。
 *
 * <ul>
 *   <li>默认名取 {@code operationId}；缺省时按 {@code HTTP方法_路径段} 生成，例：{@code GET /users/{id}} → {@code get_users_id}</li>
 *   <li>路径参数拼入名称以保证同一 Server 内唯一</li>
 *   <li>锚点（anchor）= {@code method + " " + path}，是 overlay 的定位依据；
 *       上游文档升级导致锚点消失时，overlay 进入挂起区而不是静默丢弃（REG-03）</li>
 * </ul>
 */
public final class ToolNames {

    /** MCP 规范对 tool 名的字符约束。 */
    public static final Pattern VALID_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[^a-zA-Z0-9]+");

    public static final int MAX_LENGTH = 64;

    private ToolNames() {
    }

    /** 生成 overlay 锚点，例如 {@code GET /users/{id}}。 */
    public static String anchor(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    /**
     * 把任意字符串规范化为合法 tool 名（保留 operationId 的驼峰可读性）；无法规范化时返回 null。
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // 路径参数 {id} → _id_，其余非法字符折叠为下划线
        String cleaned = raw.trim()
                .replace("{", "_")
                .replace("}", "_");
        cleaned = ILLEGAL_CHARS.matcher(cleaned).replaceAll("_");
        cleaned = cleaned.replaceAll("_+", "_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

    /** {@code GET /users/{id}} → {@code get_users_id}。 */
    public static String fromMethodAndPath(String method, String path) {
        String methodPart = sanitize(method == null ? null : method.toLowerCase(Locale.ROOT));
        String pathPart = sanitize(path == null ? "" : path.toLowerCase(Locale.ROOT).replaceAll("[/]+", "_"));
        if (methodPart == null && pathPart == null) {
            return null;
        }
        String joined = (methodPart == null ? "" : methodPart)
                + (methodPart != null && pathPart != null ? "_" : "")
                + (pathPart == null ? "" : pathPart);
        return sanitize(joined);
    }

    /** 优先 operationId，缺省则回退到 method+path 规则。 */
    public static String derive(String operationId, String method, String path) {
        String fromOperationId = sanitize(operationId);
        if (fromOperationId != null) {
            return fromOperationId;
        }
        return fromMethodAndPath(method, path);
    }

    /**
     * 保证同一 Server 内 tool 名唯一：冲突时追加 {@code _2}、{@code _3} …
     *
     * @param candidate 候选名
     * @param used      已占用的名字集合（调用方负责把返回值加入其中）
     */
    public static String unique(String candidate, Set<String> used) {
        String base = candidate == null ? "tool" : candidate;
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String suffix = "_" + i;
            String trimmed = base.length() + suffix.length() > MAX_LENGTH
                    ? base.substring(0, MAX_LENGTH - suffix.length())
                    : base;
            String next = trimmed + suffix;
            if (!used.contains(next)) {
                return next;
            }
        }
        throw new IllegalStateException("无法为 tool 生成唯一名称: " + candidate);
    }

    public static boolean isValid(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /**
     * 多服务前缀拼接：{@code <serviceId>_<baseName>}（如 {@code order_getUser}）。
     * <p>用于一个 Server 挂多份 Swagger 时避免同名 tool 冲突。
     * <p>serviceId 先做 sanitize，保证拼接结果仍合法；超长时截断 baseName。
     */
    public static String withServicePrefix(String serviceId, String baseName) {
        String prefix = sanitize(serviceId);
        String name = sanitize(baseName);
        if (prefix == null) {
            return name;
        }
        if (name == null) {
            name = "tool";
        }
        String joined = prefix + "_" + name;
        return joined.length() > MAX_LENGTH ? joined.substring(0, MAX_LENGTH) : joined;
    }
}
