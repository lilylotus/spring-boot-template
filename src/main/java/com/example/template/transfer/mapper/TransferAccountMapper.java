package com.example.template.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.template.transfer.entity.TransferAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

/**
 * {@link TransferAccount} 的MyBatis-Plus Mapper。基础读写用 {@link BaseMapper}；余额增减是两条很短的
 * 条件更新SQL，直接用 {@code @Update} 注解写在接口上，不为此单独建XML。
 */
@Mapper
public interface TransferAccountMapper extends BaseMapper<TransferAccount> {

    /**
     * 按账户号查询账户。
     *
     * @param accountNo 账户号
     * @return 账户记录，不存在时返回 {@code null}
     */
    default TransferAccount selectByAccountNo(String accountNo) {
        return selectOne(Wrappers.<TransferAccount>lambdaQuery().eq(TransferAccount::getAccountNo, accountNo));
    }

    /**
     * 扣减余额。"余额是否足够"的判断和扣减在同一条UPDATE里完成：WHERE里带上 {@code balance >= 金额}，
     * 数据库对这一行加行锁后再判断条件，所以并发扣款不会把余额扣成负数，也不需要先
     * {@code SELECT ... FOR UPDATE} 再更新。
     *
     * @param accountNo 被扣款的账户号
     * @param amount 扣减金额，必须为正数
     * @return 影响行数：1表示扣款成功；0表示账户不存在或余额不足(调用方已先确认账户存在，故0即余额不足)
     */
    @Update("UPDATE transfer_account SET balance = balance - #{amount}, update_time = NOW() "
        + "WHERE account_no = #{accountNo} AND balance >= #{amount}")
    int debit(@Param("accountNo") String accountNo, @Param("amount") BigDecimal amount);

    /**
     * 增加余额。用 {@code balance = balance + 金额} 的相对更新而不是"读出余额、算好再写回"，
     * 避免并发入账时后写覆盖先写(丢失更新)。
     *
     * @param accountNo 入账的账户号
     * @param amount 增加金额，必须为正数
     * @return 影响行数：1表示入账成功；0表示账户不存在
     */
    @Update("UPDATE transfer_account SET balance = balance + #{amount}, update_time = NOW() "
        + "WHERE account_no = #{accountNo}")
    int credit(@Param("accountNo") String accountNo, @Param("amount") BigDecimal amount);
}
