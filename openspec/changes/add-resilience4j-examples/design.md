## Context

`build.gradle` 已引入 `spring-cloud-starter-circuitbreaker-resilience4j`（用于 Spring Cloud 的
`CircuitBreaker` 抽象），该 starter 间接依赖 `resilience4j-spring-boot3`，后者提供了
`@CircuitBreaker`、`@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter` 这些基于 AOP 切面的注解，
自动装配后无需额外引入依赖或配置类即可直接在 `@Service` 方法上使用。本次改动只新增示例代码和
`application.yml` 配置，不涉及既有 Feign/Redis/MyBatis 代码路径。参见 `proposal.md` - Why。

## Goals / Non-Goals

**Goals:**
- 四种能力（断路器、重试、限流器、隔板）各自独立、互不依赖，每种都能单独演示"正常路径"和
  "降级路径"两种行为。
- 配置集中写在 `application.yml`，注解上只引用配置名（`name = "xxx"`），不在注解里硬编码阈值，
  方便使用者直接改 yml 调参数。
- 保持仓库现有的详尽中文注释风格（参考 `RedisConfig`、`MybatisPlusConfig`）。

**Non-Goals:**
- 不引入 `@TimeLimiter`（超时控制）示例——与 `@Retry`/`@CircuitBreaker` 组合使用时语义和线程模型
  （要求返回 `CompletableFuture`）比较绕，容易分散示例的重点，本次范围只覆盖用户明确要求的四种。
- 不演示注解组合叠加（如 `@CircuitBreaker` + `@Retry` 叠加在同一方法上）的顺序问题，四个示例保持
  各自独立、职责单一。
- 不重新启用 `FeignHttpClientConfig`，也不把断路器接到真实的 Feign 调用上——示例用方法内部模拟的
  失败/延迟逻辑，避免示例依赖外部服务是否可用。
- 不新增自动化测试用例（本次是"可手动调用观察效果"的演示代码，不是要长期维护验证的业务逻辑）。

## Decisions

- **包结构**：`com.example.template.resilience4j` 下按能力分文件而不是分子包——
  `CircuitBreakerDemoService`、`RetryDemoService`、`RateLimiterDemoService`、`BulkheadDemoService`
  四个 Service，加一个 `Resilience4jDemoController` 统一暴露端点。四种能力体量都很小（一个方法+一个
  fallback），拆子包只会增加层级、不增加可读性；与仓库里 `redis`/`openfeign` 这类按领域分包但不再
  往下分层的现状一致。
- **fallback 参数签名**：全部使用 `(原始参数..., Throwable t)` 形式的 fallback，方法名统一
  `xxxFallback`，是 resilience4j 官方推荐、也是最直观能在 Controller 里区分"正常结果"和"降级结果"
  的方式（返回体里显式标注 `fallback: true` 之类字段）。
- **模拟失败的方式**：
  - 断路器示例：用一个原子计数器/随机数模拟"下游服务不稳定"，多次调用后一部分抛异常触发熔断统计,
    不依赖真实网络调用，保证示例在没有任何外部依赖的情况下也能演示状态切换。
  - 重试示例：用一个基于调用次数的计数器，前 N-1 次抛可重试异常、第 N 次成功，直观展示"重试后成功"
    这条路径；同时保留一个"永远失败"的方法/端点用于展示"重试耗尽走 fallback"这条路径。
  - 限流器/隔板示例：不需要模拟失败，直接靠配置一个很小的阈值（如限流器每秒 2 次许可、隔板最大
    并发 2）+ 一个人为 `Thread.sleep` 的耗时操作，配合并发请求就能观察到限流/拒绝效果。
- **配置命名**：`resilience4j.circuitbreaker.instances.demo`、`resilience4j.retry.instances.demo`、
  `resilience4j.ratelimiter.instances.demo`、`resilience4j.bulkhead.instances.demo`，实例名统一叫
  `demo`，注解里 `name = "demo"` 直接对应，避免使用者需要在多个不一致的命名之间做映射。
- **Controller 端点**：`GET /api/resilience4j/circuit-breaker`、`/retry`、`/rate-limiter`、
  `/bulkhead`，都放在同一个 Controller 里，路径前缀体现这是示例/演示性质的接口，和真实业务
  Controller（`WelcomeController`）区分开。

## Risks / Trade-offs

- [用计数器/随机数模拟失败而非接入真实下游] → 示例的"失败"是确定性/半确定性的人工构造，不完全等价于
  生产环境里的真实网络抖动；但换取的是示例可以脱离外部依赖独立运行、且行为可预测、便于在 README/
  注释里准确描述"调用几次会看到什么效果"。
- [限流器/隔板依赖并发调用才能观察到拒绝效果，单次 curl 看不出来] → 在类注释/方法注释里写清楚
  "需要并发调用才能触发限流/隔板拒绝"，并给出示例调用方式（如 `for` 循环并发 curl 或者用压测工具),
  避免使用者误以为配置没生效。
- [四个 Service 独立、不共享父类/工具类] → 略有重复（比如构造返回结果的样板代码），但每种能力
  逻辑都很短，共享抽象反而会让人难以单独复制某一种能力到自己的项目里；符合仓库"避免为演示用途做过度
  抽象"的取向。
