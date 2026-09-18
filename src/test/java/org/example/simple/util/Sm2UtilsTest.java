package org.example.simple.util;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Sm2Utils} 单元测试。
 */
class Sm2UtilsTest {

    @Test
    void encryptsAndDecryptsUsingC1C3C2Layout() throws Exception {
        Sm2Utils.KeyPairData keyPair = Sm2Utils.generateKeyPair();
        String plaintext = "SM2 国密加解密数据";

        String ciphertext = Sm2Utils.encrypt(plaintext, keyPair.publicKey());

        assertEquals(plaintext, Sm2Utils.decrypt(ciphertext, keyPair.privateKey()));
        PublicKey publicKey = KeyFactory.getInstance("EC", CryptoSupport.BOUNCY_CASTLE_PROVIDER)
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(keyPair.publicKey())));
        assertNotNull(publicKey);
    }

    @Test
    void rejectsWrongPrivateKey() {
        Sm2Utils.KeyPairData firstKeyPair = Sm2Utils.generateKeyPair();
        Sm2Utils.KeyPairData secondKeyPair = Sm2Utils.generateKeyPair();
        String ciphertext = Sm2Utils.encrypt("机密数据", firstKeyPair.publicKey());

        assertThrows(
            IllegalArgumentException.class,
            () -> Sm2Utils.decrypt(ciphertext, secondKeyPair.privateKey()));
    }

    @Test
    void signsAndVerifiesContent() {
        Sm2Utils.KeyPairData keyPair = Sm2Utils.generateKeyPair();
        String signature = Sm2Utils.sign("国密签名数据", keyPair.privateKey());

        assertTrue(Sm2Utils.verify("国密签名数据", signature, keyPair.publicKey()));
        assertFalse(Sm2Utils.verify("篡改后的数据", signature, keyPair.publicKey()));
        assertFalse(Sm2Utils.verify(
            "国密签名数据",
            signature,
            Sm2Utils.generateKeyPair().publicKey()));
    }

    @Test
    void handlesInvalidSignatureInputs() {
        Sm2Utils.KeyPairData keyPair = Sm2Utils.generateKeyPair();

        assertThrows(
            IllegalArgumentException.class,
            () -> Sm2Utils.verify("数据", "不是 Base64!", keyPair.publicKey()));
        assertFalse(Sm2Utils.verify(
            "数据",
            Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}),
            keyPair.publicKey()));
    }
}
