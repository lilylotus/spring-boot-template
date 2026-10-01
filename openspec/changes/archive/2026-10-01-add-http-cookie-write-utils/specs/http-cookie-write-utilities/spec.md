# Spec Delta

## Purpose
为 Java 代码提供统一构造并写入响应 Cookie 的静态工具，覆盖过期时间、路径、域、Secure、HttpOnly、SameSite 等常用属性。

## ADDED Requirements

### Requirement: 构造 Cookie 属性参数对象
系统 SHALL 提供 `HttpResponseUtils.CookieOptions`，通过 `CookieOptions.builder()`（不接收 `name`/`value`）开始构造，支持链式设置 `maxAge`、`path`、`domain`、`secure`、`httpOnly`、`sameSite`，未显式设置的属性使用默认值：`maxAge=-1`（会话 Cookie）、`path="/"`、`domain` 不设置、`secure=false`、`httpOnly=true`、`sameSite` 不设置。

#### Scenario: 使用全部默认值构造
- **WHEN** 调用方不设置任何属性，直接 `build()`
- **THEN** 构造出的 `CookieOptions` 的 `maxAge` 为 `-1`、`path` 为 `/`、`httpOnly` 为 `true`，`domain`/`sameSite` 均未设置

#### Scenario: SameSite=None 必须同时为 Secure
- **WHEN** 调用方设置 `sameSite` 为 `NONE` 但未设置 `secure` 为 `true`
- **THEN** 构造方法（`build()`）抛出 `IllegalStateException`

### Requirement: 写入响应 Cookie
系统 SHALL 提供 `HttpResponseUtils.setCookie(HttpServletResponse response, String name, String value, CookieOptions options)`，用 `name`/`value` 构造 `jakarta.servlet.http.Cookie`，再按 `options` 中的属性设置其余字段，并通过 `response.addCookie(cookie)` 写入响应；`sameSite` 已设置时，系统 SHALL 以 `SameSite` 为属性名、`Strict`/`Lax`/`None` 为属性值设置到该 Cookie 上。

#### Scenario: 写入包含全部属性的 Cookie
- **WHEN** 调用方提供 `name`、`value`，以及设置了 `maxAge`、`path`、`domain`、`secure`、`httpOnly`、`sameSite` 的 `CookieOptions`
- **THEN** 响应被写入对应属性齐全的 `Set-Cookie`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `response` 或 `options` 为 `null`，或 `name` 为 `null`/空白字符串
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 使用默认属性快速写入 Cookie
系统 SHALL 提供便捷重载 `HttpResponseUtils.setCookie(HttpServletResponse response, String name, String value)`，等价于 `options` 使用 `CookieOptions` 全部默认值写入 Cookie。

#### Scenario: 快速写入会话 Cookie
- **WHEN** 调用方只提供 `response`、`name`、`value`
- **THEN** 方法写入一个会话 Cookie（不设置 `maxAge`），`path` 为 `/`，`httpOnly` 为 `true`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `response` 为 `null`，或 `name` 为 `null`/空白字符串
- **THEN** 方法抛出 `IllegalArgumentException`
