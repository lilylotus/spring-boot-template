package org.example.simple.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Provider;
import java.util.Base64;
import java.util.Objects;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * 密码学工具的包内公共约定，集中处理字符集、Base64 和 Provider。
 */
final class CryptoSupport {

    /** 字符串与密码学字节之间的统一字符集，防止不同平台默认字符集造成结果不一致。 */
    static final Charset UTF_8 = StandardCharsets.UTF_8;

    /** 国密算法使用的 Bouncy Castle Provider 实例，显式传入算法工厂以避免修改 JVM 全局 Provider。 */
    static final Provider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();

    private CryptoSupport() {
    }

    /**
     * 校验参数不为 {@code null}。
     *
     * @param value 参数值
     * @param name 参数名称
     * @return 原参数值
     * @param <T> 参数类型
     * @throws IllegalArgumentException 参数为 {@code null} 时抛出
     */
    static <T> T requireNonNull(T value, String name) {
        try {
            return Objects.requireNonNull(value, name);
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException(name + "不能为 null", exception);
        }
    }

    /**
     * 校验字符串不为空白。
     *
     * @param value 参数值
     * @param name 参数名称
     * @return 原参数值
     * @throws IllegalArgumentException 参数为空白时抛出
     */
    static String requireNonBlank(String value, String name) {
        requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空白");
        }
        return value;
    }

    /**
     * 将字节编码为标准 Base64。
     *
     * @param value 待编码字节
     * @return 标准 Base64 字符串
     */
    static String encodeBase64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    /**
     * 解码标准 Base64 参数。
     *
     * @param value Base64 字符串
     * @param name 参数名称
     * @return 解码后的字节
     * @throws IllegalArgumentException 参数为空白或不是合法 Base64 时抛出
     */
    static byte[] decodeBase64(String value, String name) {
        requireNonBlank(value, name);
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + "不是合法的 Base64", exception);
        }
    }

    /**
     * 创建不包含敏感参数的调用方输入异常。
     *
     * @param message 中文错误说明
     * @param cause 原始异常
     * @return 参数异常
     */
    static IllegalArgumentException invalidInput(String message, Exception cause) {
        return new IllegalArgumentException(message, cause);
    }
}
