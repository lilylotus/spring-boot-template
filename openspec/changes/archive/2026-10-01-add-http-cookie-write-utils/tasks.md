# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对本次 API 形状调整的明确确认：`CookieOptions.builder()` 改为无参（不再接收 `name`/`value`），`name`/`value` 改为 `setCookie` 的独立方法参数；主方法签名变为 `setCookie(HttpServletResponse response, String name, String value, CookieOptions options)`。

## 2. 实现（重构）

- [x] 2.1 新增 `org.example.simple.util.HttpResponseUtils`（`final` + 私有构造器）。（已完成，无需改动）
- [x] 2.2 调整 `CookieOptions`：移除 `name`/`value` 字段与 `name()`/`value()` getter；`builder()` 改为无参静态方法，直接返回带默认值的 `Builder`（`maxAge=-1`、`path="/"`、`domain=null`、`secure=false`、`httpOnly=true`、`sameSite=null`），不再做 `name` 校验（校验移到 2.4/2.5）。
- [x] 2.3 `SameSite { STRICT, LAX, NONE }` 枚举保持不变。（已完成，无需改动）
- [x] 2.4 调整 `setCookie(HttpServletResponse response, CookieOptions options)` 为 `setCookie(HttpServletResponse response, String name, String value, CookieOptions options)`：`response`/`options` 为 `null`，或 `name` 为 `null`/空白（`isBlank()`）时抛出 `IllegalArgumentException`；用 `new Cookie(name, value)` 构造后按 `options` 设置 `maxAge`/`path`/`secure`/`httpOnly`，`domain` 非 `null` 时 `setDomain`，`sameSite` 非 `null` 时 `setAttribute("SameSite", ...)`，调用 `response.addCookie(cookie)`。
- [x] 2.5 调整便捷重载 `setCookie(HttpServletResponse response, String name, String value)`：内部改为调用 `setCookie(response, name, value, CookieOptions.builder().build())`。
- [x] 2.6 同步更新 `HttpResponseUtils`、`CookieOptions` 及方法的中文 Javadoc，反映 `name`/`value` 拆出后的新参数校验位置与方法签名。

## 3. 测试（同步调整）

- [x] 3.1 调整 `HttpResponseUtilsTest` 中 `CookieOptions` 相关用例：改用 `CookieOptions.builder()`（无参）构造；移除对 `name`/`value` 的 `builder` 级校验断言。
- [x] 3.2 调整 `setCookie(response, name, value, options)` 相关用例：按新 4 参数签名调用；新增/保留 `name` 为 `null`/空白字符串时抛 `IllegalArgumentException` 的用例（校验位置从 `CookieOptions.builder` 移到本方法）。
- [x] 3.3 `setCookie(response, name, value)` 便捷重载用例按新实现确认仍通过（断言默认属性不变）。

## 4. 文档与验证

- [x] 4.1 更新 `docs/HttpResponseUtils使用说明.md`：示例改为 `CookieOptions.builder()`（无参）+ `setCookie(response, name, value, options)` 新签名；参数校验表格同步更新 `name` 校验的触发方法。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-http-cookie-write-utils --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.HttpResponseUtilsTest"`：17 个测试全部通过。
- `gradlew.bat build`（全量）：`HttpResponseUtilsTest` 全部通过；`RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall()` 在全量并发执行下偶发失败，单独重跑（`--tests "org.example.simple.rpc.RpcLoopbackIntegrationTest" --rerun`）通过，确认是与本变更无关的既有偶发性（flaky）测试，未作改动。
- `openspec validate add-http-cookie-write-utils --strict`：`Change 'add-http-cookie-write-utils' is valid`。
