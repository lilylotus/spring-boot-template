package org.example.simple.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link HttpRequestUtils} 的真实 IP 解析、请求头读取和 Cookie 读取测试。 */
class HttpRequestUtilsTest {

    @Test
    void getClientIp_withoutAnyProxyHeader_returnsRemoteAddr() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertEquals("203.0.113.10", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withSingleXForwardedFor_returnsThatIp() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("X-Forwarded-For", "198.51.100.7"), null);

        assertEquals("198.51.100.7", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withMultiValueXForwardedFor_returnsFirstValidAddress() {
        HttpServletRequest request = newRequest(
                "203.0.113.10",
                Map.of("X-Forwarded-For", " unknown ,  198.51.100.7 , 198.51.100.8"),
                null);

        assertEquals("198.51.100.7", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withXRealIp_returnsThatIp() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("X-Real-IP", "198.51.100.1"), null);

        assertEquals("198.51.100.1", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withProxyClientIp_returnsThatIp() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("Proxy-Client-IP", "198.51.100.2"), null);

        assertEquals("198.51.100.2", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withWlProxyClientIp_returnsThatIp() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("WL-Proxy-Client-IP", "198.51.100.3"), null);

        assertEquals("198.51.100.3", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withHttpClientIp_returnsThatIp() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("HTTP_CLIENT_IP", "198.51.100.4"), null);

        assertEquals("198.51.100.4", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withHttpXForwardedFor_returnsThatIp() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("HTTP_X_FORWARDED_FOR", "198.51.100.5"), null);

        assertEquals("198.51.100.5", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withAllProxyHeadersBlankOrUnknown_fallsBackToRemoteAddr() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Forwarded-For", "unknown");
        headers.put("X-Real-IP", "");
        headers.put("Proxy-Client-IP", "Unknown");
        headers.put("WL-Proxy-Client-IP", "");
        headers.put("HTTP_CLIENT_IP", "UNKNOWN");
        headers.put("HTTP_X_FORWARDED_FOR", "");
        HttpServletRequest request = newRequest("203.0.113.10", headers, null);

        assertEquals("203.0.113.10", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withIpv4MappedIpv6Result_returnsIpv4PartOnly() {
        HttpServletRequest request = newRequest("::FFFF:198.51.100.9", Map.of(), null);

        assertEquals("198.51.100.9", HttpRequestUtils.getClientIp(request));
    }

    @Test
    void getClientIp_withNullRequest_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getClientIp(null));
    }

    @Test
    void getHeader_whenPresent_returnsValue() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of("X-Custom-Header", "custom-value"), null);

        assertEquals("custom-value", HttpRequestUtils.getHeader(request, "X-Custom-Header"));
    }

    @Test
    void getHeader_whenAbsent_returnsNull() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertNull(HttpRequestUtils.getHeader(request, "X-Custom-Header"));
    }

    @Test
    void getHeader_withNullRequest_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getHeader(null, "X-Custom-Header"));
    }

    @Test
    void getHeader_withNullName_throwsIllegalArgumentException() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getHeader(request, null));
    }

    @Test
    void getHeader_withBlankName_throwsIllegalArgumentException() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getHeader(request, "  "));
    }

    @Test
    void getCookieValue_whenPresent_returnsValue() {
        Cookie[] cookies = {new Cookie("session-id", "abc123")};
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), cookies);

        assertEquals("abc123", HttpRequestUtils.getCookieValue(request, "session-id"));
    }

    @Test
    void getCookieValue_whenNoCookiesAtAll_returnsNull() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertNull(HttpRequestUtils.getCookieValue(request, "session-id"));
    }

    @Test
    void getCookieValue_whenNoMatchingName_returnsNull() {
        Cookie[] cookies = {new Cookie("other", "value")};
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), cookies);

        assertNull(HttpRequestUtils.getCookieValue(request, "session-id"));
    }

    @Test
    void getCookieValue_withMultipleSameNameCookies_returnsFirstOne() {
        Cookie[] cookies = {new Cookie("session-id", "first"), new Cookie("session-id", "second")};
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), cookies);

        assertEquals("first", HttpRequestUtils.getCookieValue(request, "session-id"));
    }

    @Test
    void getCookieValue_withNullRequest_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getCookieValue(null, "session-id"));
    }

    @Test
    void getCookieValue_withNullName_throwsIllegalArgumentException() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getCookieValue(request, null));
    }

    @Test
    void getCookieValue_withBlankName_throwsIllegalArgumentException() {
        HttpServletRequest request = newRequest("203.0.113.10", Map.of(), null);

        assertThrows(IllegalArgumentException.class, () -> HttpRequestUtils.getCookieValue(request, "  "));
    }

    /**
     * 基于动态代理构造只响应 {@code getHeader}/{@code getRemoteAddr}/{@code getCookies} 的
     * {@link HttpServletRequest} 测试替身，其余方法调用均抛出 {@link UnsupportedOperationException}。
     *
     * @param remoteAddr 模拟的 {@code getRemoteAddr()} 返回值
     * @param headers 模拟的请求头集合
     * @param cookies 模拟的 {@code getCookies()} 返回值，传入 {@code null} 表示请求未携带任何 Cookie
     * @return 构造完成的 {@link HttpServletRequest} 测试替身
     */
    private static HttpServletRequest newRequest(String remoteAddr, Map<String, String> headers, Cookie[] cookies) {
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getRemoteAddr":
                    return remoteAddr;
                case "getHeader":
                    return headers.get((String) args[0]);
                case "getCookies":
                    return cookies;
                default:
                    throw new UnsupportedOperationException(
                            "测试替身未实现方法: " + method.getName());
            }
        };
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpRequestUtilsTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                handler);
    }
}
