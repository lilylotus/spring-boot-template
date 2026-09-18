package org.example.simple.util.codec;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AesUtils} 单元测试。
 */
class AesUtilsTest {

    @Test
    void encryptsAndDecryptsWithAesGcm() {
        String key = AesUtils.generateKey();
        String plaintext = "AES-GCM 中文与 emoji 🔐";

        String ciphertext = AesUtils.encrypt(plaintext, key);

        assertEquals(32, Base64.getDecoder().decode(key).length);
        assertEquals(plaintext, AesUtils.decrypt(ciphertext, key));
    }

    @Test
    void usesFreshIvForEveryEncryption() {
        String key = AesUtils.generateKey();

        String first = AesUtils.encrypt("相同文本", key);
        String second = AesUtils.encrypt("相同文本", key);

        assertNotEquals(first, second);
        assertEquals("相同文本", AesUtils.decrypt(first, key));
        assertEquals("相同文本", AesUtils.decrypt(second, key));
    }

    @Test
    void rejectsTamperedCiphertextAndWrongKey() {
        String key = AesUtils.generateKey();
        String ciphertext = AesUtils.encrypt("认证数据", key);
        byte[] tampered = Base64.getDecoder().decode(ciphertext);
        tampered[tampered.length - 1] ^= 1;

        assertThrows(
            IllegalArgumentException.class,
            () -> AesUtils.decrypt(Base64.getEncoder().encodeToString(tampered), key));
        assertThrows(
            IllegalArgumentException.class,
            () -> AesUtils.decrypt(ciphertext, AesUtils.generateKey()));
    }

    @Test
    void rejectsUnknownEnvelopeVersion() {
        String key = AesUtils.generateKey();
        byte[] envelope = Base64.getDecoder().decode(AesUtils.encrypt("版本数据", key));
        envelope[0] = 2;

        assertThrows(
            IllegalArgumentException.class,
            () -> AesUtils.decrypt(Base64.getEncoder().encodeToString(envelope), key));
    }
}
