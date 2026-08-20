package com.holzyn.actor.service.common;

import com.holzyn.actor.common.AesCipherService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API Key 加密服务单元测试。
 * <p>职责：验证 AES-GCM 加解密往返、旧密钥兼容解密（历史数据用内置开发密钥加密的场景）与空值处理。</p>
 */
class AesCipherServiceTest {

    /**
     * 加解密往返：用同一密钥加密后能正确解密出原文。
     */
    @Test
    void encryptDecryptRoundTrip() {
        AesCipherService svc = new AesCipherService("test-secret-key");
        String cipher = svc.encrypt("sk-test-1234567890");
        assertNotNull(cipher);
        assertTrue(cipher.startsWith("v1:"), "密文应带 v1: 版本前缀");
        assertEquals("sk-test-1234567890", svc.decrypt(cipher));
    }

    /**
     * 旧密钥兼容：历史数据用内置开发密钥加密，当前密钥不同时应通过兼容分支解密成功。
     */
    @Test
    void decryptWithFallbackForLegacyCipher() {
        // 空密钥构造 → 回退内置开发密钥（模拟早期未配置环境变量时期加密入库的历史密文）
        String legacyCipher = new AesCipherService("").encrypt("sk-legacy-key-abcdef");
        // 当前运行时使用另一密钥：当前密钥解不开，应命中旧密钥兼容分支
        AesCipherService svc = new AesCipherService("current-random-secret-1234567890");
        assertEquals("sk-legacy-key-abcdef", svc.decrypt(legacyCipher));
    }

    /**
     * 空密文返回 null（调用方据此给出「解密失败」友好提示）。
     */
    @Test
    void decryptBlankReturnsNull() {
        AesCipherService svc = new AesCipherService("secret");
        assertNull(svc.decrypt(null));
        assertNull(svc.decrypt(""));
        assertNull(svc.decrypt("   "));
    }
}