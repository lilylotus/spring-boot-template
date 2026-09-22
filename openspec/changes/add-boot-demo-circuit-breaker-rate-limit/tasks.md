# Tasks

## 1. 依赖与配置骨架

- [x] 1.1 把 `build.gradle` 中 `spring-cloud-starter-circuitbreaker-reactor-resilience4j` 的声明版本从 `5.0.2` 改为与 BOM 实际解析一致的写法（去掉显式版本、交给 `spring-cloud-dependencies` 管理），并补一行注释说明 BOM 会覆盖显式版本；验证：`./gradlew dependencies --configuration runtimeClasspath` 中 `spring-cloud-circuitbreaker-resilience4j` 仍解析为 `3.3.3`，且不再出现 `5.0.2 -> 3.3.3` 的降级箭头
- [x] 1.2 在 `application.yaml` 新增 `resilience4j.circuitbreaker.instances.bootDemoCircuitBreaker` 与 `resilience4j.ratelimiter.instances.bootDemoRateLimiter` 两段配置，值按 design.md「目标配置形态」，并在 `timeout-duration: 0` 处加注释说明不能改（阻塞事件循环）、在 `limit-for-period` 处注明「单实例配额 = 期望总配额 / 网关实例数」；验证：启动后上下文中存在 `CircuitBreakerRegistry` 与 `RateLimiterRegistry` bean，且能按名字取到这两个实例

## 2. 降级响应

- [x] 2.1 新增 `cn.nihility.gw.resilience.DegradeResponseWriter`，提供构造统一降级 JSON（原因标识、提示信息、traceId）和把它写入 `ServerHttpResponse` 的方法；traceId 优先从 exchange 的请求头取（`TraceFilter` 排在 `HIGHEST_PRECEDENCE`，到这里时头已写好，比依赖 Reactor 上下文传播还原 MDC 可靠），退化到 `TraceContext`，都取不到时留空而不是抛异常；验证：单元测试覆盖「有 traceId」和「无 traceId」两种情况下的 JSON 结构
- [x] 2.2 新增 `cn.nihility.gw.controller.FallbackController`，`@GetMapping`/`@RequestMapping` 写完整路径 `/fallback/boot-demo`，返回 `503` + 复用 2.1 的 JSON 结构，不加类级 `@RequestMapping`、不写业务逻辑；验证：`WebTestClient` 直接请求 `/fallback/boot-demo` 返回 503、`Content-Type` 为 `application/json`、响应体含原因字段
- [x] 2.3 确认 `/fallback/**` 不会被 `/proxy/**` 路由匹配到（不构成转发回环）；验证：请求 `/fallback/boot-demo` 时 `BootDemo` 侧无访问记录，网关日志中无该路径的路由匹配

## 3. 限流过滤器

- [x] 3.1 新增 `cn.nihility.gw.resilience.Resilience4jRateLimiterGatewayFilterFactory`，继承 `AbstractGatewayFilterFactory`，配置参数为 resilience4j 的 RateLimiter 实例名（支持 `Resilience4jRateLimiter=bootDemoRateLimiter` 简写形式）；放行时走 `chain.filter(exchange)`，拒绝时用 2.1 的 writer 写 `429`；验证：单元测试用配额为 1 的 `RateLimiter` 断言第 1 个请求放行、第 2 个返回 429
- [x] 3.2 在过滤器中加入 `timeoutDuration` 必须为零的校验，不为零时以明确的错误信息快速失败（说明会阻塞 Netty 事件循环）；验证：单元测试配一个 `timeout-duration` 非零的实例，断言抛出异常且异常信息包含该原因
- [x] 3.3 确认限流拒绝路径上不会阻塞事件循环：拒绝时不调用任何阻塞 API；验证：单元测试在拒绝场景下断言执行线程未发生阻塞等待（或以代码审查 + `acquirePermission` 调用点检查替代，并在任务记录中说明）

## 4. 路由接线

- [x] 4.1 在 `application.yaml` 的 `proxy-boot-demo` 路由 `filters` 中按 design.md D4 的顺序加入 `Resilience4jRateLimiter` 与 `CircuitBreaker`（含 `name`、`fallbackUri=forward:/fallback/boot-demo`、`statusCodes`），放在 `StripPrefix`/`PreserveHostHeader` 之前；验证：启动无 `Unable to find GatewayFilterFactory` 之类报错
- [x] 4.2 扩展 `ProxyRouteConfigTest`（或新增同级测试），断言路由过滤器现在是 4 个且顺序为 `Resilience4jRateLimiter` → `CircuitBreaker` → `StripPrefix` → `PreserveHostHeader`，并断言 `CircuitBreaker` 的 `fallbackUri` 与 `statusCodes` 参数绑定正确；验证：`./gradlew test --tests "cn.nihility.gw.route.ProxyRouteConfigTest"` 通过

## 5. 集成验证

- [x] 5.1 新增集成测试：用一个本地打桩的下游（参考 `FeignTraceTest` 预占端口的做法）替代 `BootDemo`，让路由指向它，验证限流场景 —— 配额内正常转发、超配额返回 429 且桩服务未收到请求、下一周期恢复；验证：`./gradlew test --tests "*RateLimit*"` 通过
- [x] 5.2 集成测试验证熔断场景：桩服务持续返回 5xx 直至熔断打开，断言随后请求返回 503 且桩服务不再收到请求；冷却后桩服务恢复正常，断言熔断关闭、转发恢复；验证：对应测试通过
- [x] 5.3 集成测试验证降级响应：熔断 503 与限流 429 的响应体结构一致、`Content-Type` 为 `application/json`、traceId 与请求头 `X-Trace-Id` 一致且不含堆栈或内部类名；验证：对应测试通过
- [x] 5.4 验证下游 4xx 不触发熔断：桩服务持续返回 404，断言熔断保持关闭、404 原样透传；验证：对应测试通过
- [x] 5.5 跑全量测试确认未影响既有路由、链路追踪与 Feign 用例；验证：`./gradlew test` 全绿

## 7. 默认配置（本组为新增，需在原有 1-6 组之后执行）

- [x] 7.1 把 `application.yaml` 中 `resilience4j` 三个模块的参数从 `instances.<名字>` 上移到 `configs.default`，实例位置只保留空对象占位（`bootDemoCircuitBreaker: {}` / `bootDemoRateLimiter: {}`），按 design.md D9 的「目标配置形态」；验证：`./gradlew test` 中既有的熔断/限流集成用例全部仍然通过，说明 bootDemo 两个实例继承默认值后行为不变
- [x] 7.2 新增测试断言「列了名字但没填参数」的实例确实继承了默认值：注入 `RateLimiterRegistry` 与 `CircuitBreakerRegistry`，断言 `bootDemoRateLimiter` 的 `limitForPeriod` / `timeoutDuration` 与 `bootDemoCircuitBreaker` 的失败率阈值等于 `configs.default` 中的取值；验证：对应测试通过
- [x] 7.3 新增测试断言「压根没列出来」的实例也继承默认值：用一个配置里不存在的名字向两个 Registry 取实例，断言拿到的配置等于 `configs.default` 而不是库默认值（限流 `timeoutDuration` 为 0 而非 5s，超时为 5s 而非 1s）；验证：对应测试通过
- [x] 7.4 新增集成测试：给一条引用了未配置实例名的临时路由发请求，断言它仍受限流保护（超配额返回 429）且下游未收到被拦下的请求；验证：对应测试通过
- [x] 7.5 复核 `Resilience4jRateLimiterGatewayFilterFactory` 的 `timeoutDuration` 校验：加上默认值后它不再能兜住「实例名拼错」（见 design.md Risks），确认装配日志会打印实际生效的实例名与配额，让拼错在启动日志里可见；验证：启动日志中能看到 `Resilience4jRateLimiter [bootDemoRateLimiter] 已装配` 及其配额
- [x] 7.6 更新 `CLAUDE.md` 熔断与限流小节：说明 `configs.default` 的两重作用（补全未填参数的实例、兜底未列出的实例），以及「默认值必须对网关安全」这条（限流 0、超时 5s 的由来），并补上默认值削弱拼写检查这个代价；验证：描述与 `application.yaml` 实际内容一致

## 6. 文档与上线

- [x] 6.1 在 `CLAUDE.md` 架构小节补充熔断限流说明：过滤器顺序及其理由、`timeout-duration` 必须为 0 的坑、限流是单实例内存级（集群实际放行量 = 配置值 × 实例数）、声明版本被 BOM 降级到 3.3.3；验证：`git diff CLAUDE.md` 的描述与 `build.gradle`、`application.yaml` 实际内容一致
- [ ] 6.2 上线前确认 Nacos 侧配置：若 `CloudGateway.yml` / `CloudGateway-prod.yml` 定义了 `spring.cloud.gateway.server.webflux.routes`，必须把带熔断限流过滤器的整条路由同步过去，否则本地配置被整体覆盖后熔断限流会**静默失效**；验证：线上实际请求 `/proxy/**` 触发限流能拿到 429
- [ ] 6.3 按 `BootDemo` 实际容量确定正式的 QPS 配额与熔断阈值（design.md Open Questions）；验证：记录压测结论与最终配置值
