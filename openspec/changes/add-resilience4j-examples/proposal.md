## Why

本项目是一个供其他服务复制/派生的 Spring Boot 模板，目前 `build.gradle` 已经引入了
`spring-cloud-starter-circuitbreaker-resilience4j` 依赖（原本是给 OpenFeign 调用做熔断用的），
但仓库里没有任何实际使用示例，新使用者不知道该如何在业务代码中接入断路器、重试、限流器、隔板这几种
容错能力，也不清楚对应的 `application.yml` 配置项该怎么写。需要补充一套可直接参考、可运行的示例代码
和配置，降低后续基于本模板开发的服务接入 resilience4j 的门槛。

## What Changes

- 新增 `com.example.template.resilience4j` 包，包含断路器（`@CircuitBreaker`）、重试（`@Retry`）、
  限流器（`@RateLimiter`）、隔板（`@Bulkhead`）四种能力各自独立的 Service 示例方法，每个方法都配有
  对应的 fallback 方法，并在方法/类上按项目注释规范说明用途和执行逻辑。
- 新增一个 Demo Controller，暴露四个 HTTP 端点分别触发上述四种示例，便于手动验证效果（例如反复调用
  断路器端点观察熔断状态切换、并发调用限流器/隔板端点观察拒绝行为）。
- 在 `application.yml` 中新增 `resilience4j.circuitbreaker` / `resilience4j.retry` /
  `resilience4j.ratelimiter` / `resilience4j.bulkhead` 四段示例配置，并保持仓库现有的详尽中文注释
  风格，解释每个关键参数的含义和取值考虑。
- 不改动现有 `OpenFeignConfig`/`FeignHttpClientConfig` 等既有 Feign 相关代码，本次仅新增独立示例，
  不影响现有请求链路。

## Capabilities

### New Capabilities
- `resilience4j-examples`：在模板中提供断路器、重试、限流器、隔板四种 resilience4j 能力的最小可运行
  示例（Service 方法 + fallback + Controller 端点 + `application.yml` 配置）。

### Modified Capabilities
（无，本次不修改任何既有能力的行为）

## Impact

- 新增代码：`src/main/java/com/example/template/resilience4j/` 下的 Service、Controller、
  自定义异常/DTO（如需要）。
- 配置变更：`src/main/resources/application.yml` 新增 `resilience4j.*` 配置段。
- 依赖：实现过程中发现已有的 `spring-cloud-starter-circuitbreaker-resilience4j` 并不足够——它只
  间接引入了断路器/重试/限流器/超时限制，隔板(Bulkhead)模块未被带入；而且 resilience4j-spring6 里
  的各 `@Aspect` 切面依赖 AspectJ 的注解切点解析能力，仅有 `spring-aop` 无法使注解生效（表现为
  `@CircuitBreaker`/`@Retry` 等注解被静默忽略，方法直接抛出原始异常，fallback 不会被调用）。因此
  额外新增了两个 Gradle 依赖：`io.github.resilience4j:resilience4j-bulkhead`（版本沿用
  spring-cloud-dependencies BOM 的托管版本）和 `org.springframework.boot:spring-boot-starter-aop`。
- 不影响现有 Feign、Redis、MyBatis 等既有能力，纯新增示例代码，供后续基于本模板的服务参考复制。
