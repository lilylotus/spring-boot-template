package org.example.simple.util;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SM4-GCM 密钥生成与认证加解密工具。
 * <p>
 * 密文格式为“版本字节、96 位随机 IV、密文与 128 位认证标签”，组合后使用标准 Base64 编码。
 */
public final class Sm4Utils {

    /** 国密对称密钥算法名称，用于生成 SM4 密钥和构造密钥对象。 */
    private static final String ALGORITHM = "SM4";

    /** SM4 加解密转换名称，GCM 模式同时提供数据机密性与完整性校验且不需要填充。 */
    private static final String TRANSFORMATION = "SM4/GCM/NoPadding";

    /** SM4 标准密钥位数，算法规范固定为 128 位。 */
    private static final int KEY_SIZE_BITS = 128;

    /** SM4 密钥字节数，用于校验 Base64 解码后的密钥长度。 */
    private static final int KEY_SIZE_BYTES = KEY_SIZE_BITS / Byte.SIZE;

    /** GCM 初始化向量字节数，96 位是 GCM 推荐长度并可获得最佳互操作性。 */
    private static final int IV_SIZE_BYTES = 12;

    /** GCM 认证标签位数，用于检测密文或关联认证数据是否被篡改。 */
    private static final int TAG_SIZE_BITS = 128;

    /** GCM 认证标签字节数，用于校验密文封装具备最小完整长度。 */
    private static final int TAG_SIZE_BYTES = TAG_SIZE_BITS / Byte.SIZE;

    /** 当前密文封装格式版本，位于 Base64 解码后字节数组首位，便于未来兼容格式升级。 */
    private static final byte FORMAT_VERSION = 1;

    /** 密码学安全随机源，用于生成 SM4 密钥和每次加密所需的唯一随机 IV。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Sm4Utils() {
    }

    /**
     * 生成 128 位安全随机 SM4 密钥。
     *
     * @return 标准 Base64 编码的 SM4 密钥
     * @throws IllegalStateException 当前运行环境无法初始化 SM4 时抛出
     */
    public static String generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            generator.init(KEY_SIZE_BITS, SECURE_RANDOM);
            return CryptoSupport.encodeBase64(generator.generateKey().getEncoded());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法生成 SM4 密钥", exception);
        }
    }

    /**
     * 使用 SM4-GCM 加密 UTF-8 文本。
     *
     * @param plaintext 明文，不能为 {@code null}
     * @param keyBase64 Base64 编码的 128 位 SM4 密钥
     * @return Base64 编码的版本化 SM4-GCM 密文
     * @throws IllegalArgumentException 参数、密钥不合法或加密失败时抛出
     */
    public static String encrypt(String plaintext, String keyBase64) {
        CryptoSupport.requireNonNull(plaintext, "明文");
        SecretKey key = decodeKey(keyBase64);
        byte[] iv = new byte[IV_SIZE_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(CryptoSupport.UTF_8));
            byte[] envelope = new byte[1 + iv.length + ciphertext.length];
            envelope[0] = FORMAT_VERSION;
            System.arraycopy(iv, 0, envelope, 1, iv.length);
            System.arraycopy(ciphertext, 0, envelope, 1 + iv.length, ciphertext.length);
            return CryptoSupport.encodeBase64(envelope);
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("SM4-GCM 加密失败", exception);
        }
    }

    /**
     * 解密版本化 SM4-GCM Base64 密文并验证认证标签。
     *
     * @param ciphertextBase64 Base64 编码的版本化 SM4-GCM 密文
     * @param keyBase64        Base64 编码的 128 位 SM4 密钥
     * @return UTF-8 明文
     * @throws IllegalArgumentException 参数、密钥、格式或认证标签不合法时抛出
     */
    public static String decrypt(String ciphertextBase64, String keyBase64) {
        byte[] envelope = CryptoSupport.decodeBase64(ciphertextBase64, "SM4 密文");
        if (envelope.length < 1 + IV_SIZE_BYTES + TAG_SIZE_BYTES) {
            throw new IllegalArgumentException("SM4 密文长度不合法");
        }
        if (envelope[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("不支持的 SM4 密文格式版本");
        }
        SecretKey key = decodeKey(keyBase64);
        byte[] iv = Arrays.copyOfRange(envelope, 1, 1 + IV_SIZE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(envelope, 1 + IV_SIZE_BYTES, envelope.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_SIZE_BITS, iv));
            return new String(cipher.doFinal(ciphertext), CryptoSupport.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("SM4-GCM 解密失败，请检查密钥和密文", exception);
        }
    }

    private static SecretKey decodeKey(String keyBase64) {
        byte[] keyBytes = CryptoSupport.decodeBase64(keyBase64, "SM4 密钥");
        if (keyBytes.length != KEY_SIZE_BYTES) {
            throw new IllegalArgumentException("SM4 密钥必须为 128 位");
        }
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
}
