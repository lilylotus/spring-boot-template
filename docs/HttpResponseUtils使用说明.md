# HttpResponseUtils 使用说明

`HttpResponseUtils` 位于 `org.example.simple.util`，是面向出站 HTTP 响应的无状态工具类：统一构造并写入响应 Cookie，覆盖过期时间、路径、域、Secure、HttpOnly、SameSite 等常用属性。基于 `jakarta.servlet.http.HttpServletResponse`/`jakarta.servlet.http.Cookie`（由已依赖的 `tomcat-embed-core` 提供），不新增第三方依赖。

与只读的 [HttpRequestUtils](HttpRequestUtils使用说明.md) 职责分开：`HttpRequestUtils` 只负责读取入站请求，`HttpResponseUtils` 只负责写出响应 Cookie，两者不混在同一个类里。

`name`/`value` 作为方法的显式参数传入；`CookieOptions` 只封装除 `name`/`value` 外的其余属性，同一份 `CookieOptions` 可以复用给多个不同 `name`/`value` 的 Cookie。

## API 速查

| 场景 | 方法 | 说明 |
| --- | --- | --- |
| 构造 Cookie 属性 | `CookieOptions.builder()` | 开始链式构造 Cookie 属性（不含 `name`/`value`），无参即带全部默认值 |
| 写入完整属性 Cookie | `setCookie(HttpServletResponse response, String name, String value, CookieOptions options)` | 用 `name`/`value` 构造 Cookie，按 `options` 写入其余属性 |
| 快速写入默认 Cookie | `setCookie(HttpServletResponse response, String name, String value)` | 等价于 `options` 使用 `CookieOptions` 全部默认值 |

## CookieOptions 属性与默认值

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `maxAge` | `-1` | 过期时间（秒）；`-1` 表示会话 Cookie（浏览器关闭后失效），`0` 表示立即删除 |
| `path` | `/` | Cookie 生效路径 |
| `domain` | 不设置 | Cookie 生效域；未设置时使用浏览器默认（当前域） |
| `secure` | `false` | 是否仅通过 HTTPS 发送 |
| `httpOnly` | `true` | 是否禁止脚本读取该 Cookie（默认开启以降低 XSS 风险） |
| `sameSite` | 不设置 | `HttpResponseUtils.SameSite.STRICT`/`LAX`/`NONE`；设置为 `NONE` 时必须同时将 `secure` 设为 `true` |

`domain`/`secure`/`sameSite` 的正确取值依赖具体部署环境（是否全站 HTTPS、是否需要跨子域/跨站），本工具类不假设统一默认值，需调用方按场景显式设置；`httpOnly` 默认开启，确有需要前端脚本读取该 Cookie 时再显式设为 `false`。

## 基本用法

```java
import org.example.simple.util.HttpResponseUtils;
import org.example.simple.util.HttpResponseUtils.CookieOptions;
import org.example.simple.util.HttpResponseUtils.SameSite;

// 完整属性
CookieOptions options = CookieOptions.builder()
        .maxAge(3600)
        .path("/app")
        .domain("example.com")
        .secure(true)
        .httpOnly(true)
        .sameSite(SameSite.LAX)
        .build();
HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123", options);

// 快速写入（全部使用默认值：会话 Cookie、path=/、httpOnly=true）
HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123");

// 同一份 CookieOptions 复用给多个不同的 Cookie
CookieOptions shared = CookieOptions.builder().maxAge(3600).secure(true).build();
HttpResponseUtils.setCookie(response, "SESSION_ID", "abc123", shared);
HttpResponseUtils.setCookie(response, "CSRF_TOKEN", "xyz789", shared);
```

### 删除 Cookie

本工具类不提供专用的删除方法，删除即写入一个同名、`maxAge=0` 的 Cookie：

```java
HttpResponseUtils.setCookie(response, "SESSION_ID", "", CookieOptions.builder().maxAge(0).build());
```

## SameSite=None 的强制约束

主流浏览器（Chrome 等）自 2020 年起要求：`SameSite=None` 的 Cookie 必须同时携带 `Secure` 属性，否则浏览器会直接丢弃该 Cookie（不报错，调用方很难察觉）。因此 `CookieOptions.Builder#build()` 在 `sameSite` 为 `NONE` 且 `secure` 不为 `true` 时抛出 `IllegalStateException`，在构造阶段提前拦截，而不是产出一个"构造成功但浏览器不认可"的 Cookie：

```java
CookieOptions.builder().sameSite(SameSite.NONE).build();                 // 抛出 IllegalStateException
CookieOptions.builder().secure(true).sameSite(SameSite.NONE).build();    // 正常构造
```

## 参数校验

| 方法 | 触发异常的条件 |
| --- | --- |
| `CookieOptions.Builder#build()` | `sameSite == NONE` 且 `secure != true` 时抛 `IllegalStateException` |
| `setCookie(response, name, value, options)` | `response`/`options` 为 `null`，或 `name` 为 `null`/空白字符串时抛 `IllegalArgumentException` |
| `setCookie(response, name, value)` | `response` 为 `null`，或 `name` 为 `null`/空白字符串时抛 `IllegalArgumentException` |

## 非目标

- 不提供删除 Cookie 的专用方法；用 `maxAge=0` 实现，见上文示例。
- 不做 Cookie 值的编码/转义；调用方需自行保证 `value` 符合 Cookie 值的合法字符集，或自行编码后再传入。
- 不提供批量写入多个 Cookie 的入口；多个 Cookie 由调用方多次调用 `setCookie`。

## 线程安全

本工具类及 `CookieOptions` 均无可变共享状态，所有方法均可被多线程并发调用。
