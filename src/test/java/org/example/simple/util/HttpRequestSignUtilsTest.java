package org.example.simple.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import jakarta.servlet.http.HttpServletRequest;
import org.example.simple.util.HttpRequestSignUtils.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HttpRequestSignUtils} 的签名计算、验签与 {@link HttpServletRequest} 便捷验签测试。 */
class HttpRequestSignUtilsTest {

    private static final String ACCESS_KEY = "test-access-key";
    private static final String SECRET_KEY = "test-secret-key";

    @Test
    void sign_withSameInputs_producesSameSignature() {
        Map<String, String> bizParams = params("a", "1", "b", "2");

        String first = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);
        String second = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);

        assertEquals(first, second);
    }

    @Test
    void sign_withDifferentBizParamInsertionOrder_producesSameSignature() {
        Map<String, String> inOrder = params("a", "1", "b", "2");
        Map<String, String> reversed = params("b", "2", "a", "1");

        String signed1 = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, inOrder, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);
        String signed2 = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, reversed, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);

        assertEquals(signed1, signed2);
    }

    @Test
    void sign_withDifferentMethod_producesDifferentSignature() {
        Map<String, String> bizParams = params("a", "1");

        String getSign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);
        String postSign = HttpRequestSignUtils.sign(HttpMethod.POST, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);

        assertNotEquals(getSign, postSign);
    }

    @Test
    void sign_withDifferentAccessKey_producesDifferentSignature() {
        Map<String, String> bizParams = params("a", "1");

        String signed1 = HttpRequestSignUtils.sign(HttpMethod.GET, "access-key-1", bizParams, null,
                1_700_000_000_000L, "nonce-1", SECRET_KEY);
        String signed2 = HttpRequestSignUtils.sign(HttpMethod.GET, "access-key-2", bizParams, null,
                1_700_000_000_000L, "nonce-1", SECRET_KEY);

        assertNotEquals(signed1, signed2);
    }

    @Test
    void sign_withDifferentBody_producesDifferentSignature() {
        Map<String, String> bizParams = params("a", "1");

        String signed1 = HttpRequestSignUtils.sign(HttpMethod.POST, ACCESS_KEY, bizParams, "{\"x\":1}",
                1_700_000_000_000L, "nonce-1", SECRET_KEY);
        String signed2 = HttpRequestSignUtils.sign(HttpMethod.POST, ACCESS_KEY, bizParams, "{\"x\":2}",
                1_700_000_000_000L, "nonce-1", SECRET_KEY);

        assertNotEquals(signed1, signed2);
    }

    @Test
    void sign_withDifferentTimestampOrNonce_producesDifferentSignature() {
        Map<String, String> bizParams = params("a", "1");

        String base = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);
        String differentTimestamp = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                1_700_000_000_001L, "nonce-1", SECRET_KEY);
        String differentNonce = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                1_700_000_000_000L, "nonce-2", SECRET_KEY);

        assertNotEquals(base, differentTimestamp);
        assertNotEquals(base, differentNonce);
    }

    @Test
    void sign_withNullBodyAndEmptyBody_producesSameSignature() {
        Map<String, String> bizParams = params("a", "1");

        String withNull = HttpRequestSignUtils.sign(HttpMethod.POST, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);
        String withEmpty = HttpRequestSignUtils.sign(HttpMethod.POST, ACCESS_KEY, bizParams, "", 1_700_000_000_000L,
                "nonce-1", SECRET_KEY);

        assertEquals(withNull, withEmpty);
    }

    @Test
    void sign_withBizParamReservedKeyName_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("accessKey", "forged");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                        "nonce-1", SECRET_KEY));
    }

    @Test
    void sign_withSignReservedKeyName_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("sign", "forged");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, 1_700_000_000_000L,
                        "nonce-1", SECRET_KEY));
    }

    @Test
    void sign_withNullMethod_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(null, ACCESS_KEY, params("a", "1"), null, 1_700_000_000_000L,
                        "nonce-1", SECRET_KEY));
    }

    @Test
    void sign_withBlankAccessKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, "  ", params("a", "1"), null, 1_700_000_000_000L,
                        "nonce-1", SECRET_KEY));
    }

    @Test
    void sign_withNullBizParams_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, null, null, 1_700_000_000_000L,
                        "nonce-1", SECRET_KEY));
    }

    @Test
    void sign_withBlankSecretKey_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, params("a", "1"), null,
                        1_700_000_000_000L, "nonce-1", "  "));
    }

    @Test
    void sign_withBlankNonce_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, params("a", "1"), null,
                        1_700_000_000_000L, " ", SECRET_KEY));
    }

    @Test
    void sign_withNullKeyOrValueInBizParams_throwsIllegalArgumentException() {
        Map<String, String> withNullValue = new LinkedHashMap<>();
        withNullValue.put("a", null);

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, withNullValue, null, 1_700_000_000_000L,
                        "nonce-1", SECRET_KEY));
    }

    @Test
    void signToParams_returnsMapWithAllMetadataAndVerifiesSuccessfully() {
        Map<String, String> bizParams = params("a", "1", "b", "2");

        Map<String, String> result = HttpRequestSignUtils.signToParams(HttpMethod.PUT, ACCESS_KEY, bizParams,
                "{\"x\":1}", SECRET_KEY);

        assertEquals(ACCESS_KEY, result.get("accessKey"));
        assertTrue(result.containsKey("timestamp"));
        assertTrue(result.containsKey("nonce"));
        assertTrue(result.containsKey("sign"));

        long timestamp = Long.parseLong(result.get("timestamp"));
        boolean valid = HttpRequestSignUtils.verify(HttpMethod.PUT, ACCESS_KEY, bizParams, "{\"x\":1}", timestamp,
                result.get("nonce"), SECRET_KEY, result.get("sign"), 300_000);

        assertTrue(valid);
    }

    @Test
    void signToParams_withBizParamReservedKeyName_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("nonce", "forged");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null, SECRET_KEY));
    }

    @Test
    void verify_withMatchingSignatureWithinWindow_returnsTrue() {
        Map<String, String> bizParams = params("a", "1", "b", "2");
        long timestamp = System.currentTimeMillis();
        String sign = HttpRequestSignUtils.sign(HttpMethod.PUT, ACCESS_KEY, bizParams, "{\"x\":1}", timestamp,
                "nonce-1", SECRET_KEY);

        boolean result = HttpRequestSignUtils.verify(HttpMethod.PUT, ACCESS_KEY, bizParams, "{\"x\":1}", timestamp,
                "nonce-1", SECRET_KEY, sign, 300_000);

        assertTrue(result);
    }

    @Test
    void verify_withTamperedAccessKey_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        long timestamp = System.currentTimeMillis();
        String sign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp, "nonce-1",
                SECRET_KEY);

        boolean result = HttpRequestSignUtils.verify(HttpMethod.GET, "other-access-key", bizParams, null, timestamp,
                "nonce-1", SECRET_KEY, sign, 300_000);

        assertFalse(result);
    }

    @Test
    void verify_withTamperedBizParams_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        long timestamp = System.currentTimeMillis();
        String sign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp, "nonce-1",
                SECRET_KEY);

        Map<String, String> tampered = params("a", "2");
        boolean result = HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, tampered, null, timestamp,
                "nonce-1", SECRET_KEY, sign, 300_000);

        assertFalse(result);
    }

    @Test
    void verify_withTamperedBody_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        long timestamp = System.currentTimeMillis();
        String sign = HttpRequestSignUtils.sign(HttpMethod.POST, ACCESS_KEY, bizParams, "{\"x\":1}", timestamp,
                "nonce-1", SECRET_KEY);

        boolean result = HttpRequestSignUtils.verify(HttpMethod.POST, ACCESS_KEY, bizParams, "{\"x\":2}", timestamp,
                "nonce-1", SECRET_KEY, sign, 300_000);

        assertFalse(result);
    }

    @Test
    void verify_withWrongSecretKey_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        long timestamp = System.currentTimeMillis();
        String sign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp, "nonce-1",
                SECRET_KEY);

        boolean result = HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp,
                "nonce-1", "wrong-secret", sign, 300_000);

        assertFalse(result);
    }

    @Test
    void verify_withTimestampOutsideDriftWindow_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        long staleTimestamp = System.currentTimeMillis() - 10_000_000;
        String sign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, staleTimestamp,
                "nonce-1", SECRET_KEY);

        boolean result = HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null, staleTimestamp,
                "nonce-1", SECRET_KEY, sign, 300_000);

        assertFalse(result);
    }

    @Test
    void verify_withTimestampAtBoundary_returnsTrue() {
        Map<String, String> bizParams = params("a", "1");
        // 留出余量而非卡在精确边界：验证断言执行到真正调用 verify() 之间会消耗几毫秒真实时间，
        // 用恰好等于 maxTimestampDriftMillis 的偏移量会使该间隙导致测试偶发失败。
        long timestamp = System.currentTimeMillis() - 290_000;
        String sign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp, "nonce-1",
                SECRET_KEY);

        boolean result = HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp,
                "nonce-1", SECRET_KEY, sign, 300_000);

        assertTrue(result);
    }

    @Test
    void verify_withInvalidBase64Sign_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("a", "1");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                        System.currentTimeMillis(), "nonce-1", SECRET_KEY, "not-valid-base64!!", 300_000));
    }

    @Test
    void verify_withNegativeMaxDrift_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("a", "1");
        long timestamp = System.currentTimeMillis();
        String sign = HttpRequestSignUtils.sign(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp, "nonce-1",
                SECRET_KEY);

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null, timestamp, "nonce-1",
                        SECRET_KEY, sign, -1));
    }

    @Test
    void verify_withBlankSign_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("a", "1");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                        System.currentTimeMillis(), "nonce-1", SECRET_KEY, "  ", 300_000));
    }

    @Test
    void endToEnd_getRequestWithoutBody_signsAndVerifiesSuccessfully() {
        Map<String, String> bizParams = params("userId", "42", "page", "1");

        Map<String, String> result = HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                SECRET_KEY);

        assertTrue(HttpRequestSignUtils.verify(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                Long.parseLong(result.get("timestamp")), result.get("nonce"), SECRET_KEY, result.get("sign"),
                300_000));
    }

    @Test
    void endToEnd_postRequestWithJsonBodyAndNoBizParams_signsAndVerifiesSuccessfully() {
        Map<String, String> bizParams = Map.of();
        String body = "{\"name\":\"foo\",\"value\":1}";

        Map<String, String> result = HttpRequestSignUtils.signToParams(HttpMethod.POST, ACCESS_KEY, bizParams, body,
                SECRET_KEY);

        assertTrue(HttpRequestSignUtils.verify(HttpMethod.POST, ACCESS_KEY, bizParams, body,
                Long.parseLong(result.get("timestamp")), result.get("nonce"), SECRET_KEY, result.get("sign"),
                300_000));
    }

    @Test
    void endToEnd_putRequestWithBizParamsAndBody_signsAndVerifiesSuccessfully() {
        Map<String, String> bizParams = params("id", "100");
        String body = "{\"status\":\"active\"}";

        Map<String, String> result = HttpRequestSignUtils.signToParams(HttpMethod.PUT, ACCESS_KEY, bizParams, body,
                SECRET_KEY);

        assertTrue(HttpRequestSignUtils.verify(HttpMethod.PUT, ACCESS_KEY, bizParams, body,
                Long.parseLong(result.get("timestamp")), result.get("nonce"), SECRET_KEY, result.get("sign"),
                300_000));
    }

    @Test
    void verifyFromRequest_withValidRequest_returnsTrue() {
        Map<String, String> bizParams = params("a", "1", "b", "2");
        String body = "{\"x\":1}";

        Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.PUT, ACCESS_KEY, bizParams, body,
                SECRET_KEY);

        HttpServletRequest request = newRequest("PUT", bizParams, body, metadata);

        assertTrue(HttpRequestSignUtils.verify(request, key -> ACCESS_KEY.equals(key) ? SECRET_KEY : null, 300_000));
    }

    @Test
    void verifyFromRequest_withUnsupportedMethod_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                SECRET_KEY);

        HttpServletRequest request = newRequest("DELETE", bizParams, null, metadata);

        assertFalse(HttpRequestSignUtils.verify(request, key -> SECRET_KEY, 300_000));
    }

    @Test
    void verifyFromRequest_withMissingMetadataHeader_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = new LinkedHashMap<>(
                HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null, SECRET_KEY));
        metadata.remove(HttpRequestSignUtils.HEADER_NONCE);

        HttpServletRequest request = newRequest("GET", bizParams, null, metadata);

        assertFalse(HttpRequestSignUtils.verify(request, key -> SECRET_KEY, 300_000));
    }

    @Test
    void verifyFromRequest_withNonNumericTimestampHeader_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = new LinkedHashMap<>(
                HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null, SECRET_KEY));
        metadata.put(HttpRequestSignUtils.HEADER_TIMESTAMP, "not-a-number");

        HttpServletRequest request = newRequest("GET", bizParams, null, metadata);

        assertFalse(HttpRequestSignUtils.verify(request, key -> SECRET_KEY, 300_000));
    }

    @Test
    void verifyFromRequest_withInvalidBase64SignHeader_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = new LinkedHashMap<>(
                HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null, SECRET_KEY));
        metadata.put(HttpRequestSignUtils.HEADER_SIGNATURE, "not-valid-base64!!");

        HttpServletRequest request = newRequest("GET", bizParams, null, metadata);

        assertFalse(HttpRequestSignUtils.verify(request, key -> SECRET_KEY, 300_000));
    }

    @Test
    void verifyFromRequest_withUnresolvableAccessKey_returnsFalse() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                SECRET_KEY);

        HttpServletRequest request = newRequest("GET", bizParams, null, metadata);

        assertFalse(HttpRequestSignUtils.verify(request, key -> null, 300_000));
    }

    @Test
    void verifyFromRequest_withNullRequest_throwsIllegalArgumentException() {
        Function<String, String> resolver = key -> SECRET_KEY;

        assertThrows(IllegalArgumentException.class, () -> HttpRequestSignUtils.verify(null, resolver, 300_000));
    }

    @Test
    void verifyFromRequest_withNullResolver_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                SECRET_KEY);
        HttpServletRequest request = newRequest("GET", bizParams, null, metadata);

        assertThrows(IllegalArgumentException.class, () -> HttpRequestSignUtils.verify(request, null, 300_000));
    }

    @Test
    void verifyFromRequest_withNegativeMaxDrift_throwsIllegalArgumentException() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                SECRET_KEY);
        HttpServletRequest request = newRequest("GET", bizParams, null, metadata);
        Function<String, String> resolver = key -> SECRET_KEY;

        assertThrows(IllegalArgumentException.class, () -> HttpRequestSignUtils.verify(request, resolver, -1));
    }

    @Test
    void verifyFromRequest_withBodyReadFailure_throwsIllegalStateException() {
        Map<String, String> bizParams = params("a", "1");
        Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.GET, ACCESS_KEY, bizParams, null,
                SECRET_KEY);
        HttpServletRequest request = newFailingBodyRequest("GET", bizParams, metadata);
        Function<String, String> resolver = key -> SECRET_KEY;

        assertThrows(IllegalStateException.class, () -> HttpRequestSignUtils.verify(request, resolver, 300_000));
    }

    private static Map<String, String> params(String... keyValuePairs) {
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            params.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return params;
    }

    /**
     * 基于动态代理构造 {@link HttpServletRequest} 测试替身，响应 {@code getMethod}/{@code getHeader}/
     * {@code getParameterMap}/{@code getReader}：{@code headers} 承载 {@code accessKey}/{@code timestamp}/
     * {@code nonce}/{@code sign} 四个元数据请求头，{@code bizParams} 承载查询/表单参数；其余方法调用均抛出
     * {@link UnsupportedOperationException}。
     */
    private static HttpServletRequest newRequest(String method, Map<String, String> bizParams, String body,
            Map<String, String> headers) {
        Map<String, String[]> parameterMap = new LinkedHashMap<>();
        bizParams.forEach((key, value) -> parameterMap.put(key, new String[] {value}));

        InvocationHandler handler = (proxy, invokedMethod, args) -> {
            switch (invokedMethod.getName()) {
                case "getMethod":
                    return method;
                case "getHeader":
                    return headers.get((String) args[0]);
                case "getParameterMap":
                    return parameterMap;
                case "getReader":
                    return new BufferedReader(new StringReader(body == null ? "" : body));
                default:
                    throw new UnsupportedOperationException("测试替身未实现方法: " + invokedMethod.getName());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpRequestSignUtilsTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                handler);
    }

    private static HttpServletRequest newFailingBodyRequest(String method, Map<String, String> bizParams,
            Map<String, String> headers) {
        Map<String, String[]> parameterMap = new LinkedHashMap<>();
        bizParams.forEach((key, value) -> parameterMap.put(key, new String[] {value}));

        InvocationHandler handler = (proxy, invokedMethod, args) -> {
            switch (invokedMethod.getName()) {
                case "getMethod":
                    return method;
                case "getHeader":
                    return headers.get((String) args[0]);
                case "getParameterMap":
                    return parameterMap;
                case "getReader":
                    throw new IOException("模拟连接中断");
                default:
                    throw new UnsupportedOperationException("测试替身未实现方法: " + invokedMethod.getName());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpRequestSignUtilsTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                handler);
    }
}
