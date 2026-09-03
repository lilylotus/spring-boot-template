# Redis 分布式锁公共服务

`DistributedLockService` 为同一 Redis 数据库、锁前缀和业务键提供短时跨实例互斥。业务服务通过构造器注入该接口，不需要也不应直接操作 Redis 锁键。

```java
private final DistributedLockService distributedLockService;

public OrderService(DistributedLockService distributedLockService) {
    this.distributedLockService = distributedLockService;
}
```

## 同步回调

推荐使用回调入口。只有成功获取锁才会执行一次回调，结束后服务会安全释放锁；竞争超时抛出 `LockAcquisitionException`，续期失败或释放发现令牌不匹配抛出 `LockOwnershipLostException`。

```java
String result = distributedLockService.executeWithLock(
    "order:submit:" + orderId,
    () -> transactionTemplate.execute(status -> submitOrder(orderId)));
```

事务必须在回调内部开始并完成提交，锁在回调返回后释放。不要在已经开启的外层事务中获取锁后立刻释放，因为提交可能发生在锁释放之后。

## 手动释放

确有需要时可使用 `tryLock`。必须在 `finally` 中调用 `unlock`，并在长流程的关键写入前检查 `handle.isOwnershipLost()`；句柄不暴露令牌，重复释放或旧句柄都不会删除新持有者的锁。

```java
Optional<RedisLockHandle> optional = distributedLockService.tryLock(
    "inventory:deduct:" + skuId);
RedisLockHandle handle = optional.orElseThrow(() -> new LockAcquisitionException("库存正在处理"));
try {
    if (handle.isOwnershipLost()) {
        throw new LockOwnershipLostException("库存锁已失效");
    }
    deductInventory(skuId);
} finally {
    distributedLockService.unlock(handle);
}
```

## 自动续期与配置

成功获取后，服务默认每 10 秒执行一次“令牌匹配才续期”的 Redis 脚本。常用调用的默认等待时间为 3 秒、默认租期为 30 秒；租期至少为一秒，且不支持可重入。锁键前缀默认是 `lock:${spring.application.name}:`。

```yaml
template:
  redis-lock:
    key-prefix: lock:order-service:
    wait-time: 3s
    lease-time: 30s
    renewal-interval: 10s
```

三项默认时长都可按环境统一覆盖。显式 `renewal-interval` 必须为正、使用毫秒精度且小于锁租期；需要特殊等待或租期时可使用带 `waitTime`、`leaseTime` 的高级重载。续期 Redis 故障、令牌不匹配、锁键到期或调度器关闭都会停止续期并将句柄标记为所有权丢失；正在执行的回调不能被强制中断，但结束时会失败反馈。

自动续期不是强一致锁保证。进程暂停、Redis 主从切换、内存驱逐和网络故障仍可能产生并行执行。业务必须继续依赖幂等键、数据库唯一约束、条件更新或隔离令牌等保护，不能据此宣称严格一次执行。

## 测试

默认单元测试不连接 Redis。真实 Redis 集成测试仅应在独立的测试实例和专用前缀上显式启用，禁止对共享数据库执行 `FLUSHDB`、`FLUSHALL` 或全库清理。

PowerShell 示例（密码只在当前进程环境变量中传递，不写入仓库）：

```powershell
$env:REDIS_LOCK_INTEGRATION_ENABLED = "true"
$env:REDIS_LOCK_TEST_HOST = "127.0.0.1"
$env:REDIS_LOCK_TEST_PORT = "6379"
$env:REDIS_LOCK_TEST_PASSWORD = "独立测试实例密码"
.\gradlew.bat test --tests "com.example.template.redis.lock.RedisDistributedLockIntegrationTest"
```

该测试使用每次运行生成的 `lock:integration:<UUID>:` 前缀，只删除该前缀下的精确测试锁键。验证内容包括同键竞争、不同键独立、主动释放接手、自动续期，以及旧句柄不得删除新持有者的锁。
