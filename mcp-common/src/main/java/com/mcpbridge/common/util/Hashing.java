package com.mcpbridge.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要工具。
 *
 * <p>用途：① 原始 Swagger 文档存档的 sha256 校验（BR-2「原文只读 + 哈希校验，双保险」）；
 * ② Auth-D static-bearer 令牌只存哈希不存明文；③ 缓存 key 的稳定指纹。
 */
public final class Hashing {

    private static final HexFormat HEX = HexFormat.of();

    private Hashing() {
    }

    public static String sha256Hex(byte[] bytes) {
        return HEX.formatHex(digest("SHA-256").digest(bytes));
    }

    public static String sha256Hex(String text) {
        return sha256Hex(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
    }

    /** 取 sha256 前 12 位，用于日志与展示（不参与安全判定）。 */
    public static String shortSha256(String text) {
        String hex = sha256Hex(text);
        return hex.substring(0, Math.min(12, hex.length()));
    }

    /** 凭据掩码：仅保留末 4 位（SEC-01 脱敏回显）。 */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() <= 4) {
            return "****";
        }
        return "****" + secret.substring(secret.length() - 4);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持摘要算法: " + algorithm, e);
        }
    }
}
