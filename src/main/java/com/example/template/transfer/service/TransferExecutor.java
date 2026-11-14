package com.example.template.transfer.service;

import com.example.template.transfer.dto.TransferRequest;
import com.example.template.transfer.entity.TransferOrder;
import com.example.template.transfer.entity.TransferOrderStatus;
import com.example.template.transfer.exception.TransferException;
import com.example.template.transfer.mapper.TransferAccountMapper;
import com.example.template.transfer.mapper.TransferOrderMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 转账执行器：在一个本地事务内完成"写转账流水 + 扣减转出账户 + 增加转入账户"，三者要么全部生效、
 * 要么全部回滚。
 * <p>
 * 本类只负责"真正执行一次转账"，不关心请求是否重复——重复请求的识别与重放由 {@link TransferService}
 * 编排。之所以拆成独立的Bean而不是 {@code TransferService} 里的一个方法：{@code @Transactional}
 * 依赖Spring代理生效，同一个类内部的方法互相调用不经过代理，事务注解会静默失效。
 * <p>
 * 本类也是幂等的第二层(兜底)防线所在：即使调用方没有持有Redis幂等锁就进来了，流水表的幂等键唯一索引
 * 也会让第二次执行在插入流水时失败并整体回滚。
 */
@Service
public class TransferExecutor {

    /** 金额统一保留的小数位数，与数据库DECIMAL(18,2)一致。 */
    private static final int AMOUNT_SCALE = 2;

    private final TransferAccountMapper transferAccountMapper;
    private final TransferOrderMapper transferOrderMapper;

    public TransferExecutor(
        TransferAccountMapper transferAccountMapper,
        TransferOrderMapper transferOrderMapper) {
        this.transferAccountMapper = transferAccountMapper;
        this.transferOrderMapper = transferOrderMapper;
    }

    /**
     * 执行一次转账。
     *
     * @param idempotencyKey 调用方传入的幂等键
     * @param request 已通过字段校验的转账请求
     * @return 已落库的转账流水
     * @throws TransferException 转出转入相同(400)、账户不存在(404)、余额不足(422)
     * @throws org.springframework.dao.DuplicateKeyException 该幂等键已存在转账流水(重复请求)，事务已回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public TransferOrder execute(String idempotencyKey, TransferRequest request) {
        String fromAccountNo = request.fromAccountNo();
        String toAccountNo = request.toAccountNo();
        // 入参校验已保证最多两位小数，这里只是把100统一成100.00，不会发生舍入(否则UNNECESSARY会抛异常)
        BigDecimal amount = request.amount().setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);

        if (fromAccountNo.equals(toAccountNo)) {
            throw new TransferException(HttpStatus.BAD_REQUEST, "转出账户与转入账户不能相同");
        }
        ensureAccountExists(fromAccountNo);
        ensureAccountExists(toAccountNo);

        // 先插流水、再动余额：重复请求会在这第一条写语句上就被幂等键唯一索引拦下(抛DuplicateKeyException)，
        // 此时事务里还没有任何余额变更需要回滚，也没有持有任何账户行锁。
        // 如果另一个相同幂等键的事务还没提交，本语句会阻塞在唯一索引上，等对方提交后再报唯一键冲突。
        TransferOrder order = buildOrder(idempotencyKey, request, amount);
        transferOrderMapper.insert(order);

        // 固定加锁顺序防死锁：两条UPDATE各锁一个账户行。如果总是"先扣转出、再加转入"，那么A转B和B转A
        // 并发时，一个事务先锁A等B、另一个先锁B等A，形成死锁。这里统一按账户号字典序决定先更新谁，
        // 与谁是转出方无关，这样所有事务都以相同的顺序加锁。
        if (fromAccountNo.compareTo(toAccountNo) < 0) {
            debit(fromAccountNo, amount);
            credit(toAccountNo, amount);
        } else {
            credit(toAccountNo, amount);
            debit(fromAccountNo, amount);
        }
        return order;
    }

    /**
     * 确认账户存在，不存在时抛出404语义的业务异常。先做这一步检查，后面扣款的条件更新影响行数为0时
     * 就可以确定原因是余额不足，而不是账户不存在。
     *
     * @param accountNo 账户号
     */
    private void ensureAccountExists(String accountNo) {
        if (transferAccountMapper.selectByAccountNo(accountNo) == null) {
            throw new TransferException(HttpStatus.NOT_FOUND, "账户不存在: " + accountNo);
        }
    }

    /**
     * 扣减转出账户余额。条件更新影响行数为0说明余额不足，抛出业务异常使整个事务(含已插入的流水)回滚，
     * 幂等键因此不会被这次失败的请求占用。
     *
     * @param accountNo 转出账户号
     * @param amount 转账金额
     */
    private void debit(String accountNo, BigDecimal amount) {
        if (transferAccountMapper.debit(accountNo, amount) == 0) {
            throw new TransferException(HttpStatus.UNPROCESSABLE_ENTITY, "账户余额不足");
        }
    }

    /**
     * 增加转入账户余额。影响行数为0只可能是账户在存在性检查之后被删除，同样回滚整个事务。
     *
     * @param accountNo 转入账户号
     * @param amount 转账金额
     */
    private void credit(String accountNo, BigDecimal amount) {
        if (transferAccountMapper.credit(accountNo, amount) == 0) {
            throw new TransferException(HttpStatus.NOT_FOUND, "账户不存在: " + accountNo);
        }
    }

    /**
     * 组装待插入的转账流水。
     *
     * @param idempotencyKey 幂等键
     * @param request 转账请求
     * @param amount 已统一成两位小数的金额
     * @return 尚未落库的转账流水
     */
    private TransferOrder buildOrder(
        String idempotencyKey,
        TransferRequest request,
        BigDecimal amount) {
        TransferOrder order = new TransferOrder();
        order.setTransferNo(UUID.randomUUID().toString().replace("-", ""));
        order.setIdempotencyKey(idempotencyKey);
        order.setFromAccountNo(request.fromAccountNo());
        order.setToAccountNo(request.toAccountNo());
        order.setAmount(amount);
        order.setStatus(TransferOrderStatus.SUCCESS);
        order.setRemark(request.remark());
        // 截断到秒：数据库列是DATETIME(不带小数秒)，不截断的话首次响应里带纳秒、重放时从库里读出来的不带，
        // 两次响应的创建时间就不一致了
        order.setCreateTime(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        return order;
    }
}
