## Context

参见 `proposal.md` 的动机。当前静态客户端已使用
`PoolingHttpClientConnectionManagerBuilder`，但连接池容量和生命周期仍采用第三方默认值。
现有 `HttpClientConfig` 是公开不可变 record，静态 `configure` 会原子创建并替换客户端运行时。

Apache HttpClient 5.6.4 已提供所需连接池 API，无需新增依赖。当前实现使用 5 秒默认连接超时和
请求超时，且同一请求超时同时用于连接池获取和响应等待，无法独立配置。

## Goals / Non-Goals

**Goals:**

- 为长期运行和并发调用提供有界、可回收的生产默认连接池。
- 保留不同负载场景调整容量和生命周期参数的能力。
- 将连接超时和响应超时分别映射到底层请求阶段，默认均为 3 秒。
- 保持静态客户端原子重配置、请求级响应超时覆盖和幂等关闭语义。
- 通过配置测试和本地服务集成测试验证容量限制、连接复用及生命周期。

**Non-Goals:**

- 不根据 CPU、主机数量或实时流量自动计算连接池大小。
- 不增加按路由单独配置不同容量或连接参数的 API。
- 不增加监控指标导出、动态扩缩容、重试、熔断或限流能力。
- 不允许关闭容量上限；连接池始终采用有界模式。

## Decisions

### 1. 使用独立不可变连接池配置类型

新增 `HttpConnectionPoolConfig` record，字段为 `maxTotalConnections`、`maxConnectionsPerRoute`、
`connectionTimeToLive`、`validateAfterInactivity` 和 `idleEvictionTimeout`。默认值分别为
200、50、5 分钟、5 秒和 30 秒，并集中校验容量与时长。

`HttpClientConfig` 增加 `connectionPoolConfig` 组件和构建器方法，默认使用
`HttpConnectionPoolConfig.defaults()`。保留 record 四参数辅助构造器并自动补入默认连接池配置，
显式传入空值仍被拒绝。该方案便于按部署负载调节，也避免向公共 API 暴露 Apache 枚举。

### 2. 使用严格容量和 LIFO 复用策略

`PoolingHttpClientConnectionManagerBuilder` 显式设置总连接数与单路由连接数，使用
`PoolConcurrencyPolicy.STRICT` 限制并发租用，使用 `PoolReusePolicy.LIFO` 优先复用最近连接，
并启用 `setOffLockDisposalEnabled(true)` 将潜在慢关闭移出池锁。

选择 `STRICT` 而非 `LAX` 是为了让容量配置具有可预测的背压语义；选择 `LIFO` 而非 `FIFO` 可集中使用少量活跃连接，让较老空闲连接更容易被回收。超额请求继续通过既有连接池获取超时失败，不另建信号量或排队器。

### 3. 同时配置连接生命周期和主动回收

Apache HttpClient 5.6.4 已将连接管理器构建器上的相关生命周期方法标记为弃用，因此通过
`ConnectionConfig.Builder.setTimeToLive` 和 `setValidateAfterInactivity` 组成默认连接配置，再由
`PoolingHttpClientConnectionManagerBuilder.setDefaultConnectionConfig` 应用。客户端启用过期与空闲连接回收，
并由现有 `ClientRuntime.close()` 在重配置和关闭时停止后台线程并释放连接。

TTL 限制连接绝对寿命，空闲校验降低服务端已关闭连接被复用的概率，空闲回收减少低流量阶段资源占用，三者职责不同且同时保留。默认值是通用生产基线，不代表所有流量模型的容量测算结果，调用方仍应按下游数量和并发调整。

### 4. 独立配置连接超时和响应超时

`HttpClientConfig` 保留 `connectTimeout`，将 `requestTimeout` 更名为 `responseTimeout`，
并将两者默认值均设为 3 秒。项目尚未发布稳定二进制，因此不保留会继续混淆语义的旧别名。

`createRequestConfig` 接收独立的连接超时和响应超时。连接超时通过
`ConnectionConfig.setConnectTimeout` 约束 TCP 连接建立，并通过
`RequestConfig.setConnectionRequestTimeout` 约束从连接池获取连接。响应超时通过
`RequestConfig.setResponseTimeout` 约束响应等待，并通过 `ConnectionConfig.setSocketTimeout`
约束套接字读取。不使用已弃用的 `RequestConfig.setConnectTimeout`。

单次请求的 `timeout` 继续作为响应超时覆盖，调用 `createRequestConfig`
时与全局连接超时组合，不再改变连接池获取超时。

## Risks / Trade-offs

- [200 个总连接可能对低资源环境偏高] → 该值只是上限且连接按需创建，同时允许调用方使用更小配置。
- [后台空闲回收线程增加一个客户端级线程] → 线程随客户端关闭，重配置由写锁串行完成并关闭旧客户端，避免线程泄漏。
- [过短 TTL 或空闲回收时间会降低复用率] → 强制正值并提供保守默认值，文档说明调优影响。
- [LIFO 可能让部分连接长期空闲] → 主动空闲回收会释放这些连接，这正是集中复用策略的预期结果。
- [新增 record 组件影响旧二进制调用方] → 当前项目尚未发布稳定二进制；源码层保留四参数辅助构造器，调用方重新编译即可兼容。
- [`requestTimeout` 更名会影响现有源码调用方] → 在变更说明中标记为破坏性变更，调用方将访问器和构建器方法改为 `responseTimeout`。

## Migration Plan

1. 新增连接池配置模型并接入 `HttpClientConfig` 默认值与构建器。
2. 将配置映射到 Apache 连接管理器和客户端回收策略。
3. 拆分连接与响应超时映射，并将默认值均调整为 3 秒。
4. 使用本地 HTTP 服务和小容量测试配置验证上限、超时、复用与 TTL 淘汰。
5. 运行完整测试和构建；回滚时移除新增配置组件及映射，恢复原连接管理器构造逻辑。
