package com.example.template.redis.lock;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 分布式锁的命名空间和续期频率配置。
 * <p>
 * 未设置续期间隔时，服务按每把锁租期的三分之一调度；设置后必须小于租期。
 */
@ConfigurationProperties(prefix = "template.redis-lock")
public class RedisLockProperties {

    private String keyPrefix;
    private Duration waitTime = Duration.ofSeconds(3);
    private Duration leaseTime = Duration.ofSeconds(30);
    private Duration renewalInterval = Duration.ofSeconds(10);

    /**
     * 获取锁键命名空间前缀。
     *
     * @return 已配置前缀；未配置时为 {@code null}
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /**
     * 设置锁键命名空间前缀。
     *
     * @param keyPrefix 锁键前缀
     */
    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    /**
     * 获取常用入口的默认等待时间。
     *
     * @return 默认等待时间，初始值为三秒
     */
    public Duration getWaitTime() {
        return waitTime;
    }

    /**
     * 设置常用入口的默认等待时间。
     *
     * @param waitTime 默认等待时间
     */
    public void setWaitTime(Duration waitTime) {
        this.waitTime = waitTime;
    }

    /**
     * 获取常用入口的默认锁租期。
     *
     * @return 默认租期，初始值为三十秒
     */
    public Duration getLeaseTime() {
        return leaseTime;
    }

    /**
     * 设置常用入口的默认锁租期。
     *
     * @param leaseTime 默认锁租期
     */
    public void setLeaseTime(Duration leaseTime) {
        this.leaseTime = leaseTime;
    }

    /**
     * 获取显式续期间隔。
     *
     * @return 续期间隔，初始值为十秒
     */
    public Duration getRenewalInterval() {
        return renewalInterval;
    }

    /**
     * 设置显式续期间隔。
     *
     * @param renewalInterval 续期间隔
     */
    public void setRenewalInterval(Duration renewalInterval) {
        this.renewalInterval = renewalInterval;
    }
}
