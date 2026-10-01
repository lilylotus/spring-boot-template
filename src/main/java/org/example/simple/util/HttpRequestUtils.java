package org.example.simple.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 入站 HTTP 请求读取工具类：解析访问者真实 IP、读取指定请求头、读取指定 Cookie 值。
 * <p>
 * 真实 IP 解析按固定优先级依次检查 {@value #HEADER_X_FORWARDED_FOR}、{@value #HEADER_X_REAL_IP}、
 * {@value #HEADER_PROXY_CLIENT_IP}、{@value #HEADER_WL_PROXY_CLIENT_IP}、{@value #HEADER_HTTP_CLIENT_IP}、
 * {@value #HEADER_HTTP_X_FORWARDED_FOR} 请求头，取到非空且不等于（忽略大小写）{@code unknown} 的值即采用；
 * 均未命中时回退到 {@link HttpServletRequest#getRemoteAddr()}。{@value #HEADER_X_FORWARDED_FOR}
 * 可能是逗号分隔的多级代理地址链（{@code client, proxy1, proxy2}），按英文逗号切分后取第一个满足条件的片段。
 * 解析结果忽略大小写匹配 {@value #IPV4_MAPPED_IPV6_PREFIX} 前缀时，仅返回该前缀之后的 IPv4 部分。
 * <p>
 * 以上请求头均可被客户端任意伪造，本工具类不做可信代理校验（例如校验 {@code getRemoteAddr()} 是否属于可信网段
 * 才采信转发头）；调用方如需防伪造，应在业务层自行增加可信代理白名单校验。
 * <p>
 * {@link #getHeader(HttpServletRequest, String)}/{@link #getCookieValue(HttpServletRequest, String)}
 * 未命中时返回 {@code null}，不使用 {@code Optional} 作为返回类型。
 * <p>
 * 本工具类无状态，所有方法均可被多线程并发调用。
 */
public final class HttpRequestUtils {

    /**
     * 多级代理场景下透传客户端 IP 的标准请求头
     */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /**
     * Nginx 等反向代理常用的真实 IP 请求头
     */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /**
     * Apache 等代理场景使用的客户端 IP 请求头
     */
    private static final String HEADER_PROXY_CLIENT_IP = "Proxy-Client-IP";

    /**
     * WebLogic 代理场景使用的客户端 IP 请求头
     */
    private static final String HEADER_WL_PROXY_CLIENT_IP = "WL-Proxy-Client-IP";

    /**
     * 部分网关使用的客户端 IP 请求头
     */
    private static final String HEADER_HTTP_CLIENT_IP = "HTTP_CLIENT_IP";

    /**
     * 部分网关使用的多级代理客户端 IP 请求头
     */
    private static final String HEADER_HTTP_X_FORWARDED_FOR = "HTTP_X_FORWARDED_FOR";

    /**
     * 按优先级排列的代理 IP 请求头名称
     */
    private static final String[] PROXY_IP_HEADERS = {
        HEADER_X_FORWARDED_FOR,
        HEADER_X_REAL_IP,
        HEADER_PROXY_CLIENT_IP,
        HEADER_WL_PROXY_CLIENT_IP,
        HEADER_HTTP_CLIENT_IP,
        HEADER_HTTP_X_FORWARDED_FOR
    };

    /**
     * 未知代理追加的占位值，解析时需忽略
     */
    private static final String UNKNOWN = "unknown";

    /**
     * IPv4-映射 IPv6 地址的前缀，命中时仅保留前缀之后的 IPv4 部分
     */
    private static final String IPV4_MAPPED_IPV6_PREFIX = "::ffff:";

    private HttpRequestUtils() {
    }

    /**
     * 解析访问者真实 IP。
     * <p>
     * 按 {@link #PROXY_IP_HEADERS} 的固定优先级依次检查代理请求头，取到有效值即返回；均未命中时
     * 回退到 {@link HttpServletRequest#getRemoteAddr()}。{@value #HEADER_X_FORWARDED_FOR} 为
     * 逗号分隔的多级地址时取第一个非空且不等于（忽略大小写）{@value #UNKNOWN} 的地址。结果忽略大小写
     * 匹配 {@value #IPV4_MAPPED_IPV6_PREFIX} 前缀时，仅返回该前缀之后的 IPv4 部分。
     *
     * @param request 入站 HTTP 请求，不能为 {@code null}
     * @return 解析得到的访问者真实 IP
     * @throws IllegalArgumentException 当 {@code request} 为 {@code null} 时抛出
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }

        for (String headerName : PROXY_IP_HEADERS) {
            String ip = resolveFromHeader(request.getHeader(headerName));
            if (ip != null) {
                return normalizeIpv4MappedIpv6(ip);
            }
        }

        return normalizeIpv4MappedIpv6(request.getRemoteAddr());
    }

    /**
     * 读取指定请求头的值。
     *
     * @param request 入站 HTTP 请求，不能为 {@code null}
     * @param name    请求头名称，不能为 {@code null} 或空白字符串
     * @return 该请求头的值；请求不包含该请求头时返回 {@code null}
     * @throws IllegalArgumentException 当 {@code request} 为 {@code null}，或 {@code name} 为
     *                                  {@code null}/空白字符串时抛出
     */
    public static String getHeader(HttpServletRequest request, String name) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白字符串");
        }

        return request.getHeader(name);
    }

    /**
     * 读取指定名称 Cookie 的值。
     *
     * @param request 入站 HTTP 请求，不能为 {@code null}
     * @param name    Cookie 名称，不能为 {@code null} 或空白字符串
     * @return 该 Cookie 的值；不存在或请求未携带任何 Cookie 时返回 {@code null}
     * @throws IllegalArgumentException 当 {@code request} 为 {@code null}，或 {@code name} 为
     *                                  {@code null}/空白字符串时抛出
     */
    public static String getCookieValue(HttpServletRequest request, String name) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白字符串");
        }

        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }

        return null;
    }

    /**
     * 从单个代理请求头的原始值中解析出首个有效 IP。
     * <p>
     * {@value #HEADER_X_FORWARDED_FOR} 的值可能是逗号分隔的多级地址链，其余请求头通常只有单个地址，
     * 但统一按逗号切分处理不影响单值场景。
     *
     * @param headerValue 请求头原始值，可能为 {@code null}
     * @return 首个非空且不等于（忽略大小写）{@value #UNKNOWN} 的地址；未取到时返回 {@code null}
     */
    private static String resolveFromHeader(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }

        for (String candidate : headerValue.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty() && !UNKNOWN.equalsIgnoreCase(trimmed)) {
                return trimmed;
            }
        }

        return null;
    }

    /**
     * 规范化 IPv4-映射 IPv6 地址：命中 {@value #IPV4_MAPPED_IPV6_PREFIX} 前缀（忽略大小写）时，
     * 仅返回该前缀之后的 IPv4 部分。
     *
     * @param ip 待规范化的地址
     * @return 规范化后的地址
     */
    private static String normalizeIpv4MappedIpv6(String ip) {
        if (ip != null && ip.length() > IPV4_MAPPED_IPV6_PREFIX.length()
            && ip.substring(0, IPV4_MAPPED_IPV6_PREFIX.length()).equalsIgnoreCase(IPV4_MAPPED_IPV6_PREFIX)) {
            return ip.substring(IPV4_MAPPED_IPV6_PREFIX.length());
        }
        return ip;
    }
}
