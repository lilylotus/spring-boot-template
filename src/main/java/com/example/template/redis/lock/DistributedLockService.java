package com.example.template.redis.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Redis 分布式锁公共服务，供业务服务在多个实例间保护短时临界区。
 * <p>
 * 锁为非可重入租期锁，成功获取后由服务自动续期；调用方仍应通过幂等、唯一约束或条件更新保护业务数据。
 */
public interface DistributedLockService {

    /**
     * 使用当前环境配置的默认等待时间和默认租期尝试获取业务锁。
     *
     * @param key 业务锁键，不能空白
     * @return 获取成功时的锁句柄，否则为空
     * @throws InterruptedException 等待过程被中断时抛出
     */
    Optional<RedisLockHandle> tryLock(String key) throws InterruptedException;

    /**
     * 在给定等待时间内尝试获取指定业务键的锁。
     *
     * @param key 业务锁键，不能空白
     * @param waitTime 最大等待时间，可为零
     * @param leaseTime 每次设置或续期的租期，至少一秒
     * @return 获取成功时的锁句柄，否则为空
     * @throws InterruptedException 等待过程被中断时抛出
     */
    Optional<RedisLockHandle> tryLock(String key, Duration waitTime, Duration leaseTime) throws InterruptedException;

    /**
     * 仅在句柄仍为当前持有者时释放锁，并停止该句柄的自动续期。
     *
     * @param handle 由本服务成功返回的锁句柄
     * @return 当前令牌匹配且删除成功时为 {@code true}
     */
    boolean unlock(RedisLockHandle handle);

    /**
     * 使用当前环境默认时长获取锁后执行一次同步业务回调。
     *
     * @param key 业务锁键
     * @param action 受保护业务回调
     * @param <T> 回调结果类型
     * @return 回调结果
     * @throws InterruptedException 等待过程被中断时抛出
     */
    <T> T executeWithLock(String key, Supplier<T> action) throws InterruptedException;

    /**
     * 使用当前环境默认时长获取锁后执行一次无返回值业务回调。
     *
     * @param key 业务锁键
     * @param action 受保护业务回调
     * @throws InterruptedException 等待过程被中断时抛出
     */
    void executeWithLock(String key, Runnable action) throws InterruptedException;

    /**
     * 获取锁后执行一次同步业务回调，并在结束时安全释放锁。
     *
     * @param key 业务锁键
     * @param waitTime 最大等待时间
     * @param leaseTime 锁租期
     * @param action 受保护业务回调
     * @param <T> 回调结果类型
     * @return 回调结果
     * @throws InterruptedException 等待过程被中断时抛出
     */
    <T> T executeWithLock(
        String key,
        Duration waitTime,
        Duration leaseTime,
        Supplier<T> action) throws InterruptedException;

    /**
     * 获取锁后执行一次无返回值的同步业务回调。
     *
     * @param key 业务锁键
     * @param waitTime 最大等待时间
     * @param leaseTime 锁租期
     * @param action 受保护业务回调
     * @throws InterruptedException 等待过程被中断时抛出
     */
    void executeWithLock(
        String key,
        Duration waitTime,
        Duration leaseTime,
        Runnable action) throws InterruptedException;
}
