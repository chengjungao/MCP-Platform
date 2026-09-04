package com.mcpbridge.manager.service;

import com.mcpbridge.common.error.ErrorCode;
import com.mcpbridge.common.error.PlatformException;
import com.mcpbridge.manager.config.ManagerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Auth-B 凭据字段级加密（SEC-01）。
 *
 * <p>算法 AES-256-GCM：每次加密生成随机 12 字节 IV，输出 {@code base64(iv || ciphertext || tag)}。
 * 主密钥由配置源串经 SHA-256 派生，生产环境必须通过 {@code MANAGER_CRYPTO_KEY} 或 KMS 注入高熵随机串。
 *
 * <p>验收口径：数据库泄露场景下，除主密钥外凭据不可还原。
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(ManagerProperties properties) {
        this.key = deriveKey(properties.crypto().key());
        if (properties.crypto().key().startsWith("dev-only")) {
            log.warn("正在使用开发默认加密主密钥，生产环境必须通过 MANAGER_CRYPTO_KEY 注入随机密钥（SEC-01）");
        }
    }

    /** 加密明文；入参为空时原样返回，便于「未配置」与「已配置」共用一条写入路径。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv).put(cipherText);
            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL_ERROR, "凭据加密失败", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return encrypted;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(encrypted);
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 不泄露密文内容，只报告失败：常见于主密钥轮换后未重新保存凭据
            throw new PlatformException(ErrorCode.INTERNAL_ERROR,
                    "凭据解密失败（请确认 MANAGER_CRYPTO_KEY 未变更，或重新保存上行授权配置）", e);
        }
    }

    private static SecretKeySpec deriveKey(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}