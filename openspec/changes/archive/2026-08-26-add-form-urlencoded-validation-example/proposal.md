## Why

现有参数校验示例只覆盖 JSON 请求体和单个查询参数，缺少 `application/x-www-form-urlencoded` 表单请求绑定到 DTO 后触发 Jakarta Validation 的参考实现。增加该示例后，模板使用者可以直接复用 PUT 表单接口的绑定、校验、统一响应和统一异常处理方式。

## What Changes

- 在 `ValidationExampleController` 中新增一个 PUT 示例接口，只接受 `Content-Type: application/x-www-form-urlencoded`。
- 新增表单请求 DTO，示例字段覆盖非空、邮箱格式和数值范围校验，并通过 `@Valid` 触发校验。
- 合法表单请求返回包含已绑定 DTO 的统一成功响应；非法表单请求复用现有全局异常处理返回 HTTP 400 统一错误响应。
- 为新增接口和 DTO 补充 Springdoc/OpenAPI 描述，并增加成功、约束失败及 Content-Type 不匹配的 MVC 测试。
- 将不受支持的请求媒体类型转换为 HTTP 415 统一错误响应，避免被通用异常处理误判为服务器内部错误。
- 按最新 Spring Boot 规范移除 `ValidationExampleController` 的类级 `@RequestMapping`，将现有接口路径改写为方法级完整路径；对外 URL 保持不变。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `request-validation`: 增加 `application/x-www-form-urlencoded` PUT 表单绑定与参数校验示例的行为要求。
- `global-exception-handling`: 增加不受支持媒体类型到 HTTP 415 统一错误响应的转换要求。

## Impact

- 修改 `ValidationExampleController`，新增一个表单校验端点并规范现有方法级 URL 声明。
- 新增一个表单请求 DTO，复用现有 `RestResult`、`RestResultUtils`、Validation 快速失败配置和全局异常处理器。
- 修改全局异常处理器，补充不受支持媒体类型的专用映射。
- 扩展 `ValidationExampleControllerTest`，不新增生产依赖，也不需要 MySQL、Redis 或 Nacos。
- 现有 `/api/validation/example` 与 `/api/validation/positive` 的 HTTP 方法、URL 和响应契约均保持不变。
