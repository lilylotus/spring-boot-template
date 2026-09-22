# Proposal

## Why

`/proxy/**` 路由目前是裸转发：`BootDemo` 变慢或挂掉时，网关会一直把请求压过去并占着 Netty 连接，故障顺着连接池向上蔓延；同样，没有任何配额限制，单个调用方刷接口就能把下游打垮。需要在网关侧给这条链路加上熔断和限流，让故障就地收敛。

## What Changes

- 引入 resilience4j 作为熔断与限流实现（`spring-cloud-starter-circuitbreaker-reactor-resilience4j` 已在 `build.gradle` 中声明，但**声明的 `5.0.2` 会被 BOM 降级到 `3.3.3`**，resilience4j 核心库解析为 `2.2.0`；本次顺带把声明版本改成与实际解析一致，避免误导）。
- 给 `proxy-boot-demo` 路由挂上两个过滤器：
    - `CircuitBreaker=...` —— 复用 gateway 内置的 `SpringCloudCircuitBreakerResilience4JFilterFactory`，熔断打开时 `forward:` 到降级接口。
    - 一个**新写的** `Resilience4jRateLimiter` 过滤器 —— gateway 内置限流器只有 Redis 和 Bucket4j 两种实现，没有 resilience4j 版本，必须自己实现 `GatewayFilterFactory`。
- 限流维度：整条路由共享一个总配额（保护下游，不区分调用方）。
- 新增降级接口，熔断返回 `503`、限流返回 `429`，统一 JSON 响应体并带上 traceId。
- resilience4j 的实例参数（滑动窗口、失败率阈值、半开策略、QPS 配额）放进 `application.yaml` 的 `resilience4j.circuitbreaker.instances.*` / `resilience4j.ratelimiter.instances.*`。

## Capabilities

### New Capabilities

- `gateway-resilience`: 网关对下游服务的故障隔离行为——熔断的触发与恢复、请求配额限制、以及被拦截时返回给调用方的降级响应。

### Modified Capabilities

- `gateway-routing`: 「/proxy 前缀请求转发到 BootDemo 服务」这条需求当前写的是无条件转发。加上限流和熔断后，请求可能在到达下游之前就被网关拦下，需要补上这个前置约束和对应场景。

## Impact

- **新增代码**：一个 `GatewayFilterFactory`（resilience4j 限流）、一个降级 Controller、对应单元/集成测试。
- **修改代码**：`build.gradle`（版本声明对齐）、`application.yaml`（路由过滤器 + resilience4j 配置）。
- **不改动**：`TraceFilter` 及 trace 包、`FeignService` / `Template4Client`（本次只保护网关路由这一条路径，Feign 调用不在范围内）。
- **依赖**：无新增坐标，`resilience4j-ratelimiter:2.2.0` 与 `resilience4j-spring-boot3:2.2.0` 已随 starter 进入 classpath，`RateLimiterAutoConfiguration` 会提供 `RateLimiterRegistry` bean。
- **行为变更（对调用方可见）**：`/proxy/**` 在过载或下游故障时会返回 `429` / `503`，而不是一直等待或透传下游错误。
- **运维**：熔断状态与限流指标可通过 resilience4j 的 micrometer/health 集成观察，本次不引入 actuator 端点配置。
