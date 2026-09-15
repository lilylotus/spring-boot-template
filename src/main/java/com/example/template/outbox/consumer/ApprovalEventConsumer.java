package com.example.template.outbox.consumer;

import com.example.template.outbox.entity.ProcessedEvent;
import com.example.template.outbox.event.OutboxMessageDeliveredEvent;
import com.example.template.outbox.mapper.ProcessedEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 消息投递之后的示例消费者：必须假设同一条消息可能会被投递不止一次(中继重试导致重复发送，
 * 或者真实MQ的at-least-once语义)，处理逻辑必须幂等——用事件里带的唯一ID(即
 * {@code OutboxEvent#getId()})查{@code processed_event}表判断是否已处理过，已处理则直接跳过。
 */
@Component
public class ApprovalEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ApprovalEventConsumer.class);

    private final ProcessedEventMapper processedEventMapper;

    public ApprovalEventConsumer(ProcessedEventMapper processedEventMapper) {
        this.processedEventMapper = processedEventMapper;
    }

    /**
     * 处理一次消息投递，幂等：同一个eventId只会真正执行一次业务逻辑并落一条处理记录。
     *
     * @param event 消息中继投递过来的事件
     */
    @EventListener
    public void handle(OutboxMessageDeliveredEvent event) {
        if (processedEventMapper.selectById(event.getEventId()) != null) {
            log.debug("事件已处理过，跳过: eventId={}", event.getEventId());
            return;
        }

        doBusinessLogic(event);
        processedEventMapper.insert(new ProcessedEvent(event.getEventId(), LocalDateTime.now()));
    }

    /**
     * 真正的业务处理逻辑，本类只是框架自身的演示消费者，这里不做具体业务动作。
     */
    private void doBusinessLogic(OutboxMessageDeliveredEvent event) {
        log.info("处理事件: eventId={}, eventType={}, aggregateId={}",
            event.getEventId(), event.getEventType(), event.getAggregateId());
    }

}
