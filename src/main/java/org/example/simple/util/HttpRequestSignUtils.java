package org.example.simple.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HTTP 请求签名与验签工具类：对 {@code GET}/{@code POST}/{@code PUT} 请求的调用方身份（{@code accessKey}）、
 * 业务参数与请求体计算 HMAC-SHA256 签名，并内置基于时间窗口的防重放校验。
 * <p>
 * 签名算法：把 {@code method}（请求方法）、{@code accessKey}、{@code timestamp}（毫秒级时间戳的十进制字符串）、
 * {@code nonce}、{@code bodyDigest}（请求体的 SHA-256 摘要，标准 Base64；{@code body} 为 {@code null} 时按空字符串
 * 计算，与空字符串请求体产生相同摘要）这五个"机制参数"，和调用方传入的业务参数 {@code bizParams} 合并为同一个
 * {@code Map}，统一按参数名的 {@code String} 自然顺序排序，键值分别用 {@link URLEncoder} 百分号编码后以
 * {@code key=value} 形式用 {@code &} 连接成唯一的待签字符串，再用 {@code secretKey} 作为密钥计算 HMAC-SHA256，
 * 以标准 Base64 返回签名。{@code bizParams} 中如果出现与上述五个机制参数或最终发送时使用的 {@code sign} 字段同名的
 * key，视为用法错误，直接抛出 {@link IllegalArgumentException}，不会被静默覆盖。
 * <p>
 * 签名密钥 {@code secretKey} 按原始 UTF-8 字符串处理，不要求是 Base64（与 {@code org.example.simple.util.codec}
 * 包内密码学工具"密钥必须是标准 Base64"的约定不同——这里的密钥通常是业务侧按 {@code accessKey} 签发给调用方的
 * {@code secretKey} 字符串）。{@code accessKey} → {@code secretKey} 的查找/管理由调用方自行负责，本工具不持有
 * 任何密钥存储。
 * <p>
 * {@link #signToParams} 自动生成当前时间戳和一次性随机串，返回 {@code accessKey}/{@code timestamp}/{@code nonce}/
 * {@code sign} 四个键值对，调用方应把它们分别设置为 {@link #HEADER_ACCESS_KEY}/{@link #HEADER_TIMESTAMP}/
 * {@link #HEADER_NONCE}/{@link #HEADER_SIGNATURE} 四个请求头；业务参数 {@code bizParams} 和请求体仍按原有方式
 * （查询/表单参数、请求体）传递，不放进请求头。{@link #sign(HttpMethod, String, Map, String, long, String, String)}
 * 是需要自行控制 {@code timestamp}/{@code nonce} 时使用的底层方法，也是 {@link #signToParams} 的实现基础。
 * <p>
 * {@link #verify(HttpMethod, String, Map, String, long, String, String, String, long)} 先校验 {@code timestamp}
 * 与当前时间的差值（毫秒）是否超过 {@code maxTimestampDriftMillis}，超出直接判定验签失败；未超出则重新计算签名并与
 * 传入的 {@code sign} 做常数时间比较。时间窗口超出和签名内容不匹配统一返回 {@code false}，不向调用方区分具体原因。
 * <p>
 * 本工具只负责"签名不可被篡改"和"签名只在时间窗口内有效"，不做 {@code nonce} 去重存储——防止同一笔请求在时间窗口内
 * 被重复提交需要调用方自行用带 TTL 的缓存/数据库记录已出现过的 {@code nonce}（如 Redis {@code SETNX}），本工具作为
 * 无状态静态工具类不持有任何状态。
 * <p>
 * 本工具类无状态，所有方法均可被多线程并发调用。
 */
public final class HttpRequestSignUtils {

    /** 调用方身份标识使用的固定请求头名称，客户端发起请求时应设置该请求头 */
    public static final String HEADER_ACCESS_KEY = "accessKey";

    /** 请求签名时间戳（Unix 毫秒）使用的固定请求头名称，客户端发起请求时应设置该请求头 */
    public static final String HEADER_TIMESTAMP = "timestamp";

    /** 请求签名一次性随机串使用的固定请求头名称，客户端发起请求时应设置该请求头 */
    public static final String HEADER_NONCE = "nonce";

    /** 请求签名结果使用的固定请求头名称，客户端发起请求时应设置该请求头 */
    public static final String HEADER_SIGNATURE = "sign";

    /** 签名/验签使用的 HMAC 算法 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 请求体摘要使用的算法 */
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final String PARAM_METHOD = "method";
    private static final String PARAM_ACCESS_KEY = "accessKey";
    private static final String PARAM_TIMESTAMP = "timestamp";
    private static final String PARAM_NONCE = "nonce";
    private static final String PARAM_BODY_DIGEST = "bodyDigest";
    private static final String PARAM_SIGN = "sign";

    /** 机制参数与最终发送字段保留的名称，{@code bizParams} 不能使用这些 key */
    private static final Set<String> RESERVED_PARAM_NAMES = Set.of(
            PARAM_METHOD, PARAM_ACCESS_KEY, PARAM_TIMESTAMP, PARAM_NONCE, PARAM_BODY_DIGEST, PARAM_SIGN);

    private HttpRequestSignUtils() {
    }

    /**
     * 对调用方身份、业务参数、请求体、时间戳与随机串计算 HMAC-SHA256 签名。
     *
     * @param method 请求方法，不能为 {@code null}
     * @param accessKey 调用方身份标识，不能为 {@code null} 或空白字符串
     * @param bizParams 业务参数，不能为 {@code null}（可以是空 Map），不能包含 {@code null} 键/值，
     *     也不能包含与机制参数或 {@code sign} 同名的 key
     * @param body 请求体原始文本；{@code null} 表示该请求没有请求体（典型为 {@code GET}），与空字符串 {@code ""}
     *     产生相同签名
     * @param timestamp 签名时的毫秒级 Unix 时间戳
     * @param nonce 一次性随机串，不能为 {@code null} 或空白字符串
     * @param secretKey 签名密钥，按原始 UTF-8 字符串处理，不能为 {@code null} 或空白字符串
     * @return 标准 Base64 编码的签名
     * @throws IllegalArgumentException 当必填参数为 {@code null}/空白字符串，或 {@code bizParams} 中存在
     *     {@code null} 键/值或与机制参数同名的 key 时抛出
     */
    public static String sign(HttpMethod method, String accessKey, Map<String, String> bizParams, String body,
            long timestamp, String nonce, String secretKey) {
        validateCommon(method, accessKey, bizParams, secretKey, nonce);
        String canonical = buildCanonicalString(method, accessKey, bizParams, body, timestamp, nonce);
        return Base64.getEncoder().encodeToString(hmacSha256(canonical, secretKey));
    }

    /**
     * 自动生成当前毫秒级时间戳与一次性随机串，计算签名后返回可直接合并进请求参数的 {@code Map}。
     *
     * @param method 请求方法，不能为 {@code null}
     * @param accessKey 调用方身份标识，不能为 {@code null} 或空白字符串
     * @param bizParams 业务参数，约束同 {@link #sign(HttpMethod, String, Map, String, long, String, String)}
     * @param body 请求体原始文本，可为 {@code null}
     * @param secretKey 签名密钥，不能为 {@code null} 或空白字符串
     * @return 包含 {@code accessKey}/{@code timestamp}/{@code nonce}/{@code sign} 四个键值对的 {@code Map}
     * @throws IllegalArgumentException 约束同 {@link #sign(HttpMethod, String, Map, String, long, String, String)}
     */
    public static Map<String, String> signToParams(HttpMethod method, String accessKey, Map<String, String> bizParams,
            String body, String secretKey) {
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String signature = sign(method, accessKey, bizParams, body, timestamp, nonce, secretKey);

        Map<String, String> result = new LinkedHashMap<>(8);
        result.put(PARAM_ACCESS_KEY, accessKey);
        result.put(PARAM_TIMESTAMP, Long.toString(timestamp));
        result.put(PARAM_NONCE, nonce);
        result.put(PARAM_SIGN, signature);
        return result;
    }

    /**
     * 校验请求签名与时间窗口。
     * <p>
     * 先校验 {@code timestamp} 与当前时间的差值（毫秒）是否不超过 {@code maxTimestampDriftMillis}，超出范围直接
     * 返回 {@code false}；未超出范围则按 {@link #sign} 同样的规则重新计算签名，并与 {@code sign} 参数做常数时间比较。
     *
     * @param method 请求方法，不能为 {@code null}
     * @param accessKey 调用方身份标识，不能为 {@code null} 或空白字符串
     * @param bizParams 业务参数，约束同 {@link #sign(HttpMethod, String, Map, String, long, String, String)}
     * @param body 请求体原始文本；{@code null} 与空字符串 {@code ""} 等价
     * @param timestamp 签名时的毫秒级 Unix 时间戳
     * @param nonce 一次性随机串，不能为 {@code null} 或空白字符串
     * @param secretKey 签名密钥，不能为 {@code null} 或空白字符串
     * @param sign 待验证的标准 Base64 签名，不能为 {@code null} 或空白字符串
     * @param maxTimestampDriftMillis 允许的时间戳偏差（毫秒），不能为负数
     * @return 签名有效且 {@code timestamp} 在允许窗口内时返回 {@code true}；时间戳超窗或签名内容不匹配时返回
     *     {@code false}（不区分具体原因）
     * @throws IllegalArgumentException 当必填参数为 {@code null}/空白字符串，{@code bizParams} 不合法，
     *     {@code sign} 不是合法标准 Base64，或 {@code maxTimestampDriftMillis} 为负数时抛出
     */
    public static boolean verify(HttpMethod method, String accessKey, Map<String, String> bizParams, String body,
            long timestamp, String nonce, String secretKey, String sign, long maxTimestampDriftMillis) {
        validateCommon(method, accessKey, bizParams, secretKey, nonce);
        if (sign == null || sign.isBlank()) {
            throw new IllegalArgumentException("sign 不能为 null 或空白字符串");
        }
        if (maxTimestampDriftMillis < 0) {
            throw new IllegalArgumentException("maxTimestampDriftMillis 不能为负数");
        }

        byte[] providedSignature;
        try {
            providedSignature = Base64.getDecoder().decode(sign);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sign 不是合法的标准 Base64", exception);
        }

        long nowMillis = System.currentTimeMillis();
        if (Math.abs(nowMillis - timestamp) > maxTimestampDriftMillis) {
            return false;
        }

        byte[] expectedSignature = Base64.getDecoder()
                .decode(sign(method, accessKey, bizParams, body, timestamp, nonce, secretKey));
        return MessageDigest.isEqual(providedSignature, expectedSignature);
    }

    /**
     * 从 {@link HttpServletRequest} 直接提取签名材料、按 {@code accessKey} 解析密钥并完成验签。
     * <p>
     * 提取规则：{@code method} 取 {@code request.getMethod()}；{@code accessKey}/{@code timestamp}/{@code nonce}/
     * {@code sign} 分别从 {@link #HEADER_ACCESS_KEY}/{@link #HEADER_TIMESTAMP}/{@link #HEADER_NONCE}/
     * {@link #HEADER_SIGNATURE} 四个固定请求头读取；{@code bizParams} 取自 {@code request.getParameterMap()}
     * （查询/表单参数，不读取其余请求头）；{@code body} 读取请求体原始文本。取到 {@code accessKey} 后调用
     * {@code secretKeyResolver.apply(accessKey)} 解析 {@code secretKey}。
     * <p>
     * 提取或解析签名材料失败（请求方法不是 {@code GET}/{@code POST}/{@code PUT}、缺失上述任一请求头、
     * {@code timestamp} 不是合法十进制数字、{@code sign} 不是合法 Base64、{@code secretKeyResolver} 对给定
     * {@code accessKey} 返回 {@code null}）均返回 {@code false}，不抛出异常——这些值全部来自客户端可控的请求内容或
     * 业务侧的密钥管理结果，缺失或畸形本身就代表"这不是一个可以通过验签的请求"。只有 {@code request}/
     * {@code secretKeyResolver} 为 {@code null}，或 {@code maxTimestampDriftMillis} 为负数这类调用方自身的编程
     * 错误才抛出 {@code IllegalArgumentException}。
     * <p>
     * 本方法只能读取一次请求体：如果调用方在本方法之前已经读取过请求体，或请求的 {@code Content-Type} 是
     * {@code application/x-www-form-urlencoded}（Servlet 容器会在调用 {@code getParameterMap()} 时提前消费请求体
     * 输入流解析为参数），本方法读到的请求体会是空结果，导致验签失败；本方法面向查询参数走 URL、请求体是原始 JSON/
     * 文本的签名场景，不支持表单编码请求体。
     *
     * @param request 入站 HTTP 请求，不能为 {@code null}
     * @param secretKeyResolver 按 {@code accessKey} 解析 {@code secretKey} 的函数，不能为 {@code null}；
     *     解析不到（返回 {@code null}）视为验签失败
     * @param maxTimestampDriftMillis 允许的时间戳偏差（毫秒），不能为负数
     * @return 验签通过返回 {@code true}；签名材料缺失/格式非法、{@code accessKey} 无法解析、时间戳超窗或签名内容
     *     不匹配均返回 {@code false}
     * @throws IllegalArgumentException 当 {@code request}/{@code secretKeyResolver} 为 {@code null}，或
     *     {@code maxTimestampDriftMillis} 为负数时抛出
     * @throws IllegalStateException 读取请求体过程中发生 {@code IOException} 时抛出
     */
    public static boolean verify(HttpServletRequest request, Function<String, String> secretKeyResolver,
            long maxTimestampDriftMillis) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        if (secretKeyResolver == null) {
            throw new IllegalArgumentException("secretKeyResolver 不能为 null");
        }
        if (maxTimestampDriftMillis < 0) {
            throw new IllegalArgumentException("maxTimestampDriftMillis 不能为负数");
        }

        HttpMethod method = parseMethod(request.getMethod());
        if (method == null) {
            return false;
        }

        String accessKey = request.getHeader(HEADER_ACCESS_KEY);
        String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String sign = request.getHeader(HEADER_SIGNATURE);
        if (isBlank(accessKey) || isBlank(timestampHeader) || isBlank(nonce) || isBlank(sign)) {
            return false;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException exception) {
            return false;
        }

        try {
            Base64.getDecoder().decode(sign);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        String secretKey = secretKeyResolver.apply(accessKey);
        if (secretKey == null) {
            return false;
        }

        Map<String, String> bizParams = extractParams(request);
        String body = readBody(request);

        return verify(method, accessKey, bizParams, body, timestamp, nonce, secretKey, sign, maxTimestampDriftMillis);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateCommon(HttpMethod method, String accessKey, Map<String, String> bizParams,
            String secretKey, String nonce) {
        if (method == null) {
            throw new IllegalArgumentException("method 不能为 null");
        }
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("accessKey 不能为 null 或空白字符串");
        }
        if (bizParams == null) {
            throw new IllegalArgumentException("bizParams 不能为 null");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("secretKey 不能为 null 或空白字符串");
        }
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("nonce 不能为 null 或空白字符串");
        }
    }

    /**
     * 构建签名待签字符串，参见类级说明的算法定义。
     *
     * @throws IllegalArgumentException 当 {@code bizParams} 中存在 {@code null} 键/值，或存在与机制参数/
     *     {@code sign} 同名的 key 时抛出
     */
    private static String buildCanonicalString(HttpMethod method, String accessKey, Map<String, String> bizParams,
            String body, long timestamp, String nonce) {
        Map<String, String> combined = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : bizParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) {
                throw new IllegalArgumentException("bizParams 不能包含 null 键或 null 值");
            }
            if (RESERVED_PARAM_NAMES.contains(key)) {
                throw new IllegalArgumentException("bizParams 不能包含与机制参数同名的 key: " + key);
            }
            combined.put(key, value);
        }
        combined.put(PARAM_METHOD, method.name());
        combined.put(PARAM_ACCESS_KEY, accessKey);
        combined.put(PARAM_TIMESTAMP, Long.toString(timestamp));
        combined.put(PARAM_NONCE, nonce);
        combined.put(PARAM_BODY_DIGEST, bodyDigest(body));

        return combined.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String bodyDigest(String body) {
        String content = body == null ? "" : body;
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            return Base64.getEncoder().encodeToString(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境无法计算 " + DIGEST_ALGORITHM, exception);
        }
    }

    private static byte[] hmacSha256(String content, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前运行环境无法计算 " + HMAC_ALGORITHM, exception);
        }
    }

    private static HttpMethod parseMethod(String rawMethod) {
        if (rawMethod == null) {
            return null;
        }
        try {
            return HttpMethod.valueOf(rawMethod.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Map<String, String> extractParams(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            String[] values = entry.getValue();
            params.put(entry.getKey(), values != null && values.length > 0 ? values[0] : "");
        }
        return params;
    }

    private static String readBody(HttpServletRequest request) {
        try (BufferedReader reader = request.getReader()) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
            return content.toString();
        } catch (IOException exception) {
            throw new IllegalStateException("读取请求体失败", exception);
        }
    }

    /**
     * 本工具支持签名/验签的 HTTP 方法。
     */
    public enum HttpMethod {
        GET,
        POST,
        PUT
    }
}
