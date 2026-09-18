package org.example.simple.util;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * SHA-256 与 SM3 摘要工具，摘要结果统一使用标准 Base64 表示。
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
        CryptoSupport.requireNonNull(content, "摘要内容");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return CryptoSupport.encodeBase64(digest.digest(content.getBytes(CryptoSupport.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法计算 SHA-256 摘要", exception);
        }
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
        CryptoSupport.requireNonNull(content, "摘要内容");
        try {
            MessageDigest digest = MessageDigest.getInstance("SM3", CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            return CryptoSupport.encodeBase64(digest.digest(content.getBytes(CryptoSupport.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法计算 SM3 摘要", exception);
        }
    }
}
