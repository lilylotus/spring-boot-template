package com.example.template.transfer.service;

import com.example.template.redis.lock.DistributedLockService;
import com.example.template.redis.lock.LockAcquisitionException;
import com.example.template.redis.lock.LockOwnershipLostException;
import com.example.template.redis.lock.RedisLockProperties;
import com.example.template.transfer.dto.TransferRequest;
import com.example.template.transfer.exception.TransferException;
import com.example.template.transfer.mapper.TransferOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TransferService} 在幂等锁异常情况下的离线单元测试：用Mockito模拟锁服务的各种失败，
 * 不连接真实的Redis和数据库。重点验证"幂等防护不可被绕过"——Redis故障时请求必须失败，
 * 绝不能降级成无锁执行转账；这类场景靠停掉真实Redis来验证不现实，所以用mock覆盖。
 */
class TransferServiceLockFailureTest {

    private static final String KEY = "key-1";

    private DistributedLockService distributedLockService;
    private TransferExecutor transferExecutor;
    private TransferOrderMapper transferOrderMapper;
    private TransferService transferService;

    private final TransferRequest request =
        new TransferRequest("ACC1001", "ACC1002", new BigDecimal("100.00"), null);

    @BeforeEach
    void setUp() {
        distributedLockService = mock(DistributedLockService.class);
        transferExecutor = mock(TransferExecutor.class);
        transferOrderMapper = mock(TransferOrderMapper.class);
        transferService = new TransferService(
            distributedLockService,
            new RedisLockProperties(),
            transferExecutor,
            transferOrderMapper);
    }

    /**
     * 验证Redis故障(锁服务原样抛出连接异常)时异常继续向上传播、最终由全局兜底返回500，
     * 并且既没有查流水也没有执行转账。
     */
    @Test
    void shouldPropagateRedisFailureWithoutExecutingTransfer() throws Exception {
        RedisConnectionFailureException redisFailure = new RedisConnectionFailureException("模拟Redis不可用");
        mockLockToThrow(redisFailure);

        RedisConnectionFailureException thrown = assertThrows(
            RedisConnectionFailureException.class,
            () -> transferService.transfer(KEY, request));

        assertSame(redisFailure, thrown);
        verifyNoInteractions(transferExecutor, transferOrderMapper);
    }

    /**
     * 验证锁所有权丢失(回调执行完但释放时发现锁已失效)不被吞掉或伪装成成功，原样向上传播。
     */
    @Test
    void shouldPropagateLockOwnershipLost() throws Exception {
        mockLockToThrow(new LockOwnershipLostException("模拟锁所有权丢失"));

        assertThrows(LockOwnershipLostException.class, () -> transferService.transfer(KEY, request));
    }

    /**
     * 验证抢锁失败被转换成409"处理中"，且使用的是零等待、按幂等键拼出的锁键。
     */
    @Test
    void shouldConvertLockContentionToConflict() throws Exception {
        mockLockToThrow(new LockAcquisitionException("模拟抢锁失败"));

        TransferException thrown = assertThrows(
            TransferException.class,
            () -> transferService.transfer(KEY, request));

        assertEquals(HttpStatus.CONFLICT, thrown.getHttpStatus());
        assertEquals(TransferService.PROCESSING_MESSAGE, thrown.getMessage());
        verify(distributedLockService).executeWithLock(
            eq(TransferService.LOCK_KEY_PREFIX + KEY),
            eq(Duration.ZERO),
            any(Duration.class),
            ArgumentMatchers.<Supplier<Object>>any());
        verifyNoInteractions(transferExecutor, transferOrderMapper);
    }

    /**
     * 验证等待锁时线程被中断：恢复中断标志后以未预期异常结束，不执行转账。
     */
    @Test
    void shouldRestoreInterruptFlagWhenInterrupted() throws Exception {
        when(distributedLockService.executeWithLock(
            anyString(),
            any(Duration.class),
            any(Duration.class),
            ArgumentMatchers.<Supplier<Object>>any()))
            .thenThrow(new InterruptedException("模拟中断"));

        try {
            assertThrows(IllegalStateException.class, () -> transferService.transfer(KEY, request));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // 清除中断标志，避免影响同一线程上后续执行的测试
            Thread.interrupted();
        }
        verifyNoInteractions(transferExecutor, transferOrderMapper);
    }

    /**
     * 让mock的锁服务在带回调的加锁入口上抛出指定的运行时异常。
     *
     * @param failure 要抛出的异常
     */
    private void mockLockToThrow(RuntimeException failure) throws InterruptedException {
        when(distributedLockService.executeWithLock(
            anyString(),
            any(Duration.class),
            any(Duration.class),
            ArgumentMatchers.<Supplier<Object>>any()))
            .thenThrow(failure);
    }
}
