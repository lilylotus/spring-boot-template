package com.example.template.outbox.event;

/**
 * 消息中继任务投递成功后发布的进程内事件，默认的{@code InProcessOutboxMessagePublisher}
 * 用它模拟"发布到MQ/事件总线"这一步，下游消费者({@code ApprovalEventConsumer})监听该事件即可
 * 拿到投递内容并做幂等处理。真实接入MQ时，这一步会被换成MQ客户端的真实网络投递，消费者则会
 * 换成对应MQ的消息监听器，但幂等判重的逻辑不需要变化。
 */
public class OutboxMessageDeliveredEvent {

    /** 对应{@code OutboxEvent#getId()}，消费方据此做幂等判重。 */
    private final String eventId;

    private final String aggregateId;

    private final String eventType;

    /** 事件内容的JSON文本，与写入Outbox时的payload完全一致。 */
    private final String payload;

    public OutboxMessageDeliveredEvent(String eventId, String aggregateId, String eventType, String payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

}
