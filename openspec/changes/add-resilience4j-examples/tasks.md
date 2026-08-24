## 1. 配置

- [x] 1.1 在 `application.yml` 新增 `resilience4j.circuitbreaker.instances.demo` 配置段
      （滑动窗口大小、失败率阈值、half-open 探测次数、open 状态等待时间等），附中文注释说明每个
      参数的作用。
- [x] 1.2 在 `application.yml` 新增 `resilience4j.retry.instances.demo` 配置段（最大重试次数、
      重试间隔），附中文注释。
- [x] 1.3 在 `application.yml` 新增 `resilience4j.ratelimiter.instances.demo` 配置段（单位时间
      许可数、刷新周期、等待超时），附中文注释。
- [x] 1.4 在 `application.yml` 新增 `resilience4j.bulkhead.instances.demo` 配置段（最大并发调用数、
      等待时长），附中文注释。

## 2. 断路器示例

- [x] 2.1 新建 `com.example.template.resilience4j.CircuitBreakerDemoService`，实现一个用
      `@CircuitBreaker(name = "demo", fallbackMethod = "xxxFallback")` 标注的方法，内部用计数器/
      随机数模拟下游间歇性失败；实现对应 fallback 方法返回明确标识"熔断降级"的结果。类和方法均按
      项目注释规范添加中文说明。

## 3. 重试示例

- [x] 3.1 新建 `com.example.template.resilience4j.RetryDemoService`，实现一个用
      `@Retry(name = "demo", fallbackMethod = "xxxFallback")` 标注的方法：用调用计数器模拟"前几次
      失败、随后成功"，用于演示重试后成功的路径；再提供一个始终失败的方法，用于演示重试耗尽后走
      fallback 的路径。类和方法均添加中文注释。

## 4. 限流器示例

- [x] 4.1 新建 `com.example.template.resilience4j.RateLimiterDemoService`，实现一个用
      `@RateLimiter(name = "demo", fallbackMethod = "xxxFallback")` 标注的方法（可包含少量耗时
      操作便于配合并发请求观察限流效果）；实现对应 fallback 方法返回明确标识"被限流"的结果。类和
      方法均添加中文注释。

## 5. 隔板示例

- [x] 5.1 新建 `com.example.template.resilience4j.BulkheadDemoService`，实现一个用
      `@Bulkhead(name = "demo", fallbackMethod = "xxxFallback")` 标注的方法（内部 `Thread.sleep`
      模拟耗时操作，便于配合并发请求观察隔板拒绝效果）；实现对应 fallback 方法返回明确标识"被隔板
      拒绝"的结果。类和方法均添加中文注释。

## 6. Controller 与联调

- [x] 6.1 新建 `com.example.template.resilience4j.Resilience4jDemoController`，暴露
      `GET /api/resilience4j/circuit-breaker`、`/retry`、`/rate-limiter`、`/bulkhead` 四个端点分别
      调用对应 Service；类和方法添加中文注释，并在类注释里说明限流器/隔板端点需要并发调用才能观察到
      拒绝效果。另外新增 `/retry/always-fail` 端点，覆盖 spec 中"重试耗尽走 fallback"这条路径
      （design.md 中提到要保留一个"永远失败"的方法/端点，原任务描述未列出该路径独立的端点）。
- [x] 6.2 本地启动应用（`./gradlew bootRun`），依次验证端点：断路器端点连续调用 10 次，观察到
      "正常调用" → 失败次数累计到阈值后 "CircuitBreaker 'demo' is OPEN" 熔断降级；重试端点验证
      "重试后成功"（第 3 次调用成功）与 `/retry/always-fail` 验证"重试耗尽走 fallback"；限流器/
      隔板端点各并发发起 5 个请求，均观察到 2 个放行、3 个被拒绝（与配置的 `limit-for-period: 2` /
      `max-concurrent-calls: 2` 完全吻合）。过程中发现 resilience4j 的 `@Aspect` 切面必须依赖
      AspectJ 注解切点解析能力才能生效，仅有 `spring-aop` 是不够的，因此额外引入了
      `spring-boot-starter-aop`（已同步更新 build.gradle 及本文档 1.x 之外的依赖列表）。
- [x] 6.3 执行 `./gradlew build` 确认编译、打包通过；`test` 任务有 4 个既有测试失败
      （`MybatisPlusTest`/`MybatisTest` 因本地 MySQL 缺少对应表结构报 `BadSqlGrammarException`，
      `SpringContextHolderTest` 断言失败），均与本次改动的 `resilience4j` 包、`build.gradle`、
      `application.yml` 无关（通过 `git stash` 掉本次改动后仍然是同样的失败集合），本次未修复，
      不在本 change 范围内。
