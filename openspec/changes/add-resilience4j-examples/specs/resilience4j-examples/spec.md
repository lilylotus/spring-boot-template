## Purpose

为基于本模板派生的服务提供断路器、重试、限流器、隔板四种 resilience4j 容错能力的可运行参考示例，
让开发者能够直接照抄示例中的注解用法和 `application.yml` 配置来接入自己的业务方法。

## ADDED Requirements

### Requirement: 断路器示例
系统 SHALL 提供一个使用 `@CircuitBreaker` 注解的示例方法，并在对应 fallback 方法中返回一个明确
标识"熔断降级"的结果，同时通过 HTTP 端点暴露该示例，便于外部反复调用以观察熔断器在 CLOSED /
OPEN / HALF_OPEN 之间的状态切换。

#### Scenario: 被调用方法正常返回
- **WHEN** 断路器处于 CLOSED 状态且被保护的方法执行成功
- **THEN** HTTP 端点返回被保护方法本身的正常结果，不触发 fallback

#### Scenario: 触发熔断降级
- **WHEN** 断路器处于 OPEN 状态时端点被调用
- **THEN** 请求不会真正执行被保护方法，而是直接返回 fallback 方法的降级结果

### Requirement: 重试示例
系统 SHALL 提供一个使用 `@Retry` 注解的示例方法，模拟对下游的间歇性失败调用，并配置最大重试次数
和重试间隔；当所有重试均失败后 SHALL 调用 fallback 方法返回降级结果。

#### Scenario: 重试后成功
- **WHEN** 被保护方法前几次调用失败但在配置的最大重试次数内成功
- **THEN** HTTP 端点最终返回成功结果，且实际执行次数不超过配置的最大重试次数

#### Scenario: 重试全部耗尽
- **WHEN** 被保护方法在配置的最大重试次数内始终失败
- **THEN** 系统 SHALL 调用 fallback 方法并返回其降级结果，而不是把异常抛给调用方

### Requirement: 限流器示例
系统 SHALL 提供一个使用 `@RateLimiter` 注解的示例方法，并配置一个较小的单位时间许可数
（用于演示效果），超出限流阈值的请求 SHALL 被 fallback 方法拦截并返回明确标识"被限流"的结果。

#### Scenario: 未超出限流阈值
- **WHEN** 单位时间内的请求数不超过配置的限流阈值
- **THEN** 端点正常执行被保护方法并返回其结果

#### Scenario: 超出限流阈值
- **WHEN** 单位时间内的请求数超过配置的限流阈值
- **THEN** 超出部分的请求 SHALL 被拒绝并由 fallback 方法返回限流降级结果

### Requirement: 隔板示例
系统 SHALL 提供一个使用 `@Bulkhead` 注解的示例方法，限制该方法的最大并发调用数；当并发数超过配置
上限时，多余请求 SHALL 被 fallback 方法拦截并返回明确标识"被隔板拒绝"的结果。

#### Scenario: 并发数未超出上限
- **WHEN** 同时调用被保护方法的并发数不超过配置的隔板最大并发数
- **THEN** 每个请求都正常执行被保护方法并返回其结果

#### Scenario: 并发数超出上限
- **WHEN** 同时调用被保护方法的并发数超过配置的隔板最大并发数
- **THEN** 超出上限的请求 SHALL 被拒绝并由 fallback 方法返回降级结果
