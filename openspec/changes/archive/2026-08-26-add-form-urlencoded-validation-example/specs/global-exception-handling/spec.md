## ADDED Requirements

### Requirement: 不受支持媒体类型转换为统一错误响应
系统 MUST（必须）捕获请求 Content-Type 与接口支持的媒体类型不匹配产生的异常，返回 HTTP 415；响应 `code` 必须为 `415`、`message` 必须为稳定的中文提示、`data` 必须为 `null`，并携带当前请求的 `traceId` 和响应构造时间戳。

#### Scenario: 表单接口收到 JSON 请求
- **WHEN** 只接受 `application/x-www-form-urlencoded` 的接口收到 `application/json` 请求
- **THEN** 系统返回 HTTP 415 和统一错误响应，不得将该客户端错误转换为 HTTP 500
