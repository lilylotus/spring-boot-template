# http-request-utilities Specification

## Purpose
为 Java 代码提供从入站 `HttpServletRequest` 中解析访问者真实 IP、读取指定请求头、读取指定 Cookie 值的统一静态工具。

## Requirements

### Requirement: 解析访问者真实 IP
系统 SHALL 提供 `HttpRequestUtils.getClientIp(HttpServletRequest request)`，按固定优先级依次检查 `X-Forwarded-For`、`X-Real-IP`、`Proxy-Client-IP`、`WL-Proxy-Client-IP`、`HTTP_CLIENT_IP`、`HTTP_X_FORWARDED_FOR` 请求头，均未取到有效值时回退到 `request.getRemoteAddr()`。

#### Scenario: 无代理场景直接读取连接地址
- **WHEN** 请求不包含任何代理相关请求头
- **THEN** 方法返回 `request.getRemoteAddr()` 的值

#### Scenario: 单级代理透传真实 IP
- **WHEN** 请求的 `X-Forwarded-For` 请求头只包含一个有效 IP
- **THEN** 方法返回该 IP

#### Scenario: 多级代理取首个有效地址
- **WHEN** 请求的 `X-Forwarded-For` 请求头为逗号分隔的多个地址（如 `client, proxy1, proxy2`）
- **THEN** 方法返回其中第一个非空且不等于（忽略大小写）`unknown` 的地址

#### Scenario: 规范化 IPv4-映射 IPv6 地址
- **WHEN** 解析得到的地址以 `::ffff:` 为前缀（忽略大小写）
- **THEN** 方法仅返回该前缀之后的 IPv4 地址部分

#### Scenario: 请求对象为空
- **WHEN** 调用方传入的 `request` 为 `null`
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 读取指定请求头的值
系统 SHALL 提供 `HttpRequestUtils.getHeader(HttpServletRequest request, String name)`，返回指定名称请求头的值。

#### Scenario: 请求头存在
- **WHEN** 请求包含名为 `name` 的请求头
- **THEN** 方法返回该请求头的值

#### Scenario: 请求头不存在
- **WHEN** 请求不包含名为 `name` 的请求头
- **THEN** 方法返回 `null`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `request` 为 `null`，或 `name` 为 `null`/空白字符串
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 读取指定 Cookie 的值
系统 SHALL 提供 `HttpRequestUtils.getCookieValue(HttpServletRequest request, String name)`，返回指定名称 Cookie 的值。

#### Scenario: Cookie 存在
- **WHEN** 请求携带名为 `name` 的 Cookie
- **THEN** 方法返回该 Cookie 的值

#### Scenario: Cookie 不存在或请求未携带任何 Cookie
- **WHEN** 请求不包含名为 `name` 的 Cookie，或 `request.getCookies()` 为 `null`
- **THEN** 方法返回 `null`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `request` 为 `null`，或 `name` 为 `null`/空白字符串
- **THEN** 方法抛出 `IllegalArgumentException`
