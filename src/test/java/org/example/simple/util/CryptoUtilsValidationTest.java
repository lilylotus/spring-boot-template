package org.example.simple.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 密码学工具统一参数约定测试。
 */
class CryptoUtilsValidationTest {

    @Test
    void rejectsNullPlaintext() {
        assertThrows(IllegalArgumentException.class, () -> AesUtils.encrypt(null, AesUtils.generateKey()));
        assertThrows(IllegalArgumentException.class, () -> Sm4Utils.encrypt(null, Sm4Utils.generateKey()));
        assertThrows(
            IllegalArgumentException.class,
            () -> RsaUtils.encrypt(null, RsaUtils.generateKeyPair().publicKey()));
        assertThrows(
            IllegalArgumentException.class,
            () -> Sm2Utils.encrypt(null, Sm2Utils.generateKeyPair().publicKey()));
    }

    @Test
    void rejectsBlankAndInvalidBase64Keys() {
        assertThrows(IllegalArgumentException.class, () -> AesUtils.encrypt("数据", " "));
        assertThrows(IllegalArgumentException.class, () -> Sm4Utils.encrypt("数据", "不是 Base64!"));
        assertThrows(IllegalArgumentException.class, () -> RsaUtils.encrypt("数据", "不是 Base64!"));
        assertThrows(IllegalArgumentException.class, () -> Sm2Utils.encrypt("数据", "不是 Base64!"));
    }
}
