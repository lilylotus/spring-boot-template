package com.example.template.outbox.entity;

/**
 * {@link OutboxEvent#getStatus()} 的取值常量：PENDING(待发送) -&gt; SENT(已成功投递) 或
 * FAILED(超过重试上限，进死信需人工介入)。用普通字符串常量而不是枚举，避免额外引入
 * MyBatis-Plus 的枚举类型处理器配置，保持与数据库列(VARCHAR)的映射足够简单直接。
 */
public final class OutboxEventStatus {

    /** 待发送，消息中继任务会持续轮询该状态的记录。 */
    public static final String PENDING = "PENDING";

    /** 已成功投递。 */
    public static final String SENT = "SENT";

    /** 超过重试上限，转为死信，不再被消息中继任务处理，需要人工介入。 */
    public static final String FAILED = "FAILED";

    private OutboxEventStatus() {
    }

}
