## Why

限流是保护服务、平滑流量的基础手段。项目中只有 RPC 服务端内部的一个私有令牌桶，无法复用，也没有其他常见限流算法的实现可供对比与选型。需要一组接口一致、线程安全、可注入时间源以便确定性测试，并在 Javadoc 中写清原理、精度、突发特性与适用场景的限流算法实现。

## What Changes

- 新增包 `org.example.simple.rate`，只使用 JDK 内置 API，不引入新依赖。
- 新增统一接口 `RateLimiter`：`tryAcquire()`、`tryAcquire(int permits)`，非阻塞，立即返回是否放行。
- 新增 5 种实现：
  - `FixedWindowRateLimiter`：固定窗口计数，窗口内计数超过上限即拒绝，窗口边界计数清零。
  - `SlidingWindowLogRateLimiter`：滑动窗口（日志法），记录每个许可的放行时间戳，统计最近一个窗口内的精确数量。
  - `SlidingWindowCounterRateLimiter`：滑动窗口计数，将窗口划分为若干子窗口（环形数组），累加仍在窗口内的子窗口计数。
  - `LeakyBucketRateLimiter`：漏桶（水位法），请求注水，桶以恒定速率漏水，水位超过容量即拒绝。
  - `TokenBucketRateLimiter`：令牌桶，按恒定速率补充令牌（惰性计算），桶容量决定允许的突发量。
- 所有实现线程安全；时间基于单调时钟 `System.nanoTime()`，并提供包级构造器注入时间源，供测试精确控制时间。
- 每个类提供中文 Javadoc：算法原理、精度与边界问题、时间与空间复杂度、突发特性、适用场景。

## Capabilities

### New Capabilities

- `rate-limiters`：定义 `RateLimiter` 接口语义、参数校验约定，以及五种限流算法的放行规则与线程安全要求。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/rate/` 下 6 个源文件（接口 + 5 个实现），及可能的包级时间源辅助代码。
- 新增 `src/test/java/org/example/simple/rate/` 下对应测试类。
- 新增 `docs/限流算法使用说明.md`，并在 `README.md` 增加入口链接。
- 不新增 Gradle 依赖；不修改既有代码（RPC 服务端内部令牌桶保持不变）。
