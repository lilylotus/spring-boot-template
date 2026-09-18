package org.example.simple.util.codec;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.RSAKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/**
 * RSA 加解密、签名验签和密钥生成工具。
 * <p>
 * 公钥使用 X.509 DER，私钥使用 PKCS#8 DER，所有密钥、密文和签名均以标准 Base64 表示。
 * 加密使用 OAEP-SHA-256 并自动分段，适用于可驻留内存的文本数据。
 */
public final class RsaUtils {

    /** RSA 密钥模数长度，2048 位用于提供当前通用场景所需的基础安全强度。 */
    private static final int KEY_SIZE_BITS = 2048;

    /** SHA-256 摘要的字节长度，用于计算 OAEP 填充后单个 RSA 加密块可容纳的最大明文长度。 */
    private static final int SHA_256_LENGTH_BYTES = 32;

    /** RSA 加解密转换名称，指定使用 OAEP-SHA-256 填充而不是安全性较弱的 PKCS#1 v1.5 填充。 */
    private static final String CIPHER_ALGORITHM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    /** RSA 签名算法名称，使用 SHA-256 摘要后再执行 RSA 签名和验签。 */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** OAEP 的完整参数，显式固定主摘要与 MGF1 摘要均为 SHA-256，避免 Provider 默认值差异。 */
    private static final OAEPParameterSpec OAEP_PARAMETER_SPEC = new OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT);

    private RsaUtils() {
    }

    /**
     * 生成 2048 位 RSA 密钥对。
     *
     * @return Base64 编码的 X.509 公钥和 PKCS#8 私钥
     * @throws IllegalStateException 当前运行环境不支持 RSA 时抛出
     */
    public static KeyPairData generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE_BITS);
            KeyPair keyPair = generator.generateKeyPair();
            return new KeyPairData(
                CryptoSupport.encodeBase64(keyPair.getPublic().getEncoded()),
                CryptoSupport.encodeBase64(keyPair.getPrivate().getEncoded()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法生成 RSA 密钥对", exception);
        }
    }

    /**
     * 使用 RSA 公钥和 OAEP-SHA-256 加密 UTF-8 文本。
     *
     * @param plaintext 明文，不能为 {@code null}
     * @param publicKeyBase64 Base64 编码的 X.509 RSA 公钥
     * @return Base64 编码的分段 RSA 密文
     * @throws IllegalArgumentException 参数或密钥不合法，或加密失败时抛出
     */
    public static String encrypt(String plaintext, String publicKeyBase64) {
        CryptoSupport.requireNonNull(plaintext, "明文");
        try {
            PublicKey publicKey = decodePublicKey(publicKeyBase64);
            int blockSize = modulusLengthBytes(publicKey) - (2 * SHA_256_LENGTH_BYTES) - 2;
            byte[] encrypted = processBlocks(
                plaintext.getBytes(CryptoSupport.UTF_8), publicKey, Cipher.ENCRYPT_MODE, blockSize);
            return CryptoSupport.encodeBase64(encrypted);
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("RSA 加密失败，请检查公钥", exception);
        }
    }

    /**
     * 使用 RSA 私钥和 OAEP-SHA-256 解密 Base64 密文。
     *
     * @param ciphertextBase64 Base64 编码的分段 RSA 密文
     * @param privateKeyBase64 Base64 编码的 PKCS#8 RSA 私钥
     * @return UTF-8 明文
     * @throws IllegalArgumentException 参数、密钥或密文不合法，或解密失败时抛出
     */
    public static String decrypt(String ciphertextBase64, String privateKeyBase64) {
        byte[] ciphertext = CryptoSupport.decodeBase64(ciphertextBase64, "RSA 密文");
        try {
            PrivateKey privateKey = decodePrivateKey(privateKeyBase64);
            int blockSize = modulusLengthBytes(privateKey);
            if (ciphertext.length == 0 || ciphertext.length % blockSize != 0) {
                throw new IllegalArgumentException("RSA 密文长度不合法");
            }
            byte[] plaintext = processBlocks(ciphertext, privateKey, Cipher.DECRYPT_MODE, blockSize);
            return new String(plaintext, CryptoSupport.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("RSA 解密失败，请检查私钥和密文", exception);
        }
    }

    /**
     * 使用 RSA 私钥对 UTF-8 文本生成 SHA-256 with RSA 签名。
     *
     * @param content 待签名文本，不能为 {@code null}
     * @param privateKeyBase64 Base64 编码的 PKCS#8 RSA 私钥
     * @return Base64 编码的签名
     * @throws IllegalArgumentException 参数、密钥不合法或签名失败时抛出
     */
    public static String sign(String content, String privateKeyBase64) {
        CryptoSupport.requireNonNull(content, "待签名内容");
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initSign(decodePrivateKey(privateKeyBase64));
            signature.update(content.getBytes(CryptoSupport.UTF_8));
            return CryptoSupport.encodeBase64(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("RSA 签名失败，请检查私钥", exception);
        }
    }

    /**
     * 使用 RSA 公钥验证 SHA-256 with RSA 签名。
     *
     * @param content 原始文本，不能为 {@code null}
     * @param signatureBase64 Base64 编码的签名
     * @param publicKeyBase64 Base64 编码的 X.509 RSA 公钥
     * @return 签名与内容、公钥匹配时返回 {@code true}，否则返回 {@code false}
     * @throws IllegalArgumentException 参数、Base64 或公钥格式不合法时抛出
     */
    public static boolean verify(String content, String signatureBase64, String publicKeyBase64) {
        CryptoSupport.requireNonNull(content, "验签内容");
        byte[] signatureBytes = CryptoSupport.decodeBase64(signatureBase64, "RSA 签名");
        try {
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
            signature.initVerify(decodePublicKey(publicKeyBase64));
            signature.update(content.getBytes(CryptoSupport.UTF_8));
            try {
                return signature.verify(signatureBytes);
            } catch (SignatureException exception) {
                return false;
            }
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("RSA 验签失败，请检查公钥", exception);
        }
    }

    private static byte[] processBlocks(byte[] input, java.security.Key key, int mode, int blockSize)
        throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(mode, key, OAEP_PARAMETER_SPEC);
        if (input.length == 0) {
            return cipher.doFinal(input);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int offset = 0; offset < input.length; offset += blockSize) {
            int length = Math.min(blockSize, input.length - offset);
            output.writeBytes(cipher.doFinal(input, offset, length));
        }
        return output.toByteArray();
    }

    private static PublicKey decodePublicKey(String publicKeyBase64) throws GeneralSecurityException {
        byte[] encoded = CryptoSupport.decodeBase64(publicKeyBase64, "RSA 公钥");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static PrivateKey decodePrivateKey(String privateKeyBase64) throws GeneralSecurityException {
        byte[] encoded = CryptoSupport.decodeBase64(privateKeyBase64, "RSA 私钥");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static int modulusLengthBytes(java.security.Key key) {
        if (!(key instanceof RSAKey rsaKey)) {
            throw new IllegalArgumentException("密钥不是 RSA 密钥");
        }
        return (rsaKey.getModulus().bitLength() + 7) / 8;
    }

    /**
     * Base64 编码的 RSA 密钥对。
     *
     * @param publicKey X.509 DER 公钥的标准 Base64
     * @param privateKey PKCS#8 DER 私钥的标准 Base64
     */
    public record KeyPairData(String publicKey, String privateKey) {

        /**
         * 创建 RSA 密钥对数据并校验字段。
         */
        public KeyPairData {
            CryptoSupport.requireNonBlank(publicKey, "RSA 公钥");
            CryptoSupport.requireNonBlank(privateKey, "RSA 私钥");
        }
    }
}
