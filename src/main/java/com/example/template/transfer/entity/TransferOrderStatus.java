package com.example.template.transfer.entity;

/**
 * {@link TransferOrder#getStatus()} 的取值常量。目前只有SUCCESS一种：失败的转账(余额不足等)整体回滚、
 * 不落流水，所以库里不会出现失败状态的记录；保留status列和本常量类是为了将来扩展(如处理中、已冲正)。
 * 用普通字符串常量而不是枚举，与 {@code OutboxEventStatus}、{@code FileStatus} 的做法保持一致。
 */
public final class TransferOrderStatus {

    /** 转账成功：流水已落库，转出账户已扣款，转入账户已入账。 */
    public static final String SUCCESS = "SUCCESS";

    private TransferOrderStatus() {
    }

}
