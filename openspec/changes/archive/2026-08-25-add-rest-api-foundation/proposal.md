## Why

当前模板缺少统一的 REST 响应契约、参数校验示例和异常出口，业务项目复制模板后往往需要重复搭建这些基础能力，而且不同接口容易形成不一致的错误结构。补齐这组能力后，正常响应与异常响应都能携带稳定字段和链路追踪标识，并为常用 Jakarta Validation 约束提供可直接参考的示例。

## What Changes

- 新增泛型统一响应模型 `RestResult<T>`，固定输出 `code`、`data`、`message`、`traceId`、`timestamp` 字段，其中 `timestamp` 为响应构造时的 Unix 毫秒时间戳。
- 新增 `RestResultUtils` 工具类，集中创建成功与失败响应，并从 Log4j2 `ThreadContext` 获取当前请求的 `traceId`。
- 配置 Jakarta Validation 快速失败模式，使一次校验在发现首个约束违规后停止继续检查。
- 新增覆盖 `@NotNull`、`@NotEmpty`、`@NotBlank`、`@Size`、`@Min`、`@Max`、`@Positive`、`@Negative`、`@Email`、`@Pattern`、`@Past`、`@Future`、`@DecimalMin`、`@DecimalMax` 的请求参数示例。
- 新增全局异常处理，将请求体校验、方法参数校验、参数绑定或解析错误以及未预期异常转换为统一响应，并保留适当的 HTTP 状态码。
- 新增相应的单元测试或轻量级 MVC 测试，验证响应结构、快速失败配置、约束示例和异常映射。

## Capabilities

### New Capabilities

- `rest-unified-response`: 定义 REST 接口成功与失败时的统一响应字段、构造方式及链路追踪标识来源。
- `request-validation`: 定义参数校验快速失败行为以及常用 Jakarta Validation 约束的可运行示例。
- `global-exception-handling`: 定义常见 Web 与校验异常到统一错误响应及 HTTP 状态码的转换行为。

### Modified Capabilities

无。

## Impact

- 主要影响 `com.example.template` 下的公共响应、校验配置、示例控制器或请求对象及全局异常处理代码。
- 复用现有 `TraceIdFilter` 写入 Log4j2 `ThreadContext` 的 `traceId`，不改变现有链路追踪流程。
- 复用已声明的 `spring-boot-starter-validation` 和 `spring-boot-starter-web`，预计无需新增生产依赖。
- 新增接口示例会进入现有 springdoc 控制器扫描范围，并以统一响应模型展示返回结构。
