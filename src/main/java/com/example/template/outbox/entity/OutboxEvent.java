package com.example.template.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox模式的核心表：业务写入和本条记录写入必须在同一本地事务内提交，事务提交后本记录才是
 * 消息中继任务(见{@code OutboxMessageRelay})眼中"待发送的草稿箱"，由中继任务负责真正对外投递。
 */
@TableName("outbox_event")
public class OutboxEvent {

    /** 主键用应用侧生成的UUID(IdType.INPUT)，不用全局配置的自增id，避免暴露内部自增序号。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 业务聚合标识，比如leave_request的主键，用于排查"这条outbox记录对应哪个业务对象"。 */
    private String aggregateId;

    /** 事件类型，通常是领域事件类的简单类名，供消费方判断如何反序列化/处理payload。 */
    private String eventType;

    /** 事件内容，Jackson序列化后的JSON文本。 */
    private String payload;

    /** {@link OutboxEventStatus} 中的一个取值。 */
    private String status;

    private LocalDateTime createTime;

    /** 投递失败的累计重试次数，超过消息中继任务配置的上限后记录转为FAILED。 */
    private Integer retryCount;

    public OutboxEvent() {
    }

    /**
     * 构造一条初始状态为PENDING、待发送的新记录。
     *
     * @param aggregateId 业务聚合标识
     * @param eventType   事件类型
     * @param payload     事件内容的JSON文本
     * @return 待写入数据库的新记录
     */
    public static OutboxEvent pending(String aggregateId, String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID().toString();
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.status = OutboxEventStatus.PENDING;
        event.createTime = LocalDateTime.now();
        event.retryCount = 0;
        return event;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

}
