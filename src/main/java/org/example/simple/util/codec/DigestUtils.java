package org.example.simple.util.codec;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.HexFormat;

/**
 * SHA-256 与 SM3 摘要工具，支持将摘要结果表示为标准 Base64 或小写十六进制字符串。
 */
public final class DigestUtils {

    private DigestUtils() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 摘要。
     *
     * @param content 待摘要文本，不能为 {@code null}
     * @return Base64 编码的 SHA-256 摘要
     * @throws IllegalArgumentException 内容为 {@code null} 时抛出
     * @throws IllegalStateException 当前运行环境不支持 SHA-256 时抛出
     */
    public static String sha256(String content) {
        return CryptoSupport.encodeBase64(digest(content, "SHA-256", null));
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 摘要，并返回无分隔符的小写十六进制字符串。
     *
     * @param content 待摘要文本，不能为 {@code null}
     * @return 64 个小写十六进制字符组成的 SHA-256 摘要
     * @throws IllegalArgumentException 内容为 {@code null} 时抛出
     * @throws IllegalStateException 当前运行环境不支持 SHA-256 时抛出
     */
    public static String sha256Hex(String content) {
        return HexFormat.of().formatHex(digest(content, "SHA-256", null));
    }

    /**
     * 计算 UTF-8 文本的 SM3 摘要。
     *
     * @param content 待摘要文本，不能为 {@code null}
     * @return Base64 编码的 SM3 摘要
     * @throws IllegalArgumentException 内容为 {@code null} 时抛出
     * @throws IllegalStateException 当前运行环境不支持 SM3 时抛出
     */
    public static String sm3(String content) {
        return CryptoSupport.encodeBase64(digest(content, "SM3", CryptoSupport.BOUNCY_CASTLE_PROVIDER));
    }

    /**
     * 计算 UTF-8 文本的 SM3 摘要，并返回无分隔符的小写十六进制字符串。
     *
     * @param content 待摘要文本，不能为 {@code null}
     * @return 64 个小写十六进制字符组成的 SM3 摘要
     * @throws IllegalArgumentException 内容为 {@code null} 时抛出
     * @throws IllegalStateException 当前运行环境不支持 SM3 时抛出
     */
    public static String sm3Hex(String content) {
        return HexFormat.of().formatHex(digest(content, "SM3", CryptoSupport.BOUNCY_CASTLE_PROVIDER));
    }

    private static byte[] digest(String content, String algorithm, Provider provider) {
        CryptoSupport.requireNonNull(content, "摘要内容");
        try {
            MessageDigest messageDigest = provider == null
                ? MessageDigest.getInstance(algorithm)
                : MessageDigest.getInstance(algorithm, provider);
            return messageDigest.digest(content.getBytes(CryptoSupport.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法计算 " + algorithm + " 摘要", exception);
        }
    }
}
