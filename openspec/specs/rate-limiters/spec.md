# rate-limiters Specification

## Purpose
在 `org.example.simple.rate` 包中提供固定窗口计数、滑动窗口（日志法）、滑动窗口计数、漏桶、令牌桶五种限流算法，统一实现 `RateLimiter` 接口，线程安全且时间源可注入。

## Requirements

### Requirement: 统一限流接口
系统 SHALL 提供 `RateLimiter` 接口，包含非阻塞的 `tryAcquire()` 与 `tryAcquire(int permits)`，五种实现对外行为约定一致。

#### Scenario: 非法许可数
- **WHEN** 以 `permits <= 0` 调用 `tryAcquire`
- **THEN** 抛出 `IllegalArgumentException`

#### Scenario: 许可数超过上限
- **WHEN** 请求的 `permits` 超过窗口上限或桶容量
- **THEN** 返回 `false`，且限流器状态不变

#### Scenario: 获取失败无副作用
- **WHEN** 因许可不足返回 `false`
- **THEN** 不计数、不注水、不扣减令牌，随后的较小请求按原状态判断

#### Scenario: 非法构造参数
- **WHEN** 上限、容量、子窗口数不是正数，速率不是有限正数，或窗口为 `null`、非正
- **THEN** 抛出 `IllegalArgumentException`（窗口为 `null` 时抛出 `NullPointerException`）

### Requirement: 固定窗口计数
系统 SHALL 提供 `FixedWindowRateLimiter`，在以创建时刻对齐的每个固定窗口内最多放行 `limit` 个许可。

#### Scenario: 窗口内达到上限
- **WHEN** 同一窗口内已放行 `limit` 个许可后再次请求
- **THEN** 返回 `false`，进入下一个窗口后恢复放行

#### Scenario: 边界突发
- **WHEN** 在窗口结束前与下一窗口开始后各请求 `limit` 个许可
- **THEN** 两次均放行，短时间内共放行 `2·limit` 个许可

### Requirement: 滑动窗口（日志法）
系统 SHALL 提供 `SlidingWindowLogRateLimiter`，保证任意长度为 `window` 的左开右闭时间区间内放行的许可不超过 `limit`。

#### Scenario: 无边界突发
- **WHEN** 在固定窗口边界突发场景下请求
- **THEN** 跨边界的短时间内最多放行 `limit` 个许可

#### Scenario: 时间戳过期
- **WHEN** 最早放行的许可距今恰好达到 `window`
- **THEN** 该许可不再计入，可放行新的许可

### Requirement: 滑动窗口计数
系统 SHALL 提供 `SlidingWindowCounterRateLimiter`，将窗口划分为 `buckets` 个等长子窗口，按仍在窗口内的子窗口计数之和判断是否放行。

#### Scenario: 子窗口过期
- **WHEN** 时间推进超过某子窗口所在的整个窗口
- **THEN** 该子窗口计数不再计入总和

#### Scenario: 窗口不能整除
- **WHEN** 窗口纳秒数不能被 `buckets` 整除
- **THEN** 抛出 `IllegalArgumentException`

#### Scenario: 单个子窗口
- **WHEN** `buckets = 1`
- **THEN** 行为与同参数的固定窗口一致

### Requirement: 漏桶与令牌桶
系统 SHALL 提供水位法的 `LeakyBucketRateLimiter`（初始空桶、按恒定速率漏水）与 `TokenBucketRateLimiter`（初始满桶、按恒定速率补充令牌），二者瞬时突发量不超过容量，长期放行速率不超过配置速率。

#### Scenario: 初始突发
- **WHEN** 新建限流器后立即连续请求
- **THEN** 恰好放行 `capacity` 个许可

#### Scenario: 恒定速率恢复
- **WHEN** 桶耗尽后时间推进 `n / rate` 秒
- **THEN** 恰好可再放行 `n` 个许可（不超过容量）

#### Scenario: 小数速率
- **WHEN** 速率为 0.5/秒且桶耗尽后时间推进 2 秒
- **THEN** 放行 1 个许可

### Requirement: 线程安全
系统 SHALL 保证五种实现在多线程并发调用时放行总数不超过算法允许的上限。

#### Scenario: 并发争抢
- **WHEN** 时间固定，多个线程同时大量调用 `tryAcquire()`
- **THEN** 放行总数恰好等于上限或容量
