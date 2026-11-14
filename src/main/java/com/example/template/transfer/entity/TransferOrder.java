package com.example.template.transfer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账流水表实体，一笔成功的转账对应一条记录。
 * <p>
 * 本表同时充当接口幂等的记录表：{@link #idempotencyKey} 上有唯一索引，流水与余额变更在同一个本地事务
 * 提交，因此"幂等键是否已被使用"和"这笔钱是否已经转过"永远是一致的；重复请求到达时，直接用已有流水
 * 还原出首次执行的结果返回给调用方。
 */
@TableName("transfer_order")
public class TransferOrder {

    /** 主键沿用全局配置的id-type: auto(见application.yml的mybatis-plus.global-config)。 */
    @TableId
    private Long id;

    /** 对外流水号，服务端生成的32位无横线UUID，不把自增主键直接暴露给调用方。 */
    private String transferNo;

    /** 调用方通过Idempotency-Key请求头传入的幂等键，唯一索引，幂等判定的唯一依据。 */
    private String idempotencyKey;

    /** 转出账户号。 */
    private String fromAccountNo;

    /** 转入账户号。 */
    private String toAccountNo;

    /** 转账金额(元)，入库前统一成两位小数。 */
    private BigDecimal amount;

    /** {@link TransferOrderStatus} 中的一个取值。 */
    private String status;

    /** 备注，可能为null。 */
    private String remark;

    /** 创建时间，精确到秒(与数据库DATETIME精度一致，保证首次响应与重放响应里的时间完全相同)。 */
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransferNo() {
        return transferNo;
    }

    public void setTransferNo(String transferNo) {
        this.transferNo = transferNo;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getFromAccountNo() {
        return fromAccountNo;
    }

    public void setFromAccountNo(String fromAccountNo) {
        this.fromAccountNo = fromAccountNo;
    }

    public String getToAccountNo() {
        return toAccountNo;
    }

    public void setToAccountNo(String toAccountNo) {
        this.toAccountNo = toAccountNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
