package com.holzyn.actor.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加密解密服务（AES-GCM）。
 * <p>职责：对用户配置的 AI API Key 进行对称加密后入库、解密用于调用，
 * 满足设计文档 §8「api_key 加密存储」要求，明文永不下发前端。</p>
 * <p>算法说明：AES-256-GCM（128 位 tag），密钥由配置项 {@code holzyn.actor.api-key-secret}
 * 经 SHA-256 派生为 32 字节；密文格式 {@code v1:<ivBase64>:<cipherTextBase64>}，
 * iv 为 12 字节随机数，每次加密独立生成（GCM 安全性要求 IV 不重用）。</p>
 * <p>演示模式兜底：当未配置 api-key-secret 时使用内置开发密钥并告警，
 * 保证演示模式（casdoor.enabled=false）可独立跑通；生产必须通过环境变量注入。</p>
 * <p>所属模块：service/common（通用服务层-安全子域）</p>
 */
@Slf4j
@Component
public class AesCipherService {

    /** 密文版本前缀（后续更换算法/密钥时据此区分与迁移） */
    private static final String PREFIX = "v1:";

    /** 算法：AES-GCM（NoPadding） */
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    /** 密钥算法名 */
    private static final String KEY_ALGORITHM = "AES";

    /** GCM 认证标签长度（128 位，行业推荐） */
    private static final int TAG_BITS = 128;

    /** IV 长度（12 字节，GCM 推荐值） */
    private static final int IV_BYTES = 12;

    /** 演示模式兜底密钥（仅当未配置环境变量时使用；生产禁止） */
    private static final String DEV_FALLBACK_SECRET = "holzyn-actor-dev-secret-do-not-use-in-prod";

    /** 旧密钥兼容命中告警标志：仅首次命中时提示，避免每次列表加载刷 WARN */
    private static final java.util.concurrent.atomic.AtomicBoolean LEGACY_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** API Key 加密密钥（环境变量 HOLOZYN_ACTOR_API_KEY_SECRET 注入） */
    private final String apiKeySecret;

    /** 随机数源（加密安全） */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 构造函数：注入密钥配置并输出安全提示日志。
     *
     * @param apiKeySecret 加密密钥（可空，空则回退开发密钥）
     */
    public AesCipherService(@Value("${holzyn.actor.api-key-secret:}") String apiKeySecret) {
        this.apiKeySecret = (apiKeySecret == null || apiKeySecret.isBlank()) ? DEV_FALLBACK_SECRET : apiKeySecret;
        if (apiKeySecret == null || apiKeySecret.isBlank()) {
            log.warn("未配置 HOLOZYN_ACTOR_API_KEY_SECRET，使用内置开发密钥（仅限演示模式，生产必须注入环境变量）");
        }
    }

    /**
     * 加密明文 API Key。
     *
     * @param plain 明文 API Key
     * @return 密文字符串（v1:iv:data）；明文为空时返回 null
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return null;
        try {
            byte[] key = deriveKey();
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            // 加密失败属内部错误，包装为运行时异常交由全局异常处理器兜底
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /**
     * 解密 API Key 密文。
     *
     * @param cipherText 密文字符串（v1:iv:data）
     * @return 明文 API Key；密文为空时返回 null
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) return null;
        // 先尝试当前配置密钥
        String plain = tryDecrypt(cipherText, deriveKey());
        if (plain != null) {
            return plain;
        }
        // 兼容历史数据：早期未配置 HOLOZYN_ACTOR_API_KEY_SECRET 时曾用内置开发密钥加密入库，
        // 这里二次尝试；命中后提示用户重新保存 Key，让后续密文迁移到当前密钥
        String legacyPlain = tryDecrypt(cipherText, deriveKey(DEV_FALLBACK_SECRET));
        if (legacyPlain != null) {
            if (LEGACY_WARNED.compareAndSet(false, true)) {
                log.warn("检测到由旧内置密钥加密的 API Key（建议在「设置-模型 API」编辑该 API 重新保存 Key，以当前密钥重新加密）");
            }
            return legacyPlain;
        }
        // 两次均失败（密钥变更/数据损坏）：返回 null 由调用方给出友好提示
        log.error("API Key 解密失败：当前密钥与内置开发密钥均无法解密，请编辑该 API 重新填写 Key");
        return null;
    }

    /**
     * 尝试用指定密钥解密密文（失败返回 null，不抛异常）。
     *
     * @param cipherText 密文字符串（v1:iv:data）
     * @param key        32 字节密钥
     * @return 明文；解析/解密失败返回 null
     */
    private String tryDecrypt(String cipherText, byte[] key) {
        try {
            String payload = cipherText.startsWith(PREFIX) ? cipherText.substring(PREFIX.length()) : cipherText;
            String[] parts = payload.split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] enc = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 由指定密钥文本派生 256 位密钥（SHA-256 摘要）。
     *
     * @param secret 密钥文本
     * @return 32 字节密钥
     */
    private byte[] deriveKey(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("密钥派生失败", e);
        }
    }

    /**
     * 派生 256 位密钥：对当前配置密钥做 SHA-256 摘要。
     *
     * @return 32 字节密钥
     */
    private byte[] deriveKey() {
        return deriveKey(apiKeySecret);
    }
}
