## Context

参见 `proposal.md` 的变更动机。现有 `ValidationExampleController` 已演示 JSON 请求体和查询参数校验，但通过类级 `@RequestMapping` 组合 URL，与当前 Spring Boot 规范要求的方法级完整路径不一致。现有全局异常处理器包含通用 `Exception` 兜底，如果不为媒体类型不匹配增加更具体的处理器，表单端点收到 JSON 时可能被错误映射为 HTTP 500。

## Goals / Non-Goals

**Goals:**

- 展示 PUT 表单编码请求绑定到 DTO 并由 `@Valid` 触发校验的标准写法。
- 保持 Controller 只负责参数接收、校验触发和统一响应构造，不引入业务逻辑或基础设施调用。
- 确保新增和既有接口都在方法级声明完整 URL，并保持既有 URL 不变。
- 让不受支持的 Content-Type 保留 HTTP 415 语义并返回统一响应。

**Non-Goals:**

- 不新增 Service 层，因为示例接口没有业务处理，只原样返回已校验 DTO。
- 不演示文件上传、`multipart/form-data`、校验分组或自定义约束注解。
- 不修改 Validation 快速失败配置、统一响应字段或现有校验错误的 HTTP 400 映射。

## Decisions

### 1. 使用独立表单 DTO 和 `@ModelAttribute` 绑定

新增不可变表单请求记录，字段为 `username`、`email`、`age`：`username` 使用 `@NotBlank` 和 `@Size(min = 2, max = 20)`，`email` 使用 `@NotBlank` 和 `@Email`，`age` 使用 `@NotNull`、`@Min(18)` 和 `@Max(120)`。控制器参数显式使用 `@Valid @ModelAttribute`，清楚表达表单字段绑定和 DTO 校验的关系。

备选方案是直接在多个 `@RequestParam` 上声明约束，但字段增加后方法签名会迅速膨胀，也不能展示实际项目中更常见的表单 DTO 用法。

### 2. PUT 映射显式限制表单媒体类型

新增方法使用完整路径 `/api/validation/form`，并通过 `consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE` 限制媒体类型。合法请求通过 `RestResultUtils.success` 返回绑定后的 DTO，不手写校验或异常捕获。

备选方案是不声明 `consumes`，虽然表单请求仍可绑定，但接口也会接受未在示例范围内的媒体类型，无法形成清晰契约。

### 3. 现有接口改为方法级完整 URL

删除类级 `@RequestMapping("/api/validation")`，把现有 POST 和 GET 映射分别改为 `/api/validation/example`、`/api/validation/positive`。这是注解位置调整，对外方法、路径和响应行为不变，同时满足最新 Spring Boot Controller 规范。

### 4. 为媒体类型不匹配增加专用异常映射

全局异常处理器增加对 Spring MVC 媒体类型不支持异常的处理，返回 HTTP 415，响应业务码同为 `415`，固定中文提示为“请求媒体类型不支持”。该处理器比通用异常处理器更具体，避免客户端 Content-Type 错误落入 HTTP 500 兜底。

备选方案是依赖 Spring MVC 默认错误结构，但会破坏项目统一响应契约。

### 5. 使用聚焦 Web 层测试验证完整交互

扩展现有 MVC 切片测试，使用 PUT、`application/x-www-form-urlencoded` 和表单参数验证合法请求；分别提交非法字段和 JSON Content-Type，验证 HTTP 400、HTTP 415、统一响应、`traceId` 与时间戳。既有 POST、GET 测试继续保护路径重写不改变外部行为。

## Risks / Trade-offs

- [记录类型的表单构造器绑定依赖参数名发现] → 项目使用 Java 21 且编译器可保留记录组件信息，并用 MVC 测试验证真实绑定结果。
- [快速失败时多个非法字段的首项顺序不固定] → 测试每次只构造一个非法字段，或只断言返回一项允许的中文消息。
- [全局新增 HTTP 415 映射影响其他控制器] → 该行为只纠正媒体类型不支持异常的 HTTP 语义，并保持统一响应格式。

## Migration Plan

1. 新增表单 DTO。
2. 调整控制器完整路径并增加 PUT 表单接口。
3. 增加 HTTP 415 全局异常映射。
4. 扩展并运行无外部基础设施依赖的 MVC 目标测试，再执行完整测试并记录环境性失败。

回滚时移除新增 DTO、PUT 方法和 HTTP 415 处理器，并恢复类级路径组合；不涉及数据迁移。
