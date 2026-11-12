## Context

用户要求在 `org.example.simple.rate` 包中实现固定窗口计数、滑动窗口、滑动窗口计数、漏桶、令牌桶五种限流算法（用户已将包名由最初的 `ratio` 更正为 `rate`）。仓库已有 `RpcServer` 内部的私有令牌桶，与本变更互不影响。

"滑动窗口"与"滑动窗口计数"在业界有多种叫法，本设计约定：

- **滑动窗口** = 滑动日志（sliding log）：保存每个许可的时间戳，结果精确。
- **滑动窗口计数** = 子窗口计数（如 Sentinel 的 `LeapArray`）：用固定数量的子窗口计数近似滑动窗口，内存固定。

## Goals / Non-Goals

**Goals:**
- 五种算法共享 `RateLimiter` 接口，可相互替换对比。
- 线程安全，多线程并发调用时放行总数不超过算法允许的上限。
- 时间可注入，测试不依赖 `Thread.sleep`，结果确定。
- 参数校验与边界行为明确，中文 Javadoc 写清原理、误差与适用场景。

**Non-Goals:**
- 不提供阻塞式 `acquire()`（等待直到获得许可）与带超时的获取；调用方可基于 `tryAcquire` 自行重试。
- 不实现漏桶的"排队整形"形态（请求进入队列、以恒定间隔出队执行），只实现"水位计量"形态。
- 不提供分布式限流（Redis 等）、按键（用户、IP）分组限流、运行时动态调整参数、预热（warm-up）。
- 不修改 `RpcServer` 内部令牌桶，也不让其改用本包实现。

## Decisions

### 接口

```java
public interface RateLimiter {
    default boolean tryAcquire() { return tryAcquire(1); }
    boolean tryAcquire(int permits);   // 非阻塞，成功则扣减许可并返回 true
}
```

- `permits <= 0` 抛出 `IllegalArgumentException`。
- `permits` 超过单个窗口上限或桶容量时永远无法满足，直接返回 `false`（不抛异常），与"许可不足"语义一致。
- 获取失败不产生副作用（不计数、不注水、不扣令牌）。

### 时间源

- 默认使用 `System.nanoTime()`（单调，不受系统时间调整影响）。
- 每个实现提供包级构造器，额外接收 `LongSupplier nanoTimeSource`，仅供同包测试注入假时钟；公共构造器不暴露时间源，保持 API 简洁。
- 时间统一用 `long` 纳秒，`Duration` 参数在构造时转换；窗口长度、速率换算出的间隔必须为正，否则抛出 `IllegalArgumentException`。

### 线程安全

每个实现用对象内部锁（`synchronized`）保护状态，所有判断与修改在同一临界区内完成。理由：各算法的状态更新都涉及多个字段（窗口起点与计数、令牌数与上次补充时间等），CAS 实现复杂且易错；限流判断本身是纳秒级操作，锁竞争开销可接受。文档说明高并发热点场景可考虑分片或无锁实现。

### 各算法

| 实现 | 构造参数 | 状态 | 放行规则 | 时间/空间 |
| --- | --- | --- | --- | --- |
| `FixedWindowRateLimiter` | `limit`、`window` | 当前窗口起点、计数 | 当前时间超过窗口终点时，窗口起点对齐到 `start + k·window` 并清零；`count + permits <= limit` 放行 | O(1) / O(1) |
| `SlidingWindowLogRateLimiter` | `limit`、`window` | 放行记录队列（每次放行一条，含时间戳与许可数）及窗口内许可总数 | 先移除时间戳 `<= now - window` 的记录并扣减总数；`total + permits <= limit` 放行并追加一条记录 | 均摊 O(1) / O(limit) |
| `SlidingWindowCounterRateLimiter` | `limit`、`window`、`buckets`（默认 10） | 长度为 `buckets` 的环形数组，每格记录子窗口起点与计数 | 子窗口长度 `window / buckets`；按当前时间定位格子，起点过期则重置；累加起点仍在 `(now - window, now]` 内的格子计数，`sum + permits <= limit` 放行 | O(buckets) / O(buckets) |
| `LeakyBucketRateLimiter` | `capacity`、`leakRatePerSecond` | 水位（`double`）、上次漏水时间 | 先按经过时间漏水 `water = max(0, water - elapsed·rate)`；`water + permits <= capacity` 放行并注水 | O(1) / O(1) |
| `TokenBucketRateLimiter` | `capacity`、`refillRatePerSecond` | 令牌数（`double`）、上次补充时间；初始为满桶 | 先惰性补充 `tokens = min(capacity, tokens + elapsed·rate)`；`tokens >= permits` 放行并扣减 | O(1) / O(1) |

补充说明：

- **固定窗口**：窗口对齐到限流器创建时刻，长时间空闲后用整除跳过多个窗口，避免循环。已知缺陷：在相邻窗口边界两侧各打满，短时间内可放行接近 `2·limit` 的请求，Javadoc 与测试都体现该现象。
- **滑动日志**：精确无边界问题。每次放行只记录一条（时间戳 + 许可数）而非每个许可一条，结果同样精确，且一次获取多个许可时不放大内存与时间开销；记录数最多为 `limit`，内存仍与上限成正比，`limit` 很大时不适用。时间戳严格大于 `now - window` 才计入，即窗口为左开右闭区间 `(now - window, now]`。
- **滑动窗口计数**：`window` 必须能被 `buckets` 整除（纳秒精度下），否则抛出 `IllegalArgumentException`，避免子窗口长度截断引入的累计误差。误差上界约为一个子窗口内的请求量，`buckets` 越大越精确、判断成本越高；`buckets = 1` 退化为固定窗口。
- **漏桶（水位法）**：初始为空桶，允许瞬时突发 `capacity` 个请求，之后长期速率不超过 `leakRatePerSecond`。水位法漏桶与"初始满桶的令牌桶"在数学上等价，Javadoc 如实说明，并指出二者的区别在于视角（限制"流入"还是"许可"）与常见扩展方向（漏桶常扩展为排队整形以获得严格匀速输出，本变更不实现）。
- **令牌桶**：初始满桶，允许空闲后突发 `capacity` 个请求；长期速率不超过 `refillRatePerSecond`。
- 浮点误差：漏桶和令牌桶的比较使用 `1e-9` 容差，避免 `0.1` 速率累加出现 `0.9999999` 导致应放行却拒绝。
- 时间回退防护：若时间源读数小于上次记录（仅可能出现在注入的假时钟），按经过时间为 0 处理。

### 参数校验

- `limit`、`capacity`、`buckets` 必须为正整数；速率必须为有限正数；`window` 不得为 `null` 且必须为正。违反时抛出 `IllegalArgumentException`（`null` 抛 `NullPointerException`）。
- 每个实现提供只读访问器（如 `limit()`、`window()`、`capacity()`），便于日志与测试。

## Risks / Trade-offs

- **`synchronized` 在极高并发下成为热点**：对单进程常规流量足够；文档提示需要更高吞吐时可按键分片多个限流器。
- **漏桶与令牌桶看起来相似**：如实说明等价性与区别，避免误导选型。
- **滑动日志内存**：`limit` 较大（如百万级）时内存不可接受，Javadoc 提示改用滑动窗口计数。
