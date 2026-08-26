## Why

当前 `GlobalExceptionHandler` 没有专门处理 `NoResourceFoundException`（Spring MVC 在静态资源或未匹配路径找不到时抛出的异常）。这类异常会落入兜底的 `handleUnexpectedException`，被当作服务器内部错误记录 ERROR 级别日志并返回 HTTP 500，既污染错误日志、误导排查方向，也把本该属于客户端请求路径不存在的语义错误地表达为服务端故障。

## What Changes

- 在 `GlobalExceptionHandler` 中新增对 `org.springframework.web.servlet.resource.NoResourceFoundException` 的专门处理，返回 HTTP 404 和统一错误响应（`code=404`），响应携带当前请求的 `traceId`。
- 该异常不再落入 `Exception.class` 兜底分支，不再记录 ERROR 级别日志、不再返回 HTTP 500。
- 补充 `global-exception-handling` spec，新增“找不到资源的请求转换为统一错误响应”需求。

## Capabilities

### New Capabilities
（无）

### Modified Capabilities
- `global-exception-handling`: 新增对 `NoResourceFoundException` 的处理需求——系统必须捕获该异常并返回 HTTP 404 统一错误响应，而不是被兜底异常处理器当作 HTTP 500 处理。

## Impact

- 代码：`src/main/java/com/example/template/exception/GlobalExceptionHandler.java` 新增一个 `@ExceptionHandler` 方法。
- 无新增依赖，无破坏性变更（原本这类请求返回 500，现在返回更准确的 404，属于响应语义修正）。
