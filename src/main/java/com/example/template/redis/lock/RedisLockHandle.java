package com.example.template.redis.lock;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次成功获取对应的不可变锁身份句柄。
 * <p>
 * 令牌仅供同包锁服务执行 Redis 比较脚本使用，公开 API 不暴露令牌值，调用方可据此获知所有权是否已丢失。
 */
public final class RedisLockHandle {

    private final String lockKey;
    private final String token;
    private final Duration leaseTime;
    private final AtomicReference<LockState> state = new AtomicReference<>(LockState.ACTIVE);
    private volatile ScheduledFuture<?> renewalTask;

    RedisLockHandle(String lockKey, String token, Duration leaseTime) {
        this.lockKey = lockKey;
        this.token = token;
        this.leaseTime = leaseTime;
    }

    /**
     * 判断服务是否已发现当前句柄不再拥有锁。
     *
     * @return 自动续期或释放检查发现所有权丢失时为 {@code true}
     */
    public boolean isOwnershipLost() {
        return state.get() == LockState.LOST;
    }

    /**
     * 判断该句柄是否已经结束释放流程。
     *
     * @return 已完成释放流程时为 {@code true}
     */
    public boolean isReleased() {
        return state.get() == LockState.RELEASED;
    }

    String lockKey() {
        return lockKey;
    }

    String token() {
        return token;
    }

    Duration leaseTime() {
        return leaseTime;
    }

    AtomicReference<LockState> state() {
        return state;
    }

    void setRenewalTask(ScheduledFuture<?> renewalTask) {
        this.renewalTask = renewalTask;
    }

    void cancelRenewal() {
        ScheduledFuture<?> task = renewalTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 内部锁生命周期状态，防止续期任务与释放流程相互覆盖。 */
    enum LockState {
        ACTIVE,
        LOST,
        RELEASING,
        RELEASED
    }
}
