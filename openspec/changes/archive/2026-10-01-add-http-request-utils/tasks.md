# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/http-request-utilities/spec.md` 的明确确认。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.util.HttpRequestUtils`（`final` + 私有构造器），声明按优先级排列的代理 IP 请求头名称常量数组（`X-Forwarded-For`、`X-Real-IP`、`Proxy-Client-IP`、`WL-Proxy-Client-IP`、`HTTP_CLIENT_IP`、`HTTP_X_FORWARDED_FOR`）和 IPv4-映射 IPv6 前缀常量（`::ffff:`）。
- [x] 2.2 实现 `getClientIp(HttpServletRequest request)`：`request` 为 `null` 时抛出带中文描述的 `IllegalArgumentException`；依次调用 `request.getHeader(headerName)` 检查 2.1 中的请求头，取到非空且不等于 `unknown`（忽略大小写，`trim()` 后比较）的值即采用；`X-Forwarded-For` 按英文逗号切分取第一个满足条件的片段（`trim()` 后使用）；全部未命中时使用 `request.getRemoteAddr()`；最终结果忽略大小写匹配 `::ffff:` 前缀时，仅返回前缀之后的部分。
- [x] 2.3 实现 `getHeader(HttpServletRequest request, String name)`：`request` 为 `null` 或 `name` 为 `null`/空白（`isBlank()`）时抛出 `IllegalArgumentException`；否则返回 `request.getHeader(name)`。
- [x] 2.4 实现 `getCookieValue(HttpServletRequest request, String name)`：`request` 为 `null` 或 `name` 为 `null`/空白时抛出 `IllegalArgumentException`；`request.getCookies()` 为 `null` 时返回 `null`；否则遍历 `Cookie[]` 查找名称匹配（区分大小写，与 Servlet 规范一致）的第一个 Cookie 并返回其 `getValue()`，未找到返回 `null`。
- [x] 2.5 为 `HttpRequestUtils` 及三个公开方法补充中文 Javadoc：说明真实 IP 解析的请求头优先级和 `X-Forwarded-For` 多值/`unknown` 处理规则、IPv4-映射 IPv6 规范化规则、各方法的参数校验与 `IllegalArgumentException` 触发条件、未命中时返回 `null` 的语义、请求头可被客户端伪造故不做可信代理校验的风险提示、线程安全性（无状态，可并发调用）。

## 3. 测试

- [x] 3.1 新增 `HttpRequestUtilsTest`，使用 Mockito（或等价的手写 Stub/Fake，视仓库现有测试依赖而定，先确认 `build.gradle` 是否已引入 Mockito，未引入则用实现 `HttpServletRequest` 最小必要方法的测试替身）构造 `HttpServletRequest` 测试替身。
- [x] 3.2 测试 `getClientIp`：无任何代理请求头时返回 `getRemoteAddr()`；`X-Forwarded-For` 单值、逗号分隔多值（含首段为 `unknown`、含各段前后空格）时返回正确的首个有效地址；`X-Real-IP` 等其余 5 个请求头按优先级逐一命中的场景；全部代理头为空字符串或 `unknown` 时回退到 `getRemoteAddr()`；结果为 `::ffff:` 前缀（含大小写混合）时仅返回 IPv4 部分；`request` 为 `null` 时抛出 `IllegalArgumentException`。
- [x] 3.3 测试 `getHeader`：请求头存在时返回对应值；不存在时返回 `null`；`request` 为 `null`、`name` 为 `null`、`name` 为空白字符串时均抛出 `IllegalArgumentException`。
- [x] 3.4 测试 `getCookieValue`：Cookie 存在时返回其值；`getCookies()` 返回 `null`（未携带任何 Cookie）时返回 `null`；Cookie 数组非空但无匹配名称时返回 `null`；多个同名 Cookie 时返回第一个；`request` 为 `null`、`name` 为 `null`、`name` 为空白字符串时均抛出 `IllegalArgumentException`。

## 4. 文档与验证

- [x] 4.1 新增 `docs/HttpRequestUtils使用说明.md`：覆盖三个方法的示例、真实 IP 解析的请求头优先级与可被伪造的风险提示、参数校验规则；在 `README.md` 增加一行入口链接。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-http-request-utils --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.HttpRequestUtilsTest"`：21 个测试全部通过。
- `gradlew.bat build`（全量）：`HttpRequestUtilsTest` 全部通过；`RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall()` 在全量并发执行下偶发失败，单独重跑（`--tests "org.example.simple.rpc.RpcLoopbackIntegrationTest" --rerun`）通过，确认是与本变更无关的既有偶发性（flaky）测试，未作改动。
- `openspec validate add-http-request-utils --strict`：`Change 'add-http-request-utils' is valid`。
