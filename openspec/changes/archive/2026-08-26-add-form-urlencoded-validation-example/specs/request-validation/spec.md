## ADDED Requirements

### Requirement: 提供表单编码请求校验示例
系统 MUST（必须）提供一个路径为 `/api/validation/form` 的 PUT 示例接口，该接口只接受 `application/x-www-form-urlencoded` 请求，并把 `username`、`email`、`age` 表单字段绑定到请求 DTO 后通过 Jakarta Validation 统一校验。

#### Scenario: 合法表单请求
- **WHEN** 调用方以 `application/x-www-form-urlencoded` 提交非空且长度为 2 到 20 的 `username`、合法格式的 `email` 和 18 到 120 之间的 `age`
- **THEN** 系统返回 HTTP 200 和统一成功响应，响应数据包含绑定后的 `username`、`email`、`age`

#### Scenario: 表单字段违反约束
- **WHEN** 调用方以 `application/x-www-form-urlencoded` 提交违反任一 DTO 字段约束的表单请求
- **THEN** 系统返回 HTTP 400 和包含首项中文校验消息的统一错误响应

#### Scenario: 请求媒体类型不受支持
- **WHEN** 调用方使用 PUT 请求访问 `/api/validation/form`，但 Content-Type 不是 `application/x-www-form-urlencoded`
- **THEN** 系统返回 HTTP 415 和统一错误响应

### Requirement: 校验示例接口路径在方法级完整声明
参数校验示例控制器中的每个接口 MUST（必须）在方法级映射注解中直接声明完整 URL，不得依赖类级公共路径前缀；调整后既有接口的 HTTP 方法和 URL 必须保持不变。

#### Scenario: 访问既有 JSON 请求体校验接口
- **WHEN** 调用方继续向 `/api/validation/example` 发送 POST 请求
- **THEN** 系统按原有契约执行请求体校验，不因映射注解调整而改变 URL 或响应行为

#### Scenario: 访问既有方法参数校验接口
- **WHEN** 调用方继续向 `/api/validation/positive` 发送 GET 请求
- **THEN** 系统按原有契约执行方法参数校验，不因映射注解调整而改变 URL 或响应行为
