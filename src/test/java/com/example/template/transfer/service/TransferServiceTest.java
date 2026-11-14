package com.example.template.transfer.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.template.redis.lock.DistributedLockService;
import com.example.template.redis.lock.RedisLockHandle;
import com.example.template.transfer.dto.TransferRequest;
import com.example.template.transfer.dto.TransferResponse;
import com.example.template.transfer.entity.TransferAccount;
import com.example.template.transfer.entity.TransferOrder;
import com.example.template.transfer.entity.TransferOrderStatus;
import com.example.template.transfer.exception.TransferException;
import com.example.template.transfer.mapper.TransferAccountMapper;
import com.example.template.transfer.mapper.TransferOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转账接口幂等的集成测试，连接真实的本地MySQL与Redis(同仓库其他 {@code @SpringBootTest} 一样需要本地
 * 基础设施)。每个用例都自建独立的账户和幂等键，不依赖迁移脚本预置的演示账户，结束后只清理自己创建的数据。
 */
@SpringBootTest
class TransferServiceTest {

    private static final int THREAD_COUNT = 8;

    @Autowired
    private TransferService transferService;

    @Autowired
    private TransferExecutor transferExecutor;

    @Autowired
    private TransferAccountMapper transferAccountMapper;

    @Autowired
    private TransferOrderMapper transferOrderMapper;

    @Autowired
    private DistributedLockService distributedLockService;

    /** 本用例创建的账户号，用于结束后清理账户及其流水。 */
    private final List<String> createdAccountNos = new ArrayList<>();

    /**
     * 删除本用例创建的账户，以及这些账户作为转出方产生的转账流水。
     */
    @AfterEach
    void cleanUp() {
        for (String accountNo : createdAccountNos) {
            transferOrderMapper.delete(
                Wrappers.<TransferOrder>lambdaQuery().eq(TransferOrder::getFromAccountNo, accountNo));
            transferAccountMapper.delete(
                Wrappers.<TransferAccount>lambdaQuery().eq(TransferAccount::getAccountNo, accountNo));
        }
    }

    /**
     * 验证正常转账：返回成功流水，转出账户扣款、转入账户入账。
     */
    @Test
    void shouldTransferAndChangeBothBalances() {
        String from = createAccount("1000.00");
        String to = createAccount("0.00");
        String key = newKey();

        TransferResponse response = transferService.transfer(key, request(from, to, "100.00"));

        assertNotNull(response.transferNo());
        assertEquals(key, response.idempotencyKey());
        assertEquals(TransferOrderStatus.SUCCESS, response.status());
        assertBalance(from, "900.00");
        assertBalance(to, "100.00");
    }

    /**
     * 验证成功后的重试：相同幂等键再次提交返回与首次完全相同的结果，余额不再变化。
     * 第二次故意把金额写成100而不是100.00，确认标度不同不会被误判为"内容不一致"。
     */
    @Test
    void shouldReplayFirstResultWhenSameKeySubmittedAgain() {
        String from = createAccount("1000.00");
        String to = createAccount("0.00");
        String key = newKey();

        TransferResponse first = transferService.transfer(key, request(from, to, "100.00"));
        TransferResponse second = transferService.transfer(key, request(from, to, "100"));

        assertEquals(first, second);
        assertBalance(from, "900.00");
        assertBalance(to, "100.00");
        assertEquals(1, countOrders(from));
    }

    /**
     * 验证同一幂等键提交不同内容被拒绝，且不影响余额和原流水。
     */
    @Test
    void shouldRejectSameKeyWithDifferentContent() {
        String from = createAccount("1000.00");
        String to = createAccount("0.00");
        String key = newKey();
        transferService.transfer(key, request(from, to, "100.00"));

        TransferException exception = assertThrows(
            TransferException.class,
            () -> transferService.transfer(key, request(from, to, "200.00")));

        assertEquals(HttpStatus.CONFLICT, exception.getHttpStatus());
        assertEquals(TransferService.KEY_CONFLICT_MESSAGE, exception.getMessage());
        assertBalance(from, "900.00");
        assertBalance(to, "100.00");
        assertEquals(1, countOrders(from));
    }

    /**
     * 验证余额不足时整体回滚、不落流水，并且失败的请求不占用幂等键：补足余额后用同一幂等键可以转账成功。
     */
    @Test
    void shouldRejectInsufficientBalanceAndKeepKeyReusable() {
        String from = createAccount("50.00");
        String to = createAccount("0.00");
        String key = newKey();

        TransferException exception = assertThrows(
            TransferException.class,
            () -> transferService.transfer(key, request(from, to, "100.00")));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getHttpStatus());
        assertBalance(from, "50.00");
        assertBalance(to, "0.00");
        assertNull(transferOrderMapper.selectByIdempotencyKey(key));

        transferAccountMapper.credit(from, new BigDecimal("100.00"));
        TransferResponse response = transferService.transfer(key, request(from, to, "100.00"));

        assertEquals(TransferOrderStatus.SUCCESS, response.status());
        assertBalance(from, "50.00");
        assertBalance(to, "100.00");
    }

    /**
     * 验证转入账户不存在时返回404语义的业务异常，转出账户余额不变。
     */
    @Test
    void shouldRejectWhenAccountMissing() {
        String from = createAccount("1000.00");

        TransferException exception = assertThrows(
            TransferException.class,
            () -> transferService.transfer(newKey(), request(from, "NOT-EXIST-" + shortId(), "100.00")));

        assertEquals(HttpStatus.NOT_FOUND, exception.getHttpStatus());
        assertBalance(from, "1000.00");
    }

    /**
     * 验证转出与转入为同一账户时返回400语义的业务异常，余额不变。
     */
    @Test
    void shouldRejectTransferToSameAccount() {
        String account = createAccount("1000.00");

        TransferException exception = assertThrows(
            TransferException.class,
            () -> transferService.transfer(newKey(), request(account, account, "100.00")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getHttpStatus());
        assertBalance(account, "1000.00");
    }

    /**
     * 验证第一层防护(Redis幂等锁)：用测试代码手动占住某个幂等键的锁，模拟"首个请求正在处理中"，
     * 这种方式不依赖线程时序，结果是确定的。占锁期间相同幂等键被立即拒绝、不同幂等键不受影响；
     * 释放后原幂等键可以正常转账。
     */
    @Test
    void shouldRejectImmediatelyWhileSameKeyIsBeingProcessed() throws Exception {
        String from = createAccount("1000.00");
        String to = createAccount("0.00");
        String key = newKey();
        Optional<RedisLockHandle> handle = distributedLockService.tryLock(
            TransferService.LOCK_KEY_PREFIX + key,
            Duration.ZERO,
            Duration.ofSeconds(30));
        assertTrue(handle.isPresent());

        try {
            TransferException exception = assertThrows(
                TransferException.class,
                () -> transferService.transfer(key, request(from, to, "100.00")));

            assertEquals(HttpStatus.CONFLICT, exception.getHttpStatus());
            assertEquals(TransferService.PROCESSING_MESSAGE, exception.getMessage());
            assertNull(transferOrderMapper.selectByIdempotencyKey(key));
            assertBalance(from, "1000.00");

            // 锁按幂等键隔离：另一个幂等键的转账不受影响
            transferService.transfer(newKey(), request(from, to, "10.00"));
            assertBalance(from, "990.00");
        } finally {
            distributedLockService.unlock(handle.get());
        }

        TransferResponse response = transferService.transfer(key, request(from, to, "100.00"));

        assertEquals(TransferOrderStatus.SUCCESS, response.status());
        assertBalance(from, "890.00");
        assertBalance(to, "110.00");
    }

    /**
     * 验证第二层防护(数据库唯一索引兜底)：绕过 {@link TransferService} 及其幂等锁，直接用同一幂等键调用
     * 执行器两次，模拟"锁提前失效、两个请求都进入了业务"。第二次必须被唯一索引拦下并整体回滚。
     */
    @Test
    void shouldBlockDuplicateByUniqueIndexWhenLockIsBypassed() {
        String from = createAccount("1000.00");
        String to = createAccount("0.00");
        String key = newKey();
        TransferRequest request = request(from, to, "100.00");

        transferExecutor.execute(key, request);

        assertThrows(DuplicateKeyException.class, () -> transferExecutor.execute(key, request));
        assertBalance(from, "900.00");
        assertBalance(to, "100.00");
        assertEquals(1, countOrders(from));
    }

    /**
     * 验证并发重复提交：多个线程用同一幂等键同时提交，每个结果要么是成功、要么是"处理中"的409，
     * 至少一个成功且成功结果的流水号相同；最终只有一条流水、只扣一次款。
     */
    @Test
    void shouldTransferOnlyOnceWhenSameKeySubmittedConcurrently() throws Exception {
        String from = createAccount("1000.00");
        String to = createAccount("0.00");
        String key = newKey();
        TransferRequest request = request(from, to, "100.00");

        List<Object> results = runConcurrently(THREAD_COUNT, index -> transferService.transfer(key, request));

        List<String> transferNos = new ArrayList<>();
        for (Object result : results) {
            if (result instanceof TransferResponse response) {
                transferNos.add(response.transferNo());
            } else {
                TransferException exception = (TransferException) result;
                assertEquals(HttpStatus.CONFLICT, exception.getHttpStatus());
                assertEquals(TransferService.PROCESSING_MESSAGE, exception.getMessage());
            }
        }
        assertTrue(!transferNos.isEmpty(), "至少应有一个请求成功");
        assertEquals(1, transferNos.stream().distinct().count());
        assertEquals(1, countOrders(from));
        assertBalance(from, "900.00");
        assertBalance(to, "100.00");
    }

    /**
     * 验证并发转账不透支：余额100，8个线程用不同幂等键各转30，只有3笔能成功，其余余额不足，最终余额10。
     */
    @Test
    void shouldNotOverdrawWhenDifferentKeysTransferConcurrently() throws Exception {
        String from = createAccount("100.00");
        String to = createAccount("0.00");

        List<Object> results = runConcurrently(
            THREAD_COUNT,
            index -> transferService.transfer(newKey(), request(from, to, "30.00")));

        long successCount = results.stream().filter(TransferResponse.class::isInstance).count();
        for (Object result : results) {
            if (result instanceof TransferException exception) {
                assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getHttpStatus());
            }
        }
        assertEquals(3, successCount);
        assertBalance(from, "10.00");
        assertBalance(to, "90.00");
    }

    /**
     * 验证固定加锁顺序：A转B与B转A并发执行不会死锁，全部成功且两个账户的总额守恒。
     */
    @Test
    void shouldNotDeadlockWhenTransferringInBothDirections() throws Exception {
        String accountA = createAccount("1000.00");
        String accountB = createAccount("1000.00");

        List<Object> results = runConcurrently(THREAD_COUNT, index -> {
            // 偶数线程A转B，奇数线程B转A
            boolean forward = index % 2 == 0;
            String from = forward ? accountA : accountB;
            String to = forward ? accountB : accountA;
            return transferService.transfer(newKey(), request(from, to, "10.00"));
        });

        for (Object result : results) {
            assertTrue(result instanceof TransferResponse, "对转不应失败: " + result);
        }
        BigDecimal total = balanceOf(accountA).add(balanceOf(accountB));
        assertEquals(0, new BigDecimal("2000.00").compareTo(total));
    }

    /**
     * 让指定数量的线程在同一时刻开始执行任务，收集每个线程的结果：正常返回值，或抛出的转账业务异常。
     * 其他类型的异常(如死锁、Redis故障)直接让测试失败。
     *
     * @param threadCount 并发线程数
     * @param task 以线程序号为入参的任务
     * @return 每个线程的返回值或其抛出的 {@link TransferException}
     */
    private List<Object> runConcurrently(int threadCount, IndexedTask task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                int index = i;
                Callable<Object> callable = () -> {
                    // 所有线程就绪后再一起放行，尽量让请求同时到达
                    ready.countDown();
                    start.await();
                    try {
                        return task.run(index);
                    } catch (TransferException e) {
                        return e;
                    }
                };
                futures.add(executor.submit(callable));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 创建一个账户号唯一的测试账户并登记到清理列表。
     *
     * @param balance 初始余额
     * @return 新账户的账户号
     */
    private String createAccount(String balance) {
        TransferAccount account = new TransferAccount();
        account.setAccountNo("T" + shortId());
        account.setAccountName("测试账户");
        account.setBalance(new BigDecimal(balance));
        account.setCreateTime(LocalDateTime.now());
        account.setUpdateTime(LocalDateTime.now());
        transferAccountMapper.insert(account);
        createdAccountNos.add(account.getAccountNo());
        return account.getAccountNo();
    }

    private TransferRequest request(
        String from,
        String to,
        String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), "测试转账");
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private BigDecimal balanceOf(String accountNo) {
        return transferAccountMapper.selectByAccountNo(accountNo).getBalance();
    }

    private long countOrders(String fromAccountNo) {
        return transferOrderMapper.selectCount(
            Wrappers.<TransferOrder>lambdaQuery().eq(TransferOrder::getFromAccountNo, fromAccountNo));
    }

    /**
     * 断言账户余额，用compareTo比较以忽略BigDecimal的标度差异。
     *
     * @param accountNo 账户号
     * @param expected 期望余额
     */
    private void assertBalance(String accountNo, String expected) {
        BigDecimal actual = balanceOf(accountNo);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "账户" + accountNo + "余额应为" + expected + "，实际为" + actual);
    }

    /** 带线程序号入参的并发任务。 */
    @FunctionalInterface
    private interface IndexedTask {

        /**
         * 执行任务。
         *
         * @param index 线程序号，从0开始
         * @return 任务结果
         * @throws Exception 任务执行失败
         */
        Object run(int index) throws Exception;
    }
}
