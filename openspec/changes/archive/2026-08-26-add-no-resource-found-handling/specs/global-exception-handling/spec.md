## MODIFIED Requirements

### Requirement: 未预期异常转换为安全的统一错误响应
系统 MUST（必须）兜底捕获未被更具体处理器匹配的异常，记录包含异常堆栈和 `traceId` 的错误日志，并返回 HTTP 500；响应 `code` 必须为 `500`、`message` 必须为不泄露内部实现细节的固定中文提示、`data` 必须为 `null`。该兜底处理器不得处理已由更具体处理器（包括找不到资源的 `NoResourceFoundException`）捕获的异常类型。

#### Scenario: 发生未预期异常
- **WHEN** 接口处理过程中抛出未被具体异常处理规则覆盖的异常
- **THEN** 系统记录完整错误日志并返回 HTTP 500 和安全的统一错误响应

## ADDED Requirements

### Requirement: 找不到资源的请求转换为统一错误响应
系统 MUST（必须）捕获请求路径未匹配任何静态资源或处理器产生的 `NoResourceFoundException`，返回 HTTP 404；响应 `code` 必须为 `404`，`message` 必须为稳定的中文提示，`data` 必须为 `null`，并携带当前请求的 `traceId`。该异常 MUST NOT（禁止）被兜底异常处理器当作服务器内部错误处理，不得记录 ERROR 级别日志、不得返回 HTTP 500。

#### Scenario: 请求路径未匹配任何资源
- **WHEN** 请求的路径既不匹配任何静态资源也不匹配任何控制器接口
- **THEN** 系统返回 HTTP 404 和携带 `traceId` 的统一错误响应，且不记录 ERROR 级别日志
