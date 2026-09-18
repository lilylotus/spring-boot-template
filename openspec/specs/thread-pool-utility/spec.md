# thread-pool-utility Specification

## Purpose

为调用方提供简单一致的多线程任务执行能力，由工具内部维护四个工作线程，并通过执行与提交 API 隐藏线程池的创建、配置和生命周期细节。

## Requirements

### Requirement: 内部四线程执行器
工具 SHALL 在内部维护固定 4 个工作线程的共享执行器，并 MUST NOT 向调用方返回或暴露该执行器。工具 SHALL 使用可识别名称的守护线程执行任务。

#### Scenario: 最多四个任务并行执行
- **WHEN** 调用方提交至少四个可同时阻塞的任务
- **THEN** 执行器能够同时开始四个任务，队列容量允许时额外任务等待工作线程可用

#### Scenario: 工作线程可识别且不阻止退出
- **WHEN** 任务读取当前线程属性
- **THEN** 线程名称包含工具专用前缀且线程为守护线程

### Requirement: 执行无返回值任务
工具 SHALL 接受非空 `Runnable` 并异步执行，且 MUST 拒绝空任务。

#### Scenario: 直接执行 Runnable
- **WHEN** 调用方执行一个合法的 `Runnable` 任务
- **THEN** 工具在内部执行器中异步运行该任务

#### Scenario: 拒绝空 Runnable
- **WHEN** 调用方传入空 `Runnable`
- **THEN** 工具抛出参数异常且不提交任务

### Requirement: 提交任务并返回 Future
工具 SHALL 支持提交非空 `Runnable` 和 `Callable<T>`，并 SHALL 返回对应 `Future`，使调用方能够等待任务完成、获取返回值或观察任务异常。

#### Scenario: 提交 Runnable
- **WHEN** 调用方提交一个合法的 `Runnable` 任务
- **THEN** 返回的 `Future` 在任务完成后进入完成状态

#### Scenario: 提交 Callable
- **WHEN** 调用方提交一个返回结果的 `Callable<T>` 任务
- **THEN** 返回的 `Future<T>` 提供该任务的结果

#### Scenario: 任务异常可观察
- **WHEN** 已提交任务在执行期间抛出异常
- **THEN** 调用方通过 `Future` 获取结果时能够观察到该异常

#### Scenario: 拒绝空 Callable
- **WHEN** 调用方传入空 `Callable`
- **THEN** 工具抛出参数异常且不提交任务

### Requirement: 有界队列与可诊断拒绝
工具 SHALL 将等待执行的任务数量限制为 1024。当 4 个工作线程均在执行任务且 1024 个任务正在排队时，工具 MUST 拒绝新任务并抛出 `RejectedExecutionException`。异常消息 MUST 使用中文说明任务因线程池饱和被拒绝，并 SHALL 包含活动线程数、当前排队任务数和最大队列容量。

#### Scenario: 容量以内任务进入等待队列
- **WHEN** 4 个工作线程均被占用且排队任务数少于 1024
- **THEN** 新任务被接受并进入等待队列

#### Scenario: 超过任务容量时拒绝
- **WHEN** 4 个工作线程均被占用且等待队列已有 1024 个任务
- **THEN** 下一个任务被拒绝并抛出 `RejectedExecutionException`

#### Scenario: 拒绝错误说明原因
- **WHEN** 任务因执行线程和等待队列全部占满而被拒绝
- **THEN** 异常消息使用中文说明线程池任务已满，并包含活动线程数、排队任务数和队列容量
