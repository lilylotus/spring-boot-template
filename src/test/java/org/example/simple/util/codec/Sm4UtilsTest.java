package org.example.simple.util.codec;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Sm4Utils} 单元测试。
 */
class Sm4UtilsTest {

    @Test
    void encryptsAndDecryptsWithSm4Gcm() {
        String key = Sm4Utils.generateKey();
        String plaintext = "SM4-GCM 国密对称加密";

        String ciphertext = Sm4Utils.encrypt(plaintext, key);

        assertEquals(16, Base64.getDecoder().decode(key).length);
        assertEquals(plaintext, Sm4Utils.decrypt(ciphertext, key));
    }

    @Test
    void usesFreshIvForEveryEncryption() {
        String key = Sm4Utils.generateKey();

        String first = Sm4Utils.encrypt("相同文本", key);
        String second = Sm4Utils.encrypt("相同文本", key);

        assertNotEquals(first, second);
        assertEquals("相同文本", Sm4Utils.decrypt(first, key));
        assertEquals("相同文本", Sm4Utils.decrypt(second, key));
    }

    @Test
    void rejectsTamperedCiphertextAndWrongKey() {
        String key = Sm4Utils.generateKey();
        String ciphertext = Sm4Utils.encrypt("认证数据", key);
        byte[] tampered = Base64.getDecoder().decode(ciphertext);
        tampered[tampered.length - 1] ^= 1;

        assertThrows(
            IllegalArgumentException.class,
            () -> Sm4Utils.decrypt(Base64.getEncoder().encodeToString(tampered), key));
        assertThrows(
            IllegalArgumentException.class,
            () -> Sm4Utils.decrypt(ciphertext, Sm4Utils.generateKey()));
    }

    @Test
    void rejectsUnknownEnvelopeVersion() {
        String key = Sm4Utils.generateKey();
        byte[] envelope = Base64.getDecoder().decode(Sm4Utils.encrypt("版本数据", key));
        envelope[0] = 2;

        assertThrows(
            IllegalArgumentException.class,
            () -> Sm4Utils.decrypt(Base64.getEncoder().encodeToString(envelope), key));
    }
}
