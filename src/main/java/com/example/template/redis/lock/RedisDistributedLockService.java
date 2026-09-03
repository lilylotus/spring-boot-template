package com.example.template.redis.lock;

import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于 Redis 原子设置和 Lua 比较脚本实现的分布式锁服务。
 * <p>
 * 获取、续期和释放均以随机令牌识别持有者，自动续期失败即视为本地所有权丢失，绝不无条件修改锁键。
 */
@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final Duration MIN_LEASE_TIME = Duration.ofSeconds(1);
    private static final Duration RETRY_INTERVAL = Duration.ofMillis(50);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = script(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = script(
        "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end");

    private final StringRedisTemplate redisTemplate;
    private final TaskScheduler renewalScheduler;
    private final RedisLockProperties properties;
    private final String applicationName;

    /**
     * 注入 Redis 客户端、续期调度器及锁配置。
     *
     * @param redisTemplate Redis 字符串模板
     * @param renewalScheduler 锁续期专用调度器
     * @param properties 锁配置
     * @param applicationName 当前应用名，用于默认锁命名空间
     */
    public RedisDistributedLockService(
        StringRedisTemplate redisTemplate,
        @Qualifier("redisLockTaskScheduler") TaskScheduler renewalScheduler,
        RedisLockProperties properties,
        @Value("${spring.application.name:application}") String applicationName) {
        this.redisTemplate = redisTemplate;
        this.renewalScheduler = renewalScheduler;
        this.properties = properties;
        this.applicationName = applicationName;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RedisLockHandle> tryLock(String key) throws InterruptedException {
        return tryLock(key, properties.getWaitTime(), properties.getLeaseTime());
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RedisLockHandle> tryLock(
        String key,
        Duration waitTime,
        Duration leaseTime) throws InterruptedException {
        validateArguments(key, waitTime, leaseTime);
        checkInterrupted();

        String lockKey = buildLockKey(key);
        Duration renewalInterval = resolveRenewalInterval(leaseTime);
        long waitNanos = toNanos(waitTime, "等待时间");
        long startNanos = System.nanoTime();

        while (true) {
            RedisLockHandle handle = attemptLock(lockKey, leaseTime);
            if (handle != null) {
                // Redis 往返时间也占用等待预算；迟到成功必须归还，不能把超时后的锁交给调用方。
                if (!waitTime.isZero() && elapsedNanos(startNanos) >= waitNanos) {
                    unlock(handle);
                    return Optional.empty();
                }
                scheduleRenewal(handle, renewalInterval);
                return Optional.of(handle);
            }

            if (waitTime.isZero() || elapsedNanos(startNanos) >= waitNanos) {
                return Optional.empty();
            }
            sleepBeforeRetry(waitNanos - elapsedNanos(startNanos));
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T> T executeWithLock(String key, Supplier<T> action) throws InterruptedException {
        return executeWithLock(key, properties.getWaitTime(), properties.getLeaseTime(), action);
    }

    /** {@inheritDoc} */
    @Override
    public boolean unlock(RedisLockHandle handle) {
        Objects.requireNonNull(handle, "锁句柄不能为空");
        if (!handle.state().compareAndSet(RedisLockHandle.LockState.ACTIVE, RedisLockHandle.LockState.RELEASING)) {
            return false;
        }

        handle.cancelRenewal();
        try {
            Long result = redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(handle.lockKey()),
                handle.token());
            boolean unlocked = Long.valueOf(1L).equals(result);
            handle.state().set(unlocked ? RedisLockHandle.LockState.RELEASED : RedisLockHandle.LockState.LOST);
            return unlocked;
        } catch (RuntimeException exception) {
            handle.state().set(RedisLockHandle.LockState.LOST);
            throw exception;
        }
    }

    /** {@inheritDoc} */
    @Override
    public void executeWithLock(String key, Runnable action) throws InterruptedException {
        executeWithLock(key, properties.getWaitTime(), properties.getLeaseTime(), action);
    }

    /** {@inheritDoc} */
    @Override
    public <T> T executeWithLock(
        String key,
        Duration waitTime,
        Duration leaseTime,
        Supplier<T> action) throws InterruptedException {
        Objects.requireNonNull(action, "业务回调不能为空");
        RedisLockHandle handle = tryLock(key, waitTime, leaseTime)
            .orElseThrow(() -> new LockAcquisitionException("在等待时间内未获取到分布式锁: " + key));
        T result = null;
        Throwable businessFailure = null;
        try {
            result = action.get();
        } catch (Throwable throwable) {
            businessFailure = throwable;
            throw throwable;
        } finally {
            finishCallback(handle, businessFailure);
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public void executeWithLock(
        String key,
        Duration waitTime,
        Duration leaseTime,
        Runnable action) throws InterruptedException {
        Objects.requireNonNull(action, "业务回调不能为空");
        executeWithLock(key, waitTime, leaseTime, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 发起一次原子带租期的获取，并在成功时创建独立令牌句柄。
     */
    private RedisLockHandle attemptLock(String lockKey, Duration leaseTime) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, leaseTime);
        return Boolean.TRUE.equals(acquired) ? new RedisLockHandle(lockKey, token, leaseTime) : null;
    }

    /**
     * 注册续期任务；调度器拒绝任务意味着无法保证后续续期，不能交出正常句柄。
     */
    private void scheduleRenewal(RedisLockHandle handle, Duration interval) {
        try {
            handle.setRenewalTask(renewalScheduler.scheduleAtFixedRate(
                () -> renew(handle), interval));
        } catch (RejectedExecutionException exception) {
            handle.state().set(RedisLockHandle.LockState.LOST);
            safeUnlockAfterScheduleFailure(handle, exception);
            throw new LockOwnershipLostException("分布式锁续期调度器不可用");
        }
    }

    /**
     * 只在句柄仍处于活动状态时执行比较令牌续期，任何异常都终止本地持锁声明。
     */
    private void renew(RedisLockHandle handle) {
        if (handle.state().get() != RedisLockHandle.LockState.ACTIVE) {
            return;
        }
        try {
            Long result = redisTemplate.execute(
                RENEW_SCRIPT,
                Collections.singletonList(handle.lockKey()),
                handle.token(),
                Long.toString(handle.leaseTime().toMillis()));
            if (!Long.valueOf(1L).equals(result)) {
                markOwnershipLost(handle);
            }
        } catch (RuntimeException exception) {
            markOwnershipLost(handle);
        }
    }

    /**
     * 标记失锁并停止今后调度；不对未知状态的锁重新设置值，避免覆盖其他持有者。
     */
    private void markOwnershipLost(RedisLockHandle handle) {
        if (handle.state().compareAndSet(RedisLockHandle.LockState.ACTIVE, RedisLockHandle.LockState.LOST)) {
            handle.cancelRenewal();
        }
    }

    /**
     * 完成回调后的统一处理，确保业务异常优先于清理异常。
     */
    private void finishCallback(RedisLockHandle handle, Throwable businessFailure) {
        RuntimeException cleanupFailure = null;
        try {
            if (handle.isOwnershipLost() || !unlock(handle)) {
                cleanupFailure = new LockOwnershipLostException("分布式锁所有权已丢失: " + handle.lockKey());
            }
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
        }

        if (businessFailure != null) {
            if (cleanupFailure != null) {
                businessFailure.addSuppressed(cleanupFailure);
            }
            return;
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    /**
     * 调度失败后尽力删除刚获取的锁，并保留原始调度失败作为主异常。
     */
    private void safeUnlockAfterScheduleFailure(RedisLockHandle handle, RuntimeException primaryFailure) {
        try {
            Long result = redisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(handle.lockKey()),
                handle.token());
            if (!Long.valueOf(1L).equals(result)) {
                primaryFailure.addSuppressed(new LockOwnershipLostException("续期调度失败后的锁清理未取得所有权"));
            }
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 根据显式配置或租期三分之一确定续期间隔，并拒绝会在租期后才触发的配置。
     */
    private Duration resolveRenewalInterval(Duration leaseTime) {
        Duration interval = properties.getRenewalInterval();
        if (interval == null) {
            interval = leaseTime.dividedBy(3);
        }
        validateMillisecondDuration(interval, "续期间隔");
        if (interval.compareTo(leaseTime) >= 0) {
            throw new IllegalArgumentException("续期间隔必须小于锁租期");
        }
        return interval;
    }

    /**
     * 校验服务边界参数，避免非法调用触发 Redis 操作或创建不可续期的锁。
     */
    private void validateArguments(String key, Duration waitTime, Duration leaseTime) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("业务锁键不能为空白");
        }
        Objects.requireNonNull(waitTime, "等待时间不能为空");
        Objects.requireNonNull(leaseTime, "锁租期不能为空");
        validateMillisecondDuration(waitTime, "等待时间");
        if (waitTime.isNegative()) {
            throw new IllegalArgumentException("等待时间不能为负数");
        }
        validateMillisecondDuration(leaseTime, "锁租期");
        if (leaseTime.compareTo(MIN_LEASE_TIME) < 0) {
            throw new IllegalArgumentException("自动续期锁租期至少为1秒");
        }
        toNanos(waitTime, "等待时间");
        toNanos(leaseTime, "锁租期");
    }

    /** 校验时长为正的毫秒精度值。 */
    private void validateMillisecondDuration(Duration duration, String fieldName) {
        if (duration.isNegative() || duration.isZero() && !"等待时间".equals(fieldName)) {
            throw new IllegalArgumentException(fieldName + "必须为正数");
        }
        if (duration.toNanosPart() % 1_000_000 != 0) {
            throw new IllegalArgumentException(fieldName + "必须使用毫秒精度");
        }
    }

    /** 构造当前应用隔离的完整 Redis 锁键。 */
    private String buildLockKey(String key) {
        String prefix = properties.getKeyPrefix();
        if (prefix == null) {
            prefix = "lock:" + applicationName + ":";
        }
        if (!StringUtils.hasText(prefix)) {
            throw new IllegalArgumentException("锁键前缀不能为空白");
        }
        return prefix + key;
    }

    /** 使用单调时钟计算已用时长，防止系统时钟调整改变等待行为。 */
    private long elapsedNanos(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    /** 把时长安全转换为纳秒，避免极端配置静默溢出。 */
    private long toNanos(Duration duration, String fieldName) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(fieldName + "超出可支持范围", exception);
        }
    }

    /** 在剩余预算内有界休眠，避免竞争时忙循环。 */
    private void sleepBeforeRetry(long remainingNanos) throws InterruptedException {
        checkInterrupted();
        long sleepNanos = Math.min(RETRY_INTERVAL.toNanos(), Math.max(1L, remainingNanos));
        long millis = sleepNanos / 1_000_000;
        int nanos = (int) (sleepNanos % 1_000_000);
        Thread.sleep(millis, nanos);
    }

    /** 统一响应调用前已经存在的线程中断。 */
    private void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("等待分布式锁时线程被中断");
        }
    }

    /** 创建结果类型为长整型的 Lua 脚本。 */
    private static DefaultRedisScript<Long> script(String source) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(source);
        script.setResultType(Long.class);
        return script;
    }
}
