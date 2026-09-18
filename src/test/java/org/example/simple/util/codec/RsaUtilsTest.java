package org.example.simple.util.codec;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RsaUtils} 单元测试。
 */
class RsaUtilsTest {

    @Test
    void encryptsAndDecryptsLongUtf8Text() throws Exception {
        RsaUtils.KeyPairData keyPair = RsaUtils.generateKeyPair();
        String plaintext = "需要自动分段的 RSA 长文本。".repeat(80);

        String ciphertext = RsaUtils.encrypt(plaintext, keyPair.publicKey());

        assertEquals(plaintext, RsaUtils.decrypt(ciphertext, keyPair.privateKey()));
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(keyPair.publicKey())));
        assertTrue(publicKey.getModulus().bitLength() >= 2048);
    }

    @Test
    void rejectsWrongPrivateKey() {
        RsaUtils.KeyPairData firstKeyPair = RsaUtils.generateKeyPair();
        RsaUtils.KeyPairData secondKeyPair = RsaUtils.generateKeyPair();
        String ciphertext = RsaUtils.encrypt("机密数据", firstKeyPair.publicKey());

        assertThrows(
            IllegalArgumentException.class,
            () -> RsaUtils.decrypt(ciphertext, secondKeyPair.privateKey()));
    }

    @Test
    void encryptsAndDecryptsEmptyText() {
        RsaUtils.KeyPairData keyPair = RsaUtils.generateKeyPair();

        String ciphertext = RsaUtils.encrypt("", keyPair.publicKey());

        assertEquals("", RsaUtils.decrypt(ciphertext, keyPair.privateKey()));
    }

    @Test
    void signsAndVerifiesContent() {
        RsaUtils.KeyPairData keyPair = RsaUtils.generateKeyPair();
        String signature = RsaUtils.sign("待签名数据", keyPair.privateKey());

        assertTrue(RsaUtils.verify("待签名数据", signature, keyPair.publicKey()));
        assertFalse(RsaUtils.verify("已篡改数据", signature, keyPair.publicKey()));
        assertFalse(RsaUtils.verify(
            "待签名数据",
            signature,
            RsaUtils.generateKeyPair().publicKey()));
    }

    @Test
    void handlesInvalidSignatureInputs() {
        RsaUtils.KeyPairData keyPair = RsaUtils.generateKeyPair();

        assertThrows(
            IllegalArgumentException.class,
            () -> RsaUtils.verify("数据", "不是 Base64!", keyPair.publicKey()));
        assertFalse(RsaUtils.verify(
            "数据",
            Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}),
            keyPair.publicKey()));
    }
}
