package org.example.simple.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.example.simple.util.HttpResponseUtils.CookieOptions;
import org.example.simple.util.HttpResponseUtils.SameSite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link HttpResponseUtils} 的 {@link CookieOptions} 构造与 Cookie 写入测试。 */
class HttpResponseUtilsTest {

    @Test
    void cookieOptions_withNoAttributesSet_usesAllDefaults() {
        CookieOptions options = CookieOptions.builder().build();

        assertEquals(-1, options.maxAge());
        assertEquals("/", options.path());
        assertNull(options.domain());
        assertFalse(options.secure());
        assertTrue(options.httpOnly());
        assertNull(options.sameSite());
    }

    @Test
    void cookieOptions_withAllAttributesOverridden_keepsEachValue() {
        CookieOptions options = CookieOptions.builder()
                .maxAge(3600)
                .path("/app")
                .domain("example.com")
                .secure(true)
                .httpOnly(false)
                .sameSite(SameSite.LAX)
                .build();

        assertEquals(3600, options.maxAge());
        assertEquals("/app", options.path());
        assertEquals("example.com", options.domain());
        assertTrue(options.secure());
        assertFalse(options.httpOnly());
        assertEquals(SameSite.LAX, options.sameSite());
    }

    @Test
    void cookieOptions_withSameSiteNoneAndSecureTrue_buildsSuccessfully() {
        CookieOptions options = CookieOptions.builder()
                .secure(true)
                .sameSite(SameSite.NONE)
                .build();

        assertEquals(SameSite.NONE, options.sameSite());
        assertTrue(options.secure());
    }

    @Test
    void cookieOptions_withSameSiteNoneWithoutSecure_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class,
                () -> CookieOptions.builder().sameSite(SameSite.NONE).build());
    }

    @Test
    void setCookie_withFullOptions_writesCookieWithAllAttributes() {
        List<Cookie> captured = new ArrayList<>();
        HttpServletResponse response = newResponse(captured);
        CookieOptions options = CookieOptions.builder()
                .maxAge(3600)
                .path("/app")
                .domain("example.com")
                .secure(true)
                .httpOnly(true)
                .sameSite(SameSite.STRICT)
                .build();

        HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123", options);

        assertEquals(1, captured.size());
        Cookie cookie = captured.get(0);
        assertEquals("SESSION_ID", cookie.getName());
        assertEquals("abc123", cookie.getValue());
        assertEquals(3600, cookie.getMaxAge());
        assertEquals("/app", cookie.getPath());
        assertEquals("example.com", cookie.getDomain());
        assertTrue(cookie.getSecure());
        assertTrue(cookie.isHttpOnly());
        assertEquals("Strict", cookie.getAttribute("SameSite"));
    }

    @Test
    void setCookie_withoutDomainOrSameSite_doesNotSetThoseAttributes() {
        List<Cookie> captured = new ArrayList<>();
        HttpServletResponse response = newResponse(captured);

        HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123", CookieOptions.builder().build());

        Cookie cookie = captured.get(0);
        assertNull(cookie.getDomain());
        assertNull(cookie.getAttribute("SameSite"));
    }

    @Test
    void setCookie_withNullResponse_throwsIllegalArgumentException() {
        CookieOptions options = CookieOptions.builder().build();

        assertThrows(IllegalArgumentException.class,
                () -> HttpResponseUtils.setCookie(null, "SESSION_ID", "abc123", options));
    }

    @Test
    void setCookie_withNullName_throwsIllegalArgumentException() {
        HttpServletResponse response = newResponse(new ArrayList<>());
        CookieOptions options = CookieOptions.builder().build();

        assertThrows(IllegalArgumentException.class,
                () -> HttpResponseUtils.setCookie(response, null, "abc123", options));
    }

    @Test
    void setCookie_withBlankName_throwsIllegalArgumentException() {
        HttpServletResponse response = newResponse(new ArrayList<>());
        CookieOptions options = CookieOptions.builder().build();

        assertThrows(IllegalArgumentException.class,
                () -> HttpResponseUtils.setCookie(response, "  ", "abc123", options));
    }

    @Test
    void setCookie_withNullOptions_throwsIllegalArgumentException() {
        HttpServletResponse response = newResponse(new ArrayList<>());

        assertThrows(IllegalArgumentException.class,
                () -> HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123", null));
    }

    @Test
    void setCookieShortcut_writesCookieWithDefaultAttributes() {
        List<Cookie> captured = new ArrayList<>();
        HttpServletResponse response = newResponse(captured);

        HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123");

        assertEquals(1, captured.size());
        Cookie cookie = captured.get(0);
        assertEquals("SESSION_ID", cookie.getName());
        assertEquals("abc123", cookie.getValue());
        assertEquals(-1, cookie.getMaxAge());
        assertEquals("/", cookie.getPath());
        assertTrue(cookie.isHttpOnly());
    }

    @Test
    void setCookieShortcut_withNullResponse_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> HttpResponseUtils.setCookie(null, "SESSION_ID", "abc123"));
    }

    @Test
    void setCookieShortcut_withNullName_throwsIllegalArgumentException() {
        HttpServletResponse response = newResponse(new ArrayList<>());

        assertThrows(IllegalArgumentException.class, () -> HttpResponseUtils.setCookie(response, null, "abc123"));
    }

    @Test
    void setCookieShortcut_withBlankName_throwsIllegalArgumentException() {
        HttpServletResponse response = newResponse(new ArrayList<>());

        assertThrows(IllegalArgumentException.class, () -> HttpResponseUtils.setCookie(response, "  ", "abc123"));
    }

    /**
     * 基于动态代理构造只响应 {@code addCookie} 的 {@link HttpServletResponse} 测试替身，收到的 {@link Cookie}
     * 追加到 {@code captured} 供断言；其余方法调用均抛出 {@link UnsupportedOperationException}。
     *
     * @param captured 用于收集 {@code addCookie} 调用参数的列表
     * @return 构造完成的 {@link HttpServletResponse} 测试替身
     */
    private static HttpServletResponse newResponse(List<Cookie> captured) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("addCookie".equals(method.getName())) {
                captured.add((Cookie) args[0]);
                return null;
            }
            throw new UnsupportedOperationException("测试替身未实现方法: " + method.getName());
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpResponseUtilsTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                handler);
    }
}
