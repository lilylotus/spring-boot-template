package com.example.template.transfer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账示例的账户表实体。余额的增减不通过"查出实体、改完再整体更新"的方式进行，而是走
 * {@code TransferAccountMapper} 里的条件更新SQL，避免并发下出现丢失更新或余额被扣成负数。
 */
@TableName("transfer_account")
public class TransferAccount {

    /** 主键沿用全局配置的id-type: auto(见application.yml的mybatis-plus.global-config)。 */
    @TableId
    private Long id;

    /** 账户号，对外标识，表上有唯一索引。 */
    private String accountNo;

    /** 户名。 */
    private String accountName;

    /** 余额(元)，用BigDecimal对应数据库的DECIMAL(18,2)，不使用浮点类型。 */
    private BigDecimal balance;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public void setAccountNo(String accountNo) {
        this.accountNo = accountNo;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
