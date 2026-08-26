## 1. 异常处理实现

- [x] 1.1 在 `GlobalExceptionHandler` 中新增 `@ExceptionHandler(NoResourceFoundException.class)` 方法，返回 HTTP 404 统一错误响应（`code=404`），响应携带当前请求的 `traceId`
- [x] 1.2 确认 `NoResourceFoundException` 不再落入 `handleUnexpectedException` 兜底分支（Spring 按异常类型匹配最具体的 `@ExceptionHandler`，新增处理器天然优先于 `Exception.class`，无需额外改动即可验证）

## 2. 验证

- [x] 2.1 编写/运行测试，请求一个不存在的静态资源或未匹配路径，验证响应状态码为 404、`code` 为 404、`data` 为 `null`、响应体 `traceId` 与 `X-Trace-Id` 响应头一致，且未记录 ERROR 级别日志
- [x] 2.2 运行 `./gradlew test` 确认现有异常处理相关测试全部通过
