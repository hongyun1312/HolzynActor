package com.holzyn.actor.service.common;

import com.holzyn.actor.common.HolzynCrypto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * HolzynCrypto 密码加密单元测试（V2.0 .holzyn 敏感数据密码加密）。
 * <p>职责：验证 PBKDF2 + AES-GCM 加解密往返、错误密码返回 null、salt/iv 随机性
 * 与空入参保护。</p>
 */
class HolzynCryptoTest {

    /** 加解密往返：正确密码可还原明文。 */
    @Test
    void encryptDecryptRoundTrip() {
        HolzynCrypto crypto = new HolzynCrypto();
        String plain = "{\"apis\":[{\"name\":\"DeepSeek\",\"apiKeyCipher\":\"v1:xxx\"}]}";
        String cipher = crypto.encrypt(plain, "mypassword123");
        assertEquals(plain, crypto.decrypt(cipher, "mypassword123"));
    }

    /** 错误密码：解密返回 null（敏感配置置空 + 提示）。 */
    @Test
    void decryptWithWrongPasswordReturnsNull() {
        HolzynCrypto crypto = new HolzynCrypto();
        String cipher = crypto.encrypt("secret-config", "correct-password");
        assertNull(crypto.decrypt(cipher, "wrong-password"));
    }

    /** 随机性：同一明文两次加密结果不同（salt/iv 每次随机）。 */
    @Test
    void encryptIsRandomized() {
        HolzynCrypto crypto = new HolzynCrypto();
        String c1 = crypto.encrypt("same-plain", "pw");
        String c2 = crypto.encrypt("same-plain", "pw");
        assertNotEquals(c1, c2);
    }

    /** 空入参保护：明文/密码为空不加密，返回 null。 */
    @Test
    void emptyInputsReturnNull() {
        HolzynCrypto crypto = new HolzynCrypto();
        assertNull(crypto.encrypt("", "pw"));
        assertNull(crypto.encrypt("plain", ""));
        assertNull(crypto.decrypt(null, "pw"));
        assertNull(crypto.decrypt("cipher", ""));
    }
}
