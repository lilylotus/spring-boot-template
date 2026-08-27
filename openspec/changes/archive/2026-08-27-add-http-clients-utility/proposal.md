## Why

项目已经引入 Apache HttpClient 5 和 Jackson 3，但缺少统一、易复用的 HTTP 客户端封装，调用方需要重复处理请求构造、内容类型、超时、SSL 和 JSON 转换。新增 `HttpClients` 工具类可统一这些行为，并为常用同步 HTTP 调用提供稳定接口。

## What Changes

- 新增支持通过 `HttpClients.get/post/put/delete` 静态方法直接发送同步 HTTP 请求的工具，公开调用入口接收 URL 文本链接，无需创建客户端实例或构造 `URI` 对象。
- 支持 URL 查询参数以及 `multipart/form-data`、`application/x-www-form-urlencoded`、`application/json` 和二进制文件请求体。
- 支持获取原始响应，并将 JSON 响应直接转换为普通对象或带泛型的对象类型。
- 支持线程安全地分别配置静态全局客户端的连接超时和请求超时；默认均为 5 秒，并允许每次请求覆盖请求超时配置。
- 支持显式配置跳过 SSL 证书与主机名校验，默认保持严格校验。
- 默认初始化专用 `ObjectMapper`：反序列化忽略不存在于目标类型中的字段，序列化省略值为 `null` 的属性，并以 ISO-8601 字符串处理 Java 时间类型；仍允许调用方注入自定义映射器。
- 统一参数错误、网络错误、非成功 HTTP 状态和 JSON 转换错误的异常模型。
- 增加覆盖请求方法、请求体类型、响应转换、SSL 与超时行为的自动化测试。

## Capabilities

### New Capabilities

- `http-client-utility`: 定义通用 HTTP 请求发送、请求体编码、响应读取与 JSON 类型转换的行为。
- `http-client-configuration`: 定义 SSL 校验策略以及全局和单次请求超时的优先级与约束。

### Modified Capabilities

无。

## Impact

- 在 `org.example.simple` 基础包下新增 HTTP 客户端工具及配套请求、响应、配置和异常类型。
- `HttpClients` 静态方法和请求构建器使用类似 `http://127.0.0.1:23456/hello` 的 `String` URL；URL 解析仅作为内部实现细节。
- `HttpClients` 管理进程级共享连接池，提供全局配置替换和幂等关闭入口，不要求调用方持有或关闭客户端实例。
- `HttpClientConfig.defaults()` 提供 5 秒连接超时、5 秒请求超时、严格 SSL 校验和已初始化的 HTTP 专用 JSON 映射器。
- 复用现有 `org.apache.httpcomponents.client5:httpclient5` 与 `tools.jackson.core:jackson-databind` 依赖；如实现 multipart 构造所需模块未被当前依赖传递提供，将补充最小必要依赖。
- 不修改现有 RPC 能力和公开接口，不引入 Spring MVC Controller。
- SSL 跳过校验属于安全敏感选项，仅在调用方明确启用时生效。
