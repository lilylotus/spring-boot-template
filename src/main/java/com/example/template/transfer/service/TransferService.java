package com.example.template.transfer.service;

import com.example.template.redis.lock.DistributedLockService;
import com.example.template.redis.lock.LockAcquisitionException;
import com.example.template.redis.lock.RedisLockProperties;
import com.example.template.transfer.dto.TransferRequest;
import com.example.template.transfer.dto.TransferResponse;
import com.example.template.transfer.entity.TransferOrder;
import com.example.template.transfer.exception.TransferException;
import com.example.template.transfer.mapper.TransferOrderMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 转账接口的幂等编排服务：保证同一个幂等键无论被提交多少次(串行重试、并发重复)，资金只划转一次。
 * <p>
 * 采用两层防护，各管一件事：
 * <ul>
 *   <li><b>第一层 Redis幂等锁</b>：按幂等键加分布式锁，同一幂等键同一时刻只放行一个请求。并发的重复请求
 *       在入口一次Redis往返内就被拒绝，不占数据库连接、不到唯一索引上排队。但它<b>不保证</b>"只执行一次"
 *       ——锁可能因租期到期、主从切换、进程长时间暂停而提前失效。</li>
 *   <li><b>第二层 数据库唯一索引</b>：流水表的幂等键唯一索引是"只执行一次"的最终保证，流水本身同时保存了
 *       首次执行的结果，供重复请求重放。锁失效时由它兜底。</li>
 * </ul>
 * 只有锁没有唯一索引，锁失效时会重复扣款；只有唯一索引没有锁，结果仍然正确，但重复请求全部打到数据库。
 * <p>
 * 本类不带事务，真正的转账事务在 {@link TransferExecutor} 里，这样才能做到"锁包住事务"(见
 * {@link #transfer})，以及在事务之外回查流水。
 */
@Service
public class TransferService {

    /** 幂等锁的业务键前缀，完整Redis键还会由锁服务拼上全局命名空间前缀。 */
    public static final String LOCK_KEY_PREFIX = "transfer:idempotent:";

    /** 相同幂等键的请求正在处理(抢锁失败)时返回给调用方的提示。 */
    public static final String PROCESSING_MESSAGE = "请求正在处理中，请勿重复提交";

    /** 幂等键已被一笔内容不同的转账使用时返回给调用方的提示，与"处理中"的提示不同，便于调用方区分。 */
    public static final String KEY_CONFLICT_MESSAGE = "幂等键已被其他请求使用";

    private static final Logger LOGGER = LogManager.getLogger(TransferService.class);

    private final DistributedLockService distributedLockService;
    private final RedisLockProperties redisLockProperties;
    private final TransferExecutor transferExecutor;
    private final TransferOrderMapper transferOrderMapper;

    public TransferService(
        DistributedLockService distributedLockService,
        RedisLockProperties redisLockProperties,
        TransferExecutor transferExecutor,
        TransferOrderMapper transferOrderMapper) {
        this.distributedLockService = distributedLockService;
        this.redisLockProperties = redisLockProperties;
        this.transferExecutor = transferExecutor;
        this.transferOrderMapper = transferOrderMapper;
    }

    /**
     * 幂等地执行一次转账：幂等键首次出现时真正执行，已成功执行过则返回首次的结果。
     * <p>
     * 执行顺序是"加锁 → 开事务 → 提交事务 → 释放锁"，锁必须包住整个事务。如果反过来在事务里加锁、
     * 事务提交前就把锁放掉，下一个拿到锁的重复请求去查流水时，前一个事务还没提交、查不到，就会再执行一遍
     * ——这是幂等锁最常见的错误用法。这里事务边界在 {@link TransferExecutor#execute} 上，它在锁的回调
     * 内部被调用，回调返回时事务已经提交，之后锁服务才释放锁。
     *
     * @param idempotencyKey 调用方传入的幂等键
     * @param request 已通过字段校验的转账请求
     * @return 转账结果；重复请求返回与首次执行相同的结果
     * @throws TransferException 相同幂等键的请求正在处理(409)、幂等键已用于内容不同的请求(409)，
     *                           以及 {@link TransferExecutor#execute} 抛出的业务错误
     */
    public TransferResponse transfer(String idempotencyKey, TransferRequest request) {
        String lockKey = LOCK_KEY_PREFIX + idempotencyKey;
        try {
            // 等待时间传零：抢不到锁立即失败，不排队。重复请求想要的是"首次执行的结果"，而结果要等持锁的
            // 请求做完才有，与其让它占着容器线程等，不如马上告诉调用方"处理中"，由调用方稍后重试。
            // Redis故障时锁服务会原样抛出异常，这里不捕获——宁可让请求失败(500)，也不降级成无锁执行。
            return distributedLockService.executeWithLock(
                lockKey,
                Duration.ZERO,
                redisLockProperties.getLeaseTime(),
                () -> transferWithinLock(idempotencyKey, request));
        } catch (LockAcquisitionException e) {
            throw new TransferException(HttpStatus.CONFLICT, PROCESSING_MESSAGE);
        } catch (InterruptedException e) {
            // 恢复中断标志，让上层(容器线程池)仍能感知到中断，再以未预期异常的形式结束本次请求
            Thread.currentThread().interrupt();
            throw new IllegalStateException("获取转账幂等锁时线程被中断", e);
        }
    }

    /**
     * 持有幂等锁期间执行的逻辑：先查是否已执行过，没有才真正执行。
     *
     * @param idempotencyKey 幂等键
     * @param request 转账请求
     * @return 转账结果
     */
    private TransferResponse transferWithinLock(String idempotencyKey, TransferRequest request) {
        // 已经成功执行过：直接重放首次结果。注意只靠这一步"先查后做"是不够的——没有锁和唯一索引时，
        // 两个并发请求会同时查不到，然后都去执行。这一步只是持锁之后的正常判断路径。
        TransferOrder existing = transferOrderMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return replay(existing, request);
        }
        try {
            return TransferResponse.from(transferExecutor.execute(idempotencyKey, request));
        } catch (DuplicateKeyException e) {
            // 兜底路径：正常情况下持锁期间不会有别人插入同一幂等键的流水，走到这里说明锁已提前失效、
            // 另一个请求抢先完成了转账。本次事务已被唯一索引拦下并回滚，没有产生任何资金变动。
            // 此时已经在事务之外，重新查询读到的是对方已提交的流水，不受可重复读快照的影响。
            LOGGER.warn("转账幂等锁未能阻止并发重复请求，已由幂等键唯一索引拦截，idempotencyKey={}", idempotencyKey);
            TransferOrder committed = transferOrderMapper.selectByIdempotencyKey(idempotencyKey);
            if (committed == null) {
                // 唯一键冲突却查不到该幂等键的流水，说明冲突的不是幂等键(如流水号碰撞)，按未预期异常处理
                throw e;
            }
            return replay(committed, request);
        }
    }

    /**
     * 用已有流水重放首次执行的结果。重放前比对本次请求与首次请求的关键内容：同一个幂等键只能对应同一笔
     * 转账，内容不同说明调用方复用了幂等键，必须拒绝，不能把另一笔转账的"成功"返回给它。
     *
     * @param existing 该幂等键已有的转账流水
     * @param request 本次请求
     * @return 与首次执行相同的转账结果
     */
    private TransferResponse replay(TransferOrder existing, TransferRequest request) {
        // 金额用compareTo比较而不是equals：BigDecimal的equals会把100和100.00当成不相等
        boolean sameRequest = existing.getFromAccountNo().equals(request.fromAccountNo())
            && existing.getToAccountNo().equals(request.toAccountNo())
            && existing.getAmount().compareTo(request.amount()) == 0;
        if (!sameRequest) {
            throw new TransferException(HttpStatus.CONFLICT, KEY_CONFLICT_MESSAGE);
        }
        return TransferResponse.from(existing);
    }
}
