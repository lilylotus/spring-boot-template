package org.example.simple.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 出站 HTTP 响应写入工具类：统一构造并写入响应 Cookie，覆盖过期时间、路径、域、Secure、HttpOnly、SameSite 等常用属性。
 * <p>
 * 与只读的 {@link HttpRequestUtils} 职责分开：本类只负责"写"响应 Cookie，不提供读取入站请求的能力。
 * <p>
 * Cookie 的 {@code name}/{@code value} 作为方法的显式参数传入；其余属性通过 {@link CookieOptions} 统一封装，
 * 未显式设置的属性使用偏安全的默认值（详见 {@link CookieOptions.Builder} 各方法说明），同一份 {@link CookieOptions}
 * 可以复用给多个不同 {@code name}/{@code value} 的 Cookie。写入由
 * {@link #setCookie(HttpServletResponse, String, String, CookieOptions)} 完成，内部调用
 * {@code response.addCookie(cookie)} 交由 Servlet 容器序列化为 {@code Set-Cookie} 响应头。
 * <p>
 * 本工具类无状态，所有方法均可被多线程并发调用。
 */
public final class HttpResponseUtils {

    private HttpResponseUtils() {
    }

    /**
     * 用 {@code name}/{@code value} 构造 Cookie，按 {@code options} 中的属性设置其余字段并写入响应。
     * <p>
     * {@code options.sameSite()} 已设置时，通过 Jakarta Servlet 6.0 的 {@code Cookie#setAttribute("SameSite", ...)}
     * 机制设置该属性（{@code tomcat-embed-core} 对应的 Servlet 容器识别该属性名）。
     *
     * @param response 待写入的 HTTP 响应，不能为 {@code null}
     * @param name Cookie 名称，不能为 {@code null} 或空白字符串
     * @param value Cookie 值
     * @param options 除 {@code name}/{@code value} 外的其余 Cookie 属性，不能为 {@code null}
     * @throws IllegalArgumentException 当 {@code response}/{@code options} 为 {@code null}，或 {@code name}
     *     为 {@code null}/空白字符串时抛出
     */
    public static void setCookie(HttpServletResponse response, String name, String value, CookieOptions options) {
        if (response == null) {
            throw new IllegalArgumentException("response 不能为 null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为 null 或空白字符串");
        }
        if (options == null) {
            throw new IllegalArgumentException("options 不能为 null");
        }

        Cookie cookie = new Cookie(name, value);
        cookie.setMaxAge(options.maxAge());
        cookie.setPath(options.path());
        cookie.setSecure(options.secure());
        cookie.setHttpOnly(options.httpOnly());
        if (options.domain() != null) {
            cookie.setDomain(options.domain());
        }
        if (options.sameSite() != null) {
            cookie.setAttribute("SameSite", options.sameSite().attributeValue());
        }

        response.addCookie(cookie);
    }

    /**
     * 使用 {@link CookieOptions} 全部默认值快速写入 Cookie：会话 Cookie（不设置 {@code maxAge}），
     * {@code path} 为 {@code /}，{@code httpOnly} 为 {@code true}，不设置 {@code domain}/{@code secure}/{@code sameSite}。
     *
     * @param response 待写入的 HTTP 响应，不能为 {@code null}
     * @param name Cookie 名称，不能为 {@code null} 或空白字符串
     * @param value Cookie 值
     * @throws IllegalArgumentException 当 {@code response} 为 {@code null}，或 {@code name} 为
     *     {@code null}/空白字符串时抛出
     */
    public static void setCookie(HttpServletResponse response, String name, String value) {
        setCookie(response, name, value, CookieOptions.builder().build());
    }

    /**
     * Cookie 的 {@code SameSite} 属性取值。
     */
    public enum SameSite {

        /** 仅同站请求携带该 Cookie */
        STRICT("Strict"),

        /** 同站请求携带该 Cookie；部分跨站的顶级导航（如链接跳转）也携带 */
        LAX("Lax"),

        /** 跨站请求也携带该 Cookie；浏览器要求必须同时设置 {@code Secure}，否则该 Cookie 会被丢弃 */
        NONE("None");

        private final String attributeValue;

        SameSite(String attributeValue) {
            this.attributeValue = attributeValue;
        }

        /**
         * @return 写入 Cookie 的 {@code SameSite} 属性值（{@code Strict}/{@code Lax}/{@code None}）
         */
        String attributeValue() {
            return attributeValue;
        }
    }

    /**
     * 不可变的 Cookie 属性参数对象（不含 {@code name}/{@code value}，两者作为 {@code setCookie} 的独立方法参数传入），
     * 通过 {@link #builder()} 构造。
     * <p>
     * 未显式设置的属性使用默认值：{@code maxAge=-1}（会话 Cookie）、{@code path="/"}、{@code domain} 不设置、
     * {@code secure=false}、{@code httpOnly=true}、{@code sameSite} 不设置。同一份 {@link CookieOptions} 可以
     * 复用给多个不同 {@code name}/{@code value} 的 Cookie。
     */
    public static final class CookieOptions {

        /** 过期时间（秒）；{@code -1} 表示会话 Cookie（浏览器关闭后失效），{@code 0} 表示立即删除 */
        private final int maxAge;

        /** Cookie 生效路径，只有访问该路径及其子路径时浏览器才会携带该 Cookie */
        private final String path;

        /** Cookie 生效域；{@code null} 表示不设置，由浏览器按当前域处理，不支持跨子域 */
        private final String domain;

        /** 是否仅通过 HTTPS 连接发送该 Cookie，避免明文传输被窃取 */
        private final boolean secure;

        /** 是否禁止 JavaScript 读取该 Cookie（{@code document.cookie}），降低 XSS 窃取 Cookie 的风险 */
        private final boolean httpOnly;

        /** 跨站请求是否携带该 Cookie 的策略；{@code null} 表示不设置该属性，由浏览器按默认策略处理 */
        private final SameSite sameSite;

        private CookieOptions(Builder builder) {
            this.maxAge = builder.maxAge;
            this.path = builder.path;
            this.domain = builder.domain;
            this.secure = builder.secure;
            this.httpOnly = builder.httpOnly;
            this.sameSite = builder.sameSite;
        }

        /**
         * 开始构造 {@link CookieOptions}，初始即带有全部默认值。
         *
         * @return 可继续链式设置各属性的 {@link Builder}
         */
        public static Builder builder() {
            return new Builder();
        }

        /** @return 过期时间（秒）；{@code -1} 表示会话 Cookie，{@code 0} 表示立即删除 */
        public int maxAge() {
            return maxAge;
        }

        /** @return Cookie 生效路径 */
        public String path() {
            return path;
        }

        /** @return Cookie 生效域；未设置时为 {@code null} */
        public String domain() {
            return domain;
        }

        /** @return 是否仅通过 HTTPS 发送 */
        public boolean secure() {
            return secure;
        }

        /** @return 是否禁止脚本读取该 Cookie */
        public boolean httpOnly() {
            return httpOnly;
        }

        /** @return {@code SameSite} 属性；未设置时为 {@code null} */
        public SameSite sameSite() {
            return sameSite;
        }

        /**
         * {@link CookieOptions} 的构造器。
         */
        public static final class Builder {

            /** 默认会话 Cookie，即不设置过期时间，浏览器关闭后失效 */
            private int maxAge = -1;

            /** 默认对整个站点生效 */
            private String path = "/";

            /** 默认不设置，由浏览器按当前域处理 */
            private String domain;

            /** 默认允许通过 HTTP 明文发送，调用方需按部署环境（是否全站 HTTPS）显式开启 */
            private boolean secure = false;

            /** 默认禁止脚本读取，偏向更安全的默认行为 */
            private boolean httpOnly = true;

            /** 默认不设置该属性，由浏览器按默认策略处理跨站请求是否携带 */
            private SameSite sameSite;

            private Builder() {
            }

            /**
             * 设置过期时间。
             *
             * @param maxAge 过期时间（秒）；{@code -1} 表示会话 Cookie，{@code 0} 表示立即删除
             * @return 当前构造器
             */
            public Builder maxAge(int maxAge) {
                this.maxAge = maxAge;
                return this;
            }

            /**
             * 设置生效路径。
             *
             * @param path Cookie 生效路径
             * @return 当前构造器
             */
            public Builder path(String path) {
                this.path = path;
                return this;
            }

            /**
             * 设置生效域。
             *
             * @param domain Cookie 生效域
             * @return 当前构造器
             */
            public Builder domain(String domain) {
                this.domain = domain;
                return this;
            }

            /**
             * 设置是否仅通过 HTTPS 发送。
             *
             * @param secure 是否仅通过 HTTPS 发送
             * @return 当前构造器
             */
            public Builder secure(boolean secure) {
                this.secure = secure;
                return this;
            }

            /**
             * 设置是否禁止脚本读取该 Cookie。
             *
             * @param httpOnly 是否禁止脚本读取该 Cookie
             * @return 当前构造器
             */
            public Builder httpOnly(boolean httpOnly) {
                this.httpOnly = httpOnly;
                return this;
            }

            /**
             * 设置 {@code SameSite} 属性。
             *
             * @param sameSite {@code SameSite} 属性
             * @return 当前构造器
             */
            public Builder sameSite(SameSite sameSite) {
                this.sameSite = sameSite;
                return this;
            }

            /**
             * 完成构造。
             *
             * @return 构造完成的 {@link CookieOptions}
             * @throws IllegalStateException 当 {@code sameSite} 为 {@link SameSite#NONE} 且 {@code secure}
             *     不为 {@code true} 时抛出：此类 Cookie 会被浏览器直接丢弃
             */
            public CookieOptions build() {
                if (sameSite == SameSite.NONE && !secure) {
                    throw new IllegalStateException("sameSite 为 NONE 时必须同时将 secure 设置为 true，否则该 Cookie 会被浏览器丢弃");
                }
                return new CookieOptions(this);
            }
        }
    }
}
