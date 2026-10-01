# HttpRequestUtils 使用说明

`HttpRequestUtils` 位于 `org.example.simple.util`，是面向入站 HTTP 请求的无状态工具类：解析访问者真实 IP、读取指定请求头、读取指定 Cookie 值。基于 `jakarta.servlet.http.HttpServletRequest`（由已依赖的 `tomcat-embed-core` 提供），不新增第三方依赖。

- 不提供 Spring MVC 集成（`HandlerInterceptor`、参数解析器、`ThreadLocal` 当前请求上下文等），只提供以 `HttpServletRequest` 为显式入参的纯函数式方法。
- 不做 IP 地址合法性校验、地理位置解析、黑白名单等增值能力。
- 不处理 Cookie 的写入/删除，只读取请求中已有的 Cookie。

## API 速查

| 场景 | 方法 | 说明 |
| --- | --- | --- |
| 解析真实 IP | `getClientIp(HttpServletRequest request)` | 按固定优先级依次检查代理请求头，均未命中时回退到 `request.getRemoteAddr()` |
| 读取请求头 | `getHeader(HttpServletRequest request, String name)` | 返回指定请求头的值，不存在时返回 `null` |
| 读取 Cookie | `getCookieValue(HttpServletRequest request, String name)` | 返回指定名称 Cookie 的值，不存在或未携带任何 Cookie 时返回 `null` |

## 真实 IP 解析规则

按以下固定优先级依次检查请求头，取到非空且不等于（忽略大小写）`unknown` 的值即采用，顺序不可配置：

1. `X-Forwarded-For`
2. `X-Real-IP`
3. `Proxy-Client-IP`
4. `WL-Proxy-Client-IP`
5. `HTTP_CLIENT_IP`
6. `HTTP_X_FORWARDED_FOR`
7. 以上均未命中：回退到 `request.getRemoteAddr()`

`X-Forwarded-For` 可能是逗号分隔的多级代理地址链（如 `client, proxy1, proxy2`），按英文逗号切分、`trim()` 后取第一个非空且不等于 `unknown` 的片段，即访问者真实地址。

解析结果忽略大小写匹配 `::ffff:` 前缀（IPv4-映射 IPv6 地址）时，仅返回该前缀之后的 IPv4 部分，与 `ip-echo-page` 的 Node.js 实现约定的规范化规则保持一致。

```java
String clientIp = HttpRequestUtils.getClientIp(request);
```

> **风险提示**：以上请求头均可被客户端任意伪造，本方法不做可信代理校验（例如校验 `getRemoteAddr()` 是否属于可信网段才采信转发头）。调用方如需防伪造，应在业务层自行增加可信代理白名单校验后再调用本方法，或自行实现校验逻辑。

## 读取请求头与 Cookie

```java
String token = HttpRequestUtils.getHeader(request, "Authorization");  // 不存在时返回 null

String sessionId = HttpRequestUtils.getCookieValue(request, "SESSION_ID");  // 不存在或未携带 Cookie 时返回 null
```

## 参数校验

| 方法 | 触发 `IllegalArgumentException` 的条件 |
| --- | --- |
| `getClientIp(request)` | `request` 为 `null` |
| `getHeader(request, name)` | `request` 为 `null`，或 `name` 为 `null`/空白字符串 |
| `getCookieValue(request, name)` | `request` 为 `null`，或 `name` 为 `null`/空白字符串 |

三个方法未命中有效值时均返回 `null`（不使用 `Optional`），与仓库现有工具类（如 `JacksonUtils`）的返回值风格一致。

## 线程安全

本工具类无状态，所有方法均可被多线程并发调用。
