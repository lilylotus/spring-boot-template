package com.example.template.outbox.publisher;

import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.event.OutboxMessageDeliveredEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxMessagePublisher} 的默认实现：把"投递"动作转换成一次
 * {@link ApplicationEventPublisher#publishEvent}，用进程内事件模拟"发布到MQ/事件总线"，
 * 演示"中继投递成功 -&gt; 下游消费方处理"的完整链路，不需要额外起Kafka/RabbitMQ等基础设施。
 */
@Component
public class InProcessOutboxMessagePublisher implements OutboxMessagePublisher {

    private final ApplicationEventPublisher eventPublisher;

    public InProcessOutboxMessagePublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publish(OutboxEvent event) {
        eventPublisher.publishEvent(new OutboxMessageDeliveredEvent(
            event.getId(), event.getAggregateId(), event.getEventType(), event.getPayload()));
    }

}
