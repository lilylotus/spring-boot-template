## Why

`org.example.simple.util.HttpRequestUtils`（`add-http-request-utils` 变更新增）只提供读取入站 Cookie 的能力，明确把"写入/删除 Cookie（`HttpServletResponse` 相关操作）"列为该变更的 Non-Goal。但业务侧（登录态、临时偏好设置等）仍需要统一写出响应 Cookie 的能力，各处手写 `new Cookie(...)` 再逐个设置 `maxAge`/`path`/`domain`/`secure`/`httpOnly`/`SameSite` 容易遗漏属性或写出不一致的默认值（例如忘记设置 `HttpOnly` 导致 XSS 风险，或设置 `SameSite=None` 却忘记同时要求 `Secure`，被现代浏览器直接拒绝该 Cookie）。需要一个独立的、职责单一的工具类统一处理响应 Cookie 的写入。

## What Changes

- 新增 `org.example.simple.util.HttpResponseUtils` 静态工具类（`final` + 私有构造器），基于 `jakarta.servlet.http.HttpServletResponse`/`jakarta.servlet.http.Cookie`（`tomcat-embed-core:10.1.60` 已提供，不新增第三方依赖），与只读的 `HttpRequestUtils` 职责对应但分开，不混入同一个类。
- 新增不可变的 Cookie 属性参数对象 `HttpResponseUtils.CookieOptions`（Builder 模式构造，`CookieOptions.builder()` **不接收** `name`/`value`，只封装除 `name`/`value` 外的其余属性），封装 `maxAge`（秒，默认 `-1` 即会话 Cookie）、`path`（默认 `/`）、`domain`（默认不设置）、`secure`（默认 `false`）、`httpOnly`（默认 `true`）、`sameSite`（枚举 `HttpResponseUtils.SameSite { STRICT, LAX, NONE }`，默认不设置该属性）。`name`/`value` 作为 `setCookie` 的显式方法参数单独传入，不放进 `CookieOptions`。
- 新增枚举 `HttpResponseUtils.SameSite`，`build()` 时如 `sameSite == NONE` 且 `secure != true` 抛出 `IllegalStateException`（现代浏览器拒绝不带 `Secure` 的 `SameSite=None` Cookie，提前在工具类内拦截而不是产出一个浏览器会丢弃的无效 Cookie）。
- 提供 `void setCookie(HttpServletResponse response, String name, String value, CookieOptions options)`：用 `name`/`value` 构造 `jakarta.servlet.http.Cookie`，再按 `options` 设置其余属性，调用 `response.addCookie(cookie)`；`SameSite` 通过 Jakarta Servlet 6.0 的 `Cookie#setAttribute("SameSite", ...)` 设置。
- 提供便捷重载 `void setCookie(HttpServletResponse response, String name, String value)`：等价于 `options` 使用 `CookieOptions` 全部默认值（会话 Cookie、`path=/`、`httpOnly=true`、不设置 `domain`/`SameSite`）。
- `response`/`options` 为 `null`，或 `name` 为 `null`/空白字符串时抛出 `IllegalArgumentException`。

## Capabilities

### New Capabilities

- `http-cookie-write-utilities`: 定义统一构造并写入响应 Cookie（含各项属性：过期时间、路径、域、Secure、HttpOnly、SameSite）的行为。

### Modified Capabilities

无。不修改 `http-request-utilities`（`HttpRequestUtils` 保持只读职责不变）。

## Impact

- 新增 `src/main/java/org/example/simple/util/HttpResponseUtils.java`。
- 新增 `src/test/java/org/example/simple/util/HttpResponseUtilsTest.java`。
- 不新增 Gradle 依赖；不修改 `HttpRequestUtils` 及其测试、文档。
