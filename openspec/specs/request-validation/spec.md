# request-validation Specification

## Purpose

为模板提供可直接运行和复制的 Jakarta Validation 参数校验范例，并通过快速失败减少无意义的后续约束检查，让接口在首个校验错误出现后立即返回。

## Requirements

### Requirement: 校验采用快速失败模式
系统 MUST（必须）启用参数校验快速失败模式；单次请求存在多个约束违规时，本次校验必须只产生一个违规结果，不得继续收集其余违规。

#### Scenario: 请求同时违反多个约束
- **WHEN** 请求中的多个字段或同一字段的多个约束同时不满足要求
- **THEN** 校验结果只包含本次校验首先发现的一项违规信息

### Requirement: 提供常用对象约束示例
系统 MUST（必须）提供可运行的请求体校验示例，分别演示 `@NotNull`、`@NotEmpty`、`@NotBlank` 和带 `min`、`max` 边界的 `@Size`，并为每项约束配置清晰的中文错误消息。

#### Scenario: 空值与长度约束不满足
- **WHEN** 调用方提交违反任一空值或长度约束的请求体
- **THEN** 接口拒绝请求并返回该约束对应的中文错误消息

### Requirement: 提供数值约束示例
系统 MUST（必须）提供可运行的请求体校验示例，分别演示 `@Min`、`@Max`、`@Positive`、`@Negative`、`@DecimalMin` 和 `@DecimalMax`，并为边界值和越界值定义可验证的行为。

#### Scenario: 数值位于允许边界
- **WHEN** 调用方提交等于 `@Min`、`@Max`、`@DecimalMin` 或 `@DecimalMax` 所声明边界的数值
- **THEN** 相应边界约束校验通过

#### Scenario: 数值违反方向或边界约束
- **WHEN** 调用方提交违反任一数值方向或边界约束的请求体
- **THEN** 接口拒绝请求并返回该约束对应的中文错误消息

### Requirement: 提供文本格式约束示例
系统 MUST（必须）提供可运行的请求体校验示例，分别演示 `@Email` 和带明确正则表达式的 `@Pattern`，并为每项约束配置清晰的中文错误消息。

#### Scenario: 文本格式不合法
- **WHEN** 调用方提交不符合电子邮箱格式或指定正则表达式的文本
- **THEN** 接口拒绝请求并返回相应格式约束的中文错误消息

### Requirement: 提供时间约束示例
系统 MUST（必须）提供可运行的请求体校验示例，分别演示 `@Past` 和 `@Future`，并为每项约束配置清晰的中文错误消息。

#### Scenario: 时间方向不合法
- **WHEN** `@Past` 字段不是过去时间或 `@Future` 字段不是未来时间
- **THEN** 接口拒绝请求并返回相应时间约束的中文错误消息

### Requirement: 合法请求返回统一响应
校验示例接口 MUST（必须）在请求体全部满足约束时返回统一成功响应，并把已校验的请求数据作为响应数据返回。

#### Scenario: 所有约束均满足
- **WHEN** 调用方提交满足全部示例约束的请求体
- **THEN** 接口返回 HTTP 200 和统一成功响应，响应数据为提交的请求数据

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
