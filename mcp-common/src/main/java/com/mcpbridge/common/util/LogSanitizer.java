package com.mcpbridge.common.util;

import java.util.regex.Pattern;

/**
 * 日志脱敏（SEC-02）。
 *
 * <p>控制面与数据面在打印请求/响应/异常前统一过一遍：疑似密钥、令牌、密码字段打码，
 * 避免上行凭据或用户 PII 落进日志。默认开启，不提供关闭开关（合规基线）。
 */
public final class LogSanitizer {

    /** JSON 或表单中的敏感键：secret/password/token/authorization/apiKey/client_secret 等。 */
    private static final Pattern SENSITIVE_KV = Pattern.compile(
            "(\"(?:[^\"]*(?:secret|password|passwd|token|authorization|api[-_]?key|credential)[^\"]*)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENSITIVE_FORM = Pattern.compile(
            "((?:secret|password|token|api[-_]?key|client_secret)\\s*[=:]\\s*)([^\\s&,;\"']+)",
            Pattern.CASE_INSENSITIVE);

    /** Authorization / X-Api-Key 头的值。 */
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._\\-+/=]{6,}");

    private static final String MASK = "\"****\"";

    private LogSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = SENSITIVE_KV.matcher(text).replaceAll("$1" + MASK);
        result = SENSITIVE_FORM.matcher(result).replaceAll("$1****");
        result = BEARER.matcher(result).replaceAll("$1 ****");
        return result;
    }

    /** 截断 + 脱敏，用于打印上游响应体。 */
    public static String sanitizeAndTruncate(String text, int maxLength) {
        String sanitized = sanitize(text);
        if (sanitized == null || sanitized.length() <= maxLength) {
            return sanitized;
        }
        return sanitized.substring(0, maxLength) + "...(truncated)";
    }
}