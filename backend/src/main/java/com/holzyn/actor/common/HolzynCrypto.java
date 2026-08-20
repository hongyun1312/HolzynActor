package com.holzyn.actor.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * .holzyn 项目包「密码加密」工具（V2.0 设计文档 §6.3/§11）。
 * <p>职责：对敏感配置块（如 settings/apis.json）做口令加密——
 * PBKDF2（HMAC-SHA256，迭代 ≥210_000）从用户密码派生密钥 → AES-256-GCM 加密；
 * 密文格式 {@code v1:<saltBase64>:<ivBase64>:<dataBase64>}；salt/iv 每次随机生成。</p>
 * <p>与 API Key 加密（AesCipherService）不同：这里是用户口令派生密钥（可导出/导入携带），
 * 解密密钥只存在于内存，不落盘。</p>
 * <p>所属模块：service/common（通用服务层-安全子域）</p>
 */
@Slf4j
@Component
public class HolzynCrypto {

    /** 密文版本前缀 */
    private static final String PREFIX = "v1:";

    /** 密钥派生算法：PBKDF2 + HMAC-SHA256 */
    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";

    /** 加密算法：AES-GCM（NoPadding） */
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";

    /** 密钥算法名 */
    private static final String KEY_ALGO = "AES";

    /** GCM 认证标签长度（128 位） */
    private static final int TAG_BITS = 128;

    /** IV 长度（12 字节，GCM 推荐值） */
    private static final int IV_BYTES = 12;

    /** PBKDF2 盐长度（16 字节） */
    private static final int SALT_BYTES = 16;

    /** PBKDF2 迭代次数（≥210_000，V2.0 设计要求） */
    private static final int ITERATIONS = 210_000;

    /** 派生密钥长度（256 位） */
    private static final int KEY_BITS = 256;

    /** 随机数源 */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 用密码加密明文（每次生成随机 salt/iv）。
     *
     * @param plain    明文（如 apis.json 的 JSON 文本）
     * @param password 用户密码（不可空）
     * @return 密文（v1:salt:iv:data，均为 base64）；明文为空返回 null
     */
    public String encrypt(String plain, String password) {
        if (plain == null || plain.isBlank() || password == null || password.isEmpty()) {
            return null;
        }
        try {
            byte[] salt = new byte[SALT_BYTES];
            secureRandom.nextBytes(salt);
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            SecretKeySpec key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(salt) + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(enc);
        } catch (Exception e) {
            log.error("密码加密失败", e);
            throw new IllegalStateException("密码加密失败", e);
        }
    }

    /**
     * 用密码解密密文。
     *
     * @param cipherText 密文（v1:salt:iv:data）
     * @param password   用户密码
     * @return 明文；密码错误 / 格式非法 / 解密失败返回 null
     */
    public String decrypt(String cipherText, String password) {
        if (cipherText == null || cipherText.isBlank() || password == null || password.isEmpty()) {
            return null;
        }
        try {
            String payload = cipherText.startsWith(PREFIX) ? cipherText.substring(PREFIX.length()) : cipherText;
            String[] parts = payload.split(":", 3);
            if (parts.length != 3) {
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] enc = Base64.getDecoder().decode(parts[2]);
            SecretKeySpec key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 密码错误 / 数据损坏：返回 null 由调用方置空并提示
            return null;
        }
    }

    /**
     * PBKDF2 派生 256 位 AES 密钥。
     *
     * @param password 用户密码
     * @param salt     随机盐
     * @return AES 密钥
     * @throws Exception 派生失败（理论上不会）
     */
    private SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, KEY_ALGO);
    }
}
