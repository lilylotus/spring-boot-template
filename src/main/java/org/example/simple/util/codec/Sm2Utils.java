package org.example.simple.util.codec;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jce.spec.ECParameterSpec;

/**
 * SM2 加解密、签名验签和密钥生成工具。
 * <p>
 * 公钥使用 X.509 DER，私钥使用 PKCS#8 DER，密文固定为 C1C3C2 布局；所有二进制产物均使用
 * 标准 Base64 表示。
 */
public final class Sm2Utils {

    /** SM2 密钥在 JCA 中使用的椭圆曲线密钥算法名称，用于生成和重建公私钥。 */
    private static final String KEY_ALGORITHM = "EC";

    /** 国密 SM2 标准曲线名称，用于固定密钥对的曲线参数并保证互操作性。 */
    private static final String CURVE_NAME = "sm2p256v1";

    /** SM2 签名算法名称，先使用 SM3 计算摘要，再执行 SM2 签名或验签。 */
    private static final String SIGNATURE_ALGORITHM = "SM3withSM2";

    /** 密码学安全随机源，用于生成 SM2 密钥、加密临时参数和签名随机数。 */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Sm2Utils() {
    }

    /**
     * 基于 {@code sm2p256v1} 曲线生成 SM2 密钥对。
     *
     * @return Base64 编码的 X.509 公钥和 PKCS#8 私钥
     * @throws IllegalStateException 当前运行环境无法初始化 SM2 时抛出
     */
    public static KeyPairData generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KEY_ALGORITHM, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            generator.initialize(new ECGenParameterSpec(CURVE_NAME), SECURE_RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            return new KeyPairData(
                CryptoSupport.encodeBase64(keyPair.getPublic().getEncoded()),
                CryptoSupport.encodeBase64(keyPair.getPrivate().getEncoded()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法生成 SM2 密钥对", exception);
        }
    }

    /**
     * 使用 SM2 公钥加密 UTF-8 文本，密文布局为 C1C3C2。
     *
     * @param plaintext 明文，不能为 {@code null}
     * @param publicKeyBase64 Base64 编码的 X.509 SM2 公钥
     * @return Base64 编码的 C1C3C2 密文
     * @throws IllegalArgumentException 参数、公钥不合法或加密失败时抛出
     */
    public static String encrypt(String plaintext, String publicKeyBase64) {
        CryptoSupport.requireNonNull(plaintext, "明文");
        try {
            ECPublicKeyParameters publicKey = toPublicParameters(decodePublicKey(publicKeyBase64));
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(true, new ParametersWithRandom(publicKey, SECURE_RANDOM));
            byte[] plaintextBytes = plaintext.getBytes(CryptoSupport.UTF_8);
            return CryptoSupport.encodeBase64(engine.processBlock(plaintextBytes, 0, plaintextBytes.length));
        } catch (GeneralSecurityException | InvalidCipherTextException exception) {
            throw CryptoSupport.invalidInput("SM2 加密失败，请检查公钥", exception);
        }
    }

    /**
     * 使用 SM2 私钥解密 C1C3C2 Base64 密文。
     *
     * @param ciphertextBase64 Base64 编码的 C1C3C2 密文
     * @param privateKeyBase64 Base64 编码的 PKCS#8 SM2 私钥
     * @return UTF-8 明文
     * @throws IllegalArgumentException 参数、私钥或密文不合法，或解密失败时抛出
     */
    public static String decrypt(String ciphertextBase64, String privateKeyBase64) {
        byte[] ciphertext = CryptoSupport.decodeBase64(ciphertextBase64, "SM2 密文");
        try {
            ECPrivateKeyParameters privateKey = toPrivateParameters(decodePrivateKey(privateKeyBase64));
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(false, privateKey);
            byte[] plaintext = engine.processBlock(ciphertext, 0, ciphertext.length);
            return new String(plaintext, CryptoSupport.UTF_8);
        } catch (GeneralSecurityException | InvalidCipherTextException exception) {
            throw CryptoSupport.invalidInput("SM2 解密失败，请检查私钥和密文", exception);
        }
    }

    /**
     * 使用 SM2 私钥对 UTF-8 文本生成 SM3 with SM2 DER 签名。
     *
     * @param content 待签名文本，不能为 {@code null}
     * @param privateKeyBase64 Base64 编码的 PKCS#8 SM2 私钥
     * @return Base64 编码的 DER 签名
     * @throws IllegalArgumentException 参数、私钥不合法或签名失败时抛出
     */
    public static String sign(String content, String privateKeyBase64) {
        CryptoSupport.requireNonNull(content, "待签名内容");
        try {
            Signature signature = Signature.getInstance(
                SIGNATURE_ALGORITHM, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            signature.initSign(decodePrivateKey(privateKeyBase64), SECURE_RANDOM);
            signature.update(content.getBytes(CryptoSupport.UTF_8));
            return CryptoSupport.encodeBase64(signature.sign());
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("SM2 签名失败，请检查私钥", exception);
        }
    }

    /**
     * 使用 SM2 公钥验证 SM3 with SM2 DER 签名。
     *
     * @param content 原始文本，不能为 {@code null}
     * @param signatureBase64 Base64 编码的 DER 签名
     * @param publicKeyBase64 Base64 编码的 X.509 SM2 公钥
     * @return 签名与内容、公钥匹配时返回 {@code true}，否则返回 {@code false}
     * @throws IllegalArgumentException 参数、Base64 或公钥格式不合法时抛出
     */
    public static boolean verify(String content, String signatureBase64, String publicKeyBase64) {
        CryptoSupport.requireNonNull(content, "验签内容");
        byte[] signatureBytes = CryptoSupport.decodeBase64(signatureBase64, "SM2 签名");
        try {
            Signature signature = Signature.getInstance(
                SIGNATURE_ALGORITHM, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
            signature.initVerify(decodePublicKey(publicKeyBase64));
            signature.update(content.getBytes(CryptoSupport.UTF_8));
            try {
                return signature.verify(signatureBytes);
            } catch (SignatureException exception) {
                return false;
            }
        } catch (GeneralSecurityException exception) {
            throw CryptoSupport.invalidInput("SM2 验签失败，请检查公钥", exception);
        }
    }

    private static PublicKey decodePublicKey(String publicKeyBase64) throws GeneralSecurityException {
        byte[] encoded = CryptoSupport.decodeBase64(publicKeyBase64, "SM2 公钥");
        KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static PrivateKey decodePrivateKey(String privateKeyBase64) throws GeneralSecurityException {
        byte[] encoded = CryptoSupport.decodeBase64(privateKeyBase64, "SM2 私钥");
        KeyFactory factory = KeyFactory.getInstance(KEY_ALGORITHM, CryptoSupport.BOUNCY_CASTLE_PROVIDER);
        return factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    private static ECPublicKeyParameters toPublicParameters(PublicKey publicKey) {
        if (!(publicKey instanceof BCECPublicKey ecPublicKey)) {
            throw new IllegalArgumentException("公钥不是受支持的 SM2 公钥");
        }
        return new ECPublicKeyParameters(ecPublicKey.getQ(), domainParameters(ecPublicKey.getParameters()));
    }

    private static ECPrivateKeyParameters toPrivateParameters(PrivateKey privateKey) {
        if (!(privateKey instanceof BCECPrivateKey ecPrivateKey)) {
            throw new IllegalArgumentException("私钥不是受支持的 SM2 私钥");
        }
        return new ECPrivateKeyParameters(ecPrivateKey.getD(), domainParameters(ecPrivateKey.getParameters()));
    }

    private static ECDomainParameters domainParameters(ECParameterSpec parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("SM2 密钥缺少曲线参数");
        }
        return new ECDomainParameters(
            parameters.getCurve(),
            parameters.getG(),
            parameters.getN(),
            parameters.getH(),
            parameters.getSeed());
    }

    /**
     * Base64 编码的 SM2 密钥对。
     *
     * @param publicKey X.509 DER 公钥的标准 Base64
     * @param privateKey PKCS#8 DER 私钥的标准 Base64
     */
    public record KeyPairData(String publicKey, String privateKey) {

        /**
         * 创建 SM2 密钥对数据并校验字段。
         */
        public KeyPairData {
            CryptoSupport.requireNonBlank(publicKey, "SM2 公钥");
            CryptoSupport.requireNonBlank(privateKey, "SM2 私钥");
        }
    }
}
