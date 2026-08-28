## Why

当前 `HttpClients` 未显式限制生产并发容量、连接寿命、空闲校验和回收策略，
高并发或长期运行时容易复用失效连接或长期占用空闲连接。连接池获取和响应等待还共用一个超时，
无法分别调优两个阶段。

## What Changes

- 新增不可变的 HTTP 连接池配置模型，并由 `HttpClientConfig` 提供生产推荐默认值和显式覆盖入口。
- 默认连接池总连接数为 200、单路由连接数为 50，使用严格并发上限和 LIFO 连接复用策略。
- 默认连接存活期为 5 分钟，连接空闲 5 秒后在复用前校验，并回收过期连接和空闲超过 30 秒的连接。
- 启用锁外连接释放，降低连接关闭操作对池锁竞争的影响。
- 校验连接池容量和时长参数，拒绝零值、负值、单路由上限大于总上限及底层客户端无法表达的时长。
- 将客户端配置的超时明确分为连接超时和响应超时，两者默认均为 3 秒。
- 连接超时独立约束连接建立和连接池获取，响应超时独立约束响应等待和套接字读取。
- 增加覆盖默认值、配置映射、并发上限、连接复用和空闲回收的自动化测试。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `http-client-configuration`: 增加生产级共享连接池配置，并将连接与响应超时拆分为独立配置。

## Impact

- 修改 `org.example.simple.http.HttpClientConfig` 和 `HttpClients`，新增 HTTP 连接池配置类型。
- `HttpClientConfig` 构建器增加连接池配置入口，并将 `requestTimeout` 更名为
  `responseTimeout`；该公开访问器和构建器方法变更为 **BREAKING**。
- 不新增第三方依赖，不修改 HTTP 请求、响应和请求体公开语义。
- 连接回收会创建由 Apache HttpClient 管理的后台清理线程，并在 `HttpClients.shutdown()` 或全局重配置时随客户端关闭。
