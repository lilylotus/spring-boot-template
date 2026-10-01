## Why

项目目前只有面向出站调用的 HTTP 客户端工具（`org.example.simple.http` 包下的 `HttpClients` 等），没有面向入站请求的通用工具：获取访问者真实 IP、读取指定请求头、读取指定 Cookie 值这类高频需求，各处只能临时手写且容易遗漏代理场景（`X-Forwarded-For` 多级代理、未知代理追加的 `unknown`）和 IPv4-映射 IPv6 地址规范化（已在 `ip-echo-page` 的 Node.js 实现中约定、但 Java 侧没有对应能力）。需要一个无状态的静态工具类统一处理这三类读取逻辑。

## What Changes

- 新增 `org.example.simple.util.HttpRequestUtils` 静态工具类（`final` + 私有构造器），基于 `jakarta.servlet.http.HttpServletRequest`（项目已依赖的 `tomcat-embed-core:10.1.60` 自带该 API，不新增第三方依赖）。
- 提供 `String getClientIp(HttpServletRequest request)`：按常见代理请求头优先级（`X-Forwarded-For` → `X-Real-IP` → `Proxy-Client-IP` → `WL-Proxy-Client-IP` → `HTTP_CLIENT_IP` → `HTTP_X_FORWARDED_FOR` → `request.getRemoteAddr()`）解析访问者真实 IP；`X-Forwarded-For` 存在多个以逗号分隔的地址时取第一个非空且不等于 `unknown`（大小写不敏感）的地址；结果为 IPv4-映射 IPv6（`::ffff:` 前缀）时仅返回前缀之后的 IPv4 部分，与 `ip-echo-page` 规范的规则保持一致。
- 提供 `String getHeader(HttpServletRequest request, String name)`：返回指定请求头的值，不存在时返回 `null`。
- 提供 `String getCookieValue(HttpServletRequest request, String name)`：返回指定名称 Cookie 的值，不存在或请求未携带任何 Cookie 时返回 `null`。
- `request` 为 `null` 时三个方法均抛出 `IllegalArgumentException`；`getHeader`/`getCookieValue` 的 `name` 为 `null` 或空白字符串时同样抛出 `IllegalArgumentException`。

## Capabilities

### New Capabilities

- `http-request-utilities`: 定义从入站 HTTP 请求中解析真实客户端 IP、读取指定请求头、读取指定 Cookie 值的行为。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/util/HttpRequestUtils.java`。
- 新增 `src/test/java/org/example/simple/util/HttpRequestUtilsTest.java`。
- 不新增 Gradle 依赖（`jakarta.servlet.http.HttpServletRequest` 由已有的 `tomcat-embed-core` 提供）；不修改现有 HTTP 客户端工具、RPC 模块或 `ip-echo-page` 的 Node.js 实现。
