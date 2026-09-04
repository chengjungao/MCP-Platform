package com.mcpbridge.common.util;

import com.mcpbridge.common.error.PlatformException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对外 PATH 末段规则（BR-3）。
 *
 * <p>端点模板：{@code {集群入口}/{平台保留前缀}/{自定义末段}}，用户只能自定义末段。
 * 末段规则 {@code ^[a-z0-9]([a-z0-9-_]{0,62}[a-z0-9])?$}；共享集群内全局唯一，
 * 唯一性由控制面在注册与发布时校验（见 Manager 的 PublishService）。
 */
public final class PathSegments {

    public static final Pattern PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-_]{0,62}[a-z0-9])?$");

    public static final int MAX_LENGTH = 64;

    /** 平台保留前缀的默认值，可被集群配置覆盖。 */
    public static final String DEFAULT_RESERVED_PREFIX = "mcp";

    private PathSegments() {
    }

    public static boolean isValid(String segment) {
        return segment != null && PATTERN.matcher(segment).matches();
    }

    /** 去掉首尾空白并转小写，便于用户输入容错（校验仍按严格规则）。 */
    public static String normalize(String segment) {
        return segment == null ? null : segment.trim().toLowerCase();
    }

    /**
     * 校验末段，非法时抛出携带结构化原因的异常（前端可即时反馈，SVR-01 验收项）。
     */
    public static String requireValid(String segment) {
        String normalized = normalize(segment);
        if (normalized == null || normalized.isEmpty()) {
            throw PlatformException.validation("PATH 末段不能为空", Map.of("field", "pathSegment"));
        }
        if (normalized.length() > MAX_LENGTH) {
            throw PlatformException.validation("PATH 末段长度不得超过 " + MAX_LENGTH + " 个字符",
                    Map.of("field", "pathSegment", "actual", normalized.length()));
        }
        if (!PATTERN.matcher(normalized).matches()) {
            throw PlatformException.validation(
                    "PATH 末段只能包含小写字母、数字、连字符与下划线，且必须以字母或数字开头和结尾",
                    Map.of("field", "pathSegment", "pattern", PATTERN.pattern(), "value", normalized));
        }
        return normalized;
    }

    /** 拼接完整对外端点：{entrypoint}/{prefix}/{segment}。 */
    public static String endpoint(String entrypoint, String reservedPrefix, String segment) {
        String base = entrypoint == null ? "" : entrypoint.replaceAll("/+$", "");
        String prefix = (reservedPrefix == null || reservedPrefix.isBlank())
                ? DEFAULT_RESERVED_PREFIX
                : reservedPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
        return base + "/" + prefix + "/" + requireValid(segment);
    }
}
