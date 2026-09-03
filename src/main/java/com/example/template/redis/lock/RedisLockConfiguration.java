package com.example.template.redis.lock;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Redis 锁组件配置，提供受 Spring 生命周期管理的续期调度器。
 */
@Configuration
@EnableConfigurationProperties(RedisLockProperties.class)
public class RedisLockConfiguration {

    /**
     * 创建单线程续期调度器，避免锁续期与业务线程池相互抢占。
     *
     * @return 生命周期受 Spring 管理的锁续期调度器
     */
    @Bean("redisLockTaskScheduler")
    public ThreadPoolTaskScheduler redisLockTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("redis-lock-renew-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return scheduler;
    }
}
