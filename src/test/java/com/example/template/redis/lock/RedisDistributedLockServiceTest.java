package com.example.template.redis.lock;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.TaskScheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

/**
 * Redis 分布式锁服务的离线单元测试，不连接任何真实 Redis 实例。
 */
class RedisDistributedLockServiceTest {

    @Test
    void shouldAcquireAndReleaseLockWithTokenScript() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doReturn(scheduledFuture).when(scheduler)
            .scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        when(redisTemplate.execute(
            ArgumentMatchers.<DefaultRedisScript<Long>>any(), anyList(), anyString())).thenReturn(1L);

        DistributedLockService service = createService(redisTemplate, scheduler);
        Optional<RedisLockHandle> handle = service.tryLock("order:1", Duration.ZERO, Duration.ofSeconds(30));

        assertTrue(handle.isPresent());
        assertTrue(service.unlock(handle.orElseThrow()));
        assertTrue(handle.orElseThrow().isReleased());
        verify(scheduledFuture).cancel(false);
    }

    @Test
    void shouldUseRecommendedDefaultsWhenOnlyKeyIsProvided() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doReturn(scheduledFuture).when(scheduler)
            .scheduleAtFixedRate(any(Runnable.class), any(Duration.class));

        RedisLockProperties properties = new RedisLockProperties();
        properties.setKeyPrefix("lock:test:");
        DistributedLockService service = new RedisDistributedLockService(redisTemplate, scheduler, properties, "test");

        assertTrue(service.tryLock("order:default").isPresent());
        ArgumentCaptor<Duration> leaseCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(anyString(), anyString(), leaseCaptor.capture());
        assertEquals(Duration.ofSeconds(30), leaseCaptor.getValue());
        ArgumentCaptor<Duration> intervalCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), intervalCaptor.capture());
        assertEquals(Duration.ofSeconds(10), intervalCaptor.getValue());
    }

    @Test
    void shouldNotExecuteCallbackWhenLockIsContended() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        DistributedLockService service = createService(redisTemplate, scheduler);

        assertThrows(LockAcquisitionException.class, () -> service.executeWithLock(
            "order:1", Duration.ZERO, Duration.ofSeconds(30), () -> "不应执行"));
        verify(scheduler, never()).scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
    }

    @Test
    void shouldRejectLeaseTimeTooShortForAutomaticRenewal() {
        DistributedLockService service = createService(mock(StringRedisTemplate.class), mock(TaskScheduler.class));

        assertThrows(IllegalArgumentException.class,
            () -> service.tryLock("order:1", Duration.ZERO, Duration.ofMillis(999)));
    }

    @Test
    void shouldReturnOwnershipLostWhenReleaseDoesNotMatchToken() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        TaskScheduler scheduler = mock(TaskScheduler.class);
        @SuppressWarnings("unchecked")
        ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doReturn(scheduledFuture).when(scheduler)
            .scheduleAtFixedRate(any(Runnable.class), any(Duration.class));
        when(redisTemplate.execute(
            ArgumentMatchers.<DefaultRedisScript<Long>>any(), anyList(), anyString())).thenReturn(0L);

        DistributedLockService service = createService(redisTemplate, scheduler);

        assertThrows(LockOwnershipLostException.class, () -> service.executeWithLock(
            "order:1", Duration.ZERO, Duration.ofSeconds(30), () -> "结果"));
        assertFalse(service.tryLock("order:2", Duration.ZERO, Duration.ofSeconds(30)).isEmpty());
    }

    /**
     * 构造带固定默认配置的测试服务，避免加载完整 Spring 上下文。
     */
    private DistributedLockService createService(StringRedisTemplate redisTemplate, TaskScheduler scheduler) {
        RedisLockProperties properties = new RedisLockProperties();
        properties.setKeyPrefix("lock:test:");
        return new RedisDistributedLockService(redisTemplate, scheduler, properties, "test");
    }
}
