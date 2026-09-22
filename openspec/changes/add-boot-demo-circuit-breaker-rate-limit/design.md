# Design

## Context

动机见 `proposal.md` - Why。与实现相关的现状约束（均已在本机核实）：

- **实际解析版本与声明不符。** `build.gradle` 声明 `spring-cloud-starter-circuitbreaker-reactor-resilience4j:5.0.2`，BOM `spring-cloud-dependencies:2025.0.3` 把 `spring-cloud-circuitbreaker-resilience4j` 降到 **3.3.3**，resilience4j 核心库统一解析为 **2.2.0**（`./gradlew dependencies` 显示 `5.0.2 -> 3.3.3`、`2.3.0 -> 2.2.0`）。这和 gateway 那次是同一个坑。
- **熔断有现成实现。** `spring-cloud-gateway-server-4.3.5.jar` 里有 `SpringCloudCircuitBreakerResilience4JFilterFactory` 和 `GatewayResilience4JCircuitBreakerAutoConfiguration`，配上 reactor-resilience4j starter 就能直接用 `CircuitBreaker=` 过滤器。其 `Config` 支持 `name`、`fallbackUri`、`statusCodes`、`resumeWithoutError` 四个参数。
- **限流没有现成实现。** 同一个 jar 里的限流器只有 `RedisRateLimiter` 和 `Bucket4jRateLimiter`，**没有 resilience4j 版本**。`RequestRateLimiter=` 过滤器绑定的是 `RateLimiter` 这个 gateway 自己的接口，不是 resilience4j 的。
- **resilience4j 的 Spring Boot 集成已在 classpath。** `resilience4j-spring-boot3:2.2.0` 随 starter 引入，其 `RateLimiterAutoConfiguration` 会根据 `resilience4j.ratelimiter.*` 配置提供 `RateLimiterRegistry` bean，`CircuitBreakerAutoConfiguration` 同理提供 `CircuitBreakerRegistry`。
- 项目没有 Redis 依赖，也没有统一响应包装类（`WelcomeController` 直接返回 `Mono<Map<String, Object>>`）；没有 springdoc 依赖，因此不需要 OpenAPI 注解。
- `TraceFilter` order 为 `HIGHEST_PRECEDENCE`，早于所有网关过滤器，所以降级响应里能拿到 traceId。

## Goals / Non-Goals

**Goals:**

- 熔断尽量复用框架现成能力，只在确实没有实现的限流上写代码。
- 熔断与限流的参数全部外置到配置，调参不需要改代码、不需要重新编译。
- 两种拦截返回同一套 JSON 结构，调用方只需要按状态码区分。

**Non-Goals:**

- 不做分布式限流（见 Risks，这是 resilience4j 的固有限制）。
- 不做重试与舱壁隔离（resilience4j 的 retry/bulkhead 本次不启用）。超时控制不是可选项：熔断的响应式实现强制带一层 TimeLimiter，只能配、不能不配，见 D8。
- 不保护 `FeignService` / `Template4Client` 的调用路径。
- 不按调用方身份（IP / 租户 / 用户）区分配额。
- 不引入 `Result<T>` 统一响应包装类 —— 项目目前没有，为一个降级接口引入属于跨切面重构，应单独立项。

## Decisions

### D1：熔断用内置 `CircuitBreaker` 过滤器，不自己写

`SpringCloudCircuitBreakerResilience4JFilterFactory` 已经处理好了响应式链路的包装、fallback 转发和异常翻译。

- 备选：自己写 `GlobalFilter` 调 `CircuitBreakerRegistry`。要重新实现 fallback 转发和取消语义，纯属重复造轮子。**否决。**

### D2：限流自己写 `GatewayFilterFactory`

没有现成实现（见 Context），只能写。继承 `AbstractGatewayFilterFactory<Config>`，从注入的 `RateLimiterRegistry` 按配置的实例名取 `RateLimiter`，放行则 `chain.filter(exchange)`，拒绝则直接写 `429`。

- 备选：引入 Redis + 内置 `RequestRateLimiter`。能做到分布式精确限流，但要新增 Redis 依赖和一套运维成本，也不是「用 resilience4j」这个需求。**否决，但记入 Risks 作为将来的升级路径。**
- 备选：改用内置的 `Bucket4jRateLimiter`。同样不是 resilience4j，且仍需 Redis 之类的外部存储才能跨实例。**否决。**

### D3：`timeout-duration` 必须配成 `0`（关键约束）

resilience4j 的 `RateLimiter.acquirePermission()` 在配额耗尽时会**阻塞当前线程**直到 `timeoutDuration` 超时。在 WebFlux 里这个线程是 Netty 事件循环，一旦阻塞就是全局性事故 —— 限流反而成了压垮网关的原因。

因此：`resilience4j.ratelimiter.instances.*.timeout-duration` MUST 为 `0`，让它立即返回 `false`。这条也写进 spec（「MUST NOT 排队等待配额释放」）。实现里还要再加一道保险：过滤器启动时校验该实例的 `timeoutDuration` 为零，不为零就快速失败并给出明确提示，避免有人在 Nacos 上随手改出线上事故。

### D4：过滤器顺序 —— 限流在前，熔断在后

网关按 `filters:` 列表的下标分配 order。顺序定为 `RateLimiter` → `CircuitBreaker` → `StripPrefix` → `PreserveHostHeader`。

理由：超配额的请求根本没有真正调用下游，不应该污染熔断的失败率统计样本。反过来排会让一次限流风暴把熔断也带开。

### D5：熔断返回走 `fallbackUri` 转发，限流直接写响应

- 熔断：`CircuitBreaker=name=...,fallbackUri=forward:/fallback/boot-demo`，由 `FallbackController` 返回 `503` + JSON。这是该过滤器的既定用法。
- 限流：过滤器自己写 `429` + JSON。此时请求还没进入熔断包装，再 `forward` 一次等于多绕一圈内部路由，没有收益。

两处的 JSON 由同一个组件构造，保证结构一致（spec 要求「结构一致」）。

- fallback 路径选 `/fallback/**`，**不能**落在 `/proxy/**` 下，否则会被自己的路由再匹配一次形成回环。

### D6：熔断的失败口径要显式配 `statusCodes`

`SpringCloudCircuitBreakerFilterFactory` 默认只把**异常**（连接失败、超时）计入失败；下游返回的 5xx 是一个正常完成的响应，不配 `statusCodes` 就不会计入。spec 要求 5xx 计入失败率、4xx 不计入，所以必须显式列出 5xx 状态码。

### D7：配置落本地 `application.yaml`

与 `2026-09-22-add-proxy-route-forwarding` 保持一致。Nacos 覆盖风险同样适用（见 Risks）。

### D8：必须显式配置 TimeLimiter（实现阶段发现，对确认稿的修正）

`ReactiveResilience4JCircuitBreaker` 会给每次调用套一层 `Mono.timeout(timeLimiterConfig.getTimeoutDuration())`
(已在 3.3.3 的字节码中确认)，而 resilience4j 的 `TimeLimiterConfig` 默认超时是 **1 秒**。

这意味着按本文档最初确认的配置，所有 `/proxy/**` 请求都会被静默截断在 1 秒，且 D 系列里
`slow-call-duration-threshold: 3s` 这个阈值永远够不到——确认稿在这一点上是自相矛盾的。

因此补上：

```yaml
resilience4j:
  timelimiter:
    instances:
      bootDemoCircuitBreaker:      # 实例名必须与 CircuitBreaker 过滤器的 name 一致
        timeout-duration: 5s       # 与 Feign 的 read-timeout 对齐
        cancel-running-future: false
```

三个阈值由此形成一条连贯的梯度：3s 起算慢调用(计入 slowCallRate)，5s 判定超时(计入失败率并降级)。

- 备选：`spring.cloud.circuitbreaker.resilience4j.disable-time-limiter=true` 关掉时间限制器。
  那样一个挂起的下游会一直占着网关连接，失去了熔断要解决的核心问题之一。**否决。**

`ProxyCircuitBreakerTest.shouldNotTimeOutAtResilience4jDefaultOneSecond` 是这一条的防回归用例：
打桩下游故意耗时 2 秒，配置写错就会失败。

### D9：用 `configs.default` 提供项目默认值，而不是给每个实例复制一遍

resilience4j 的 `configs` 里有一个名字是特殊的：`default`。已在 2.2.0 的字节码中确认两件事——

1. `CommonCircuitBreakerConfigurationProperties.createCircuitBreakerConfig` 在实例没写 `base-config` 时，回退到名为 `default` 的配置（反编译里是字面量 `"default"`）。所以**列了名字但没填参数**的实例会继承它。
2. `configs` 会传给 `XxxRegistry.of(Map)`，其中 `default` 项成为 Registry 的 `defaultConfig`（`AbstractRegistry.DEFAULT_CONFIG`）。所以**压根没列在 `instances` 下、由代码按名字临时创建**的实例也会继承它。

这两条合起来正好覆盖「没有配置的走默认」的两种含义，一处配置即可，不需要为每个新实例复制一份参数。

同时把 `bootDemoCircuitBreaker` / `bootDemoRateLimiter` 中与默认值相同的项删掉，只保留确实要偏离默认的部分——否则默认值形同虚设，改默认值不会影响任何实例，是最容易腐化的一种配置写法。

默认值本身必须是**对网关安全的**，这正是不能沿用库默认值的原因：

| 项 | 库默认值 | 项目默认值 | 为什么不能用库默认值 |
| --- | --- | --- | --- |
| `ratelimiter.timeout-duration` | 5s | `0` | 非零会阻塞 Netty 事件循环，见 D3 |
| `timelimiter.timeout-duration` | 1s | `5s` | 会把所有请求静默截断在 1 秒，见 D8 |
| `circuitbreaker.*` | 失败率 50%、窗口 100、冷却 60s | 与 bootDemo 同源的一套 | 窗口 100 起判太迟，冷却 60s 太长 |

- 备选：不设默认值，强制每个实例显式配置，漏配就让 D3 的校验把启动打挂。这确实能防漏配，但「加一条路由必须同时改三处配置、否则起不来」的心智负担太重，且与本次需求相反。**否决。**

## 目标配置形态

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: proxy-boot-demo
              uri: lb://BootDemo
              predicates:
                - Path=/proxy/**
              filters:
                - Resilience4jRateLimiter=bootDemoRateLimiter
                - name: CircuitBreaker
                  args:
                    name: bootDemoCircuitBreaker
                    fallbackUri: forward:/fallback/boot-demo
                    statusCodes: 500,502,503,504
                - StripPrefix=1
                - PreserveHostHeader

resilience4j:
  circuitbreaker:
    configs:
      # 名字必须叫 default：没写 base-config 的实例、以及压根没列出来的实例都继承它（D9）
      default:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 20
        minimum-number-of-calls: 10
        failure-rate-threshold: 50
        slow-call-duration-threshold: 3s
        slow-call-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true
    instances:
      # 目前与默认值完全一致，列在这里是为了让实例名在配置里可见、便于日后单独调参
      bootDemoCircuitBreaker: {}
  timelimiter:
    configs:
      default:
        timeout-duration: 5s        # 不设默认就会落到库默认的 1 秒，见 D8
        cancel-running-future: false
    instances:
      bootDemoCircuitBreaker: {}    # 实例名必须与 CircuitBreaker 过滤器的 name 一致
  ratelimiter:
    configs:
      default:
        limit-for-period: 50
        limit-refresh-period: 1s
        timeout-duration: 0         # 必须为 0，见 D3；库默认是 5s，会阻塞事件循环
    instances:
      bootDemoRateLimiter: {}
```

上面的阈值是可用的起始值，不是调优结果；正式配额要按 `BootDemo` 的实际容量定。

## 新增代码

| 文件 | 职责 |
| --- | --- |
| `cn.nihility.gw.resilience.Resilience4jRateLimiterGatewayFilterFactory` | 限流过滤器工厂，含 D3 的 `timeoutDuration` 校验 |
| `cn.nihility.gw.resilience.DegradeResponseWriter` | 构造并写出统一的降级 JSON，供过滤器和 Controller 共用 |
| `cn.nihility.gw.controller.FallbackController` | `/fallback/boot-demo`，返回 `503` + 降级 JSON |

`FallbackController` 遵循项目 Controller 规范：方法级注解写完整 URL、不加类级 `@RequestMapping`、不写业务逻辑。响应类型沿用 `Mono<Map<String, Object>>`，与 `WelcomeController` 一致（项目无 `Result<T>`，见 Non-Goals）。

## Risks / Trade-offs

- **限流是单实例内存级的，不是分布式的** → resilience4j 的 `RateLimiter` 状态只存在于当前 JVM。网关部署 N 个实例时，集群实际放行量是配置值的 N 倍；实例数变化时实际配额也跟着变。缓解：把单实例配额按 `期望总配额 / 实例数` 来配，并在配置注释里写明这个换算关系。要精确的全局配额必须换 Redis + 内置 `RequestRateLimiter`，那是另一次变更。
- **阻塞事件循环** → 见 D3，靠「配置为 0 + 启动校验」双保险。
- **默认值削弱了实例名的拼写检查** → D3 的校验原本顺带能发现「实例名写错」：写错会落到库默认配置，其 `timeout-duration` 是 5 秒，启动即失败。加上 `configs.default`（`timeout-duration: 0`）之后，写错名字不再报错，而是静默套用默认配额。这是「漏配也要有保护」必然付出的代价：要么漏配报错、要么漏配兜底，二者不可兼得，本次按需求选后者。缓解：限流过滤器在装配时会打印实际生效的实例名与配额，启动日志里能核对。
- **Nacos 覆盖本地配置** → `spring.config.import` 的 Nacos 配置优先级更高，且 YAML List 不合并。Nacos 上若定义了 `...webflux.routes`，本次加的过滤器会连同整条路由一起被覆盖掉，**熔断限流静默失效**。这比上次的风险更高，因为失效时没有任何报错。缓解：上线前必须确认 Nacos 侧配置，并在验证清单里加一条「确认过滤器实际生效」。
- **`statusCodes` 与下游语义耦合** → 若 `BootDemo` 用 5xx 表达某些业务结果，会被误判为失败而触发熔断。本次按 spec 的口径实现，如与实际不符需回头调整该列表。
- **熔断打开时调用方感知为 503** → 与「下游无可用实例」的 503 状态码相同，靠响应体里的原因字段区分。
- **降级响应的写出时机** → 在过滤器里直接写响应，必须确保此前没有其他过滤器已经提交响应；限流过滤器排在最前，此条件成立。

## Migration Plan

纯新增，无数据迁移。回滚：从路由的 `filters` 中删除 `Resilience4jRateLimiter` 与 `CircuitBreaker` 两项并重启，其余代码不生效即为无害。

## Open Questions

- 具体的 QPS 配额与熔断阈值需要按 `BootDemo` 的实际容量压测后确定。这不改变方案结构和任务拆分，可在实现后按需调整配置值。
