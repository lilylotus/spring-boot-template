# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/rate-limiters/spec.md` 的明确确认，尤其是："滑动窗口"按滑动日志实现、"滑动窗口计数"按子窗口环形数组实现；漏桶只实现水位计量形态（不实现排队整形）；接口仅提供非阻塞 `tryAcquire`；`permits` 超过上限返回 `false` 而非抛异常；使用 `synchronized` 保证线程安全；包名为 `org.example.simple.rate`。

## 2. 实现

- [x] 2.1 新增 `RateLimiter` 接口与包级参数校验、时间读取辅助代码（默认 `System.nanoTime()`，包级构造器可注入 `LongSupplier`，时间回退按 0 处理）。
- [x] 2.2 实现 `FixedWindowRateLimiter`（窗口对齐创建时刻、空闲后整除跳窗）。
- [x] 2.3 实现 `SlidingWindowLogRateLimiter`（放行记录队列，每条含时间戳与许可数，维护窗口内总数；左开右闭窗口）。
- [x] 2.4 实现 `SlidingWindowCounterRateLimiter`（环形子窗口、整除校验、默认 10 个子窗口）。
- [x] 2.5 实现 `LeakyBucketRateLimiter`（水位法、惰性漏水、浮点容差）。
- [x] 2.6 实现 `TokenBucketRateLimiter`（初始满桶、惰性补充、浮点容差）。
- [x] 2.7 为所有公共类与方法编写中文 Javadoc：原理、误差与边界问题、复杂度、突发特性、适用场景。

## 3. 测试

- [x] 3.1 参数化契约测试覆盖五种实现：非法构造参数、`permits <= 0` 抛 `IllegalArgumentException`、`permits` 超过上限返回 `false`、失败不产生副作用。
- [x] 3.2 使用假时钟逐一验证各算法放行规则：窗口内达到上限后拒绝、窗口或时间推进后恢复、长时间空闲后行为正确。
- [x] 3.3 固定窗口边界突发：边界两侧短时间内放行 `2·limit`；滑动日志同场景只放行 `limit`。
- [x] 3.4 滑动窗口计数：子窗口过期后计数移出；`buckets = 1` 行为等同固定窗口；`window` 不能被 `buckets` 整除时抛异常。
- [x] 3.5 漏桶与令牌桶：初始突发量等于容量；长期速率不超过配置速率；小数速率（如 0.5/秒）在浮点累加下按时放行。
- [x] 3.6 并发测试：固定假时钟下多线程同时 `tryAcquire`，放行总数恰好等于上限。
- [x] 3.7 真实时钟冒烟测试：默认构造器可用，短时间内放行数不超过上限（不依赖精确时序）。

## 4. 文档与验证

- [x] 4.1 新增 `docs/限流算法使用说明.md`：算法对比表、选型建议、使用示例、Non-Goal；在 `README.md` 增加入口链接。
- [x] 4.2 运行 `gradlew.bat test --tests "org.example.simple.rate.*"` 与 `gradlew.bat build`，确认测试通过、无新增编译与 Javadoc 警告。
- [x] 4.3 运行 `openspec validate add-rate-limiters --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.rate.*"`：3 个测试类共 64 个用例（含参数化展开）全部通过，耗时约 3 秒。
- 对 `rate` 包单独执行 `javadoc -Xmaxwarns 1000`：0 个警告；编译无新增警告；源码与测试无超过 120 字符的行、无 tab、无 CRLF。
- 变异检查（检查后均已恢复源码）：
  - 滑动日志过期判断由 `>=` 改为 `>`：`slidingLogExpiresGrantsExactlyAfterWindow` 失败。
  - 令牌桶去掉浮点容差：`leakyBucketIsEquivalentToFullTokenBucket` 失败。
- `gradlew.bat build`（全量）：本变更测试全部通过；其余 10 个失败与本变更无关，未作改动：`GlobalHttpClientUtilsTest` 5 个与 `HttpClientsTest` 2 个依赖本地 HTTP 服务，`ExcelUtilsTest` 2 个缺少本地测试文件，`RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall` 1 个偶发失败（单独重跑通过）。
- 实施中设计调整（已先同步 `design.md`）：
  - 用户将包名由 `ratio` 更正为 `rate`。
  - 滑动日志由"每个许可一条时间戳"改为"每次放行一条记录（时间戳 + 许可数）"并维护窗口内总数，精度不变，一次获取多个许可时不放大开销。
  - 新增包级工具类 `RateLimiters`（参数校验、默认时间源、浮点容差、防时间回退）；测试额外覆盖纳秒读数溢出回绕，以及水位法漏桶与令牌桶判断结果一致。
