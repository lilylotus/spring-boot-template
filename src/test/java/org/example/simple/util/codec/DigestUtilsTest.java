package org.example.simple.util.codec;

import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DigestUtils} 单元测试。
 */
class DigestUtilsTest {

    @Test
    void calculatesSha256StandardVector() {
        String digest = DigestUtils.sha256("abc");

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HexFormat.of().formatHex(Base64.getDecoder().decode(digest)));
        assertEquals(digest, DigestUtils.sha256("abc"));
    }

    @Test
    void calculatesSm3StandardVector() {
        String digest = DigestUtils.sm3("abc");

        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            HexFormat.of().formatHex(Base64.getDecoder().decode(digest)));
        assertEquals(digest, DigestUtils.sm3("abc"));
    }

    @Test
    void calculatesSha256HexStandardVector() {
        String content = "af4843ea571341ab96b539364ac15ba9_d6fe2edac56a4304842e47f164403c78";
        String digest = DigestUtils.sha256Hex(content);

        assertEquals(
            "e2dee90a70c35fcdb0478333cdc6d3473186317741ff9c04bb76c169875a77a4",
            digest);
        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(digest, DigestUtils.sha256Hex(content));
    }

    @Test
    void calculatesSm3HexStandardVector() {
        String digest = DigestUtils.sm3Hex("abc");

        assertEquals(
            "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0",
            digest);
        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(digest, DigestUtils.sm3Hex("abc"));
    }

    @Test
    void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class, () -> DigestUtils.sha256(null));
        assertThrows(IllegalArgumentException.class, () -> DigestUtils.sha256Hex(null));
        assertThrows(IllegalArgumentException.class, () -> DigestUtils.sm3(null));
        assertThrows(IllegalArgumentException.class, () -> DigestUtils.sm3Hex(null));
    }
}
