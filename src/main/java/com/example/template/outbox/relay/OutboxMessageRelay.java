package com.example.template.outbox.relay;

import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.entity.OutboxEventStatus;
import com.example.template.outbox.mapper.OutboxEventMapper;
import com.example.template.outbox.publisher.OutboxMessagePublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息中继(Message Relay)：独立于业务事务之外的定时任务，负责把Outbox表里"待发送的草稿箱"
 * 真正投递出去。这一步和业务事务彻底解耦，允许失败重试——任务本身失败了也没关系，Outbox记录
 * 还在表里、状态还是PENDING，下一轮继续重试，不会丢；所有重试状态(PENDING/retry_count)都
 * 持久化在数据库里，不依赖任何进程内存，进程重启后能从表里已有状态继续处理未完成的记录。
 */
@Component
public class OutboxMessageRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxMessageRelay.class);

    /** 每一轮最多拉取的待发送记录数，设计文档给出的默认调优参数。 */
    private static final int BATCH_SIZE = 100;

    /** 单条记录允许的最大重试次数，超过后转为FAILED死信，需要人工介入。 */
    private static final int MAX_RETRY_COUNT = 10;

    private final OutboxEventMapper outboxEventMapper;

    private final OutboxMessagePublisher messagePublisher;

    public OutboxMessageRelay(OutboxEventMapper outboxEventMapper, OutboxMessagePublisher messagePublisher) {
        this.outboxEventMapper = outboxEventMapper;
        this.messagePublisher = messagePublisher;
    }

    /**
     * 按批轮询PENDING记录并逐条尝试投递。每条记录的状态更新相互独立：单条记录投递/更新失败
     * 不应该影响同一批次里其它记录的处理结果，所以这里不用整批事务包裹。
     */
    @Scheduled(fixedDelay = 2000)
    public void relay() {
        List<OutboxEvent> pending =
            outboxEventMapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, BATCH_SIZE);
        for (OutboxEvent event : pending) {
            relayOne(event);
        }
    }

    private void relayOne(OutboxEvent event) {
        try {
            messagePublisher.publish(event);
            event.setStatus(OutboxEventStatus.SENT);
        } catch (Exception e) {
            log.warn("Outbox记录投递失败，id={}，当前重试次数={}", event.getId(), event.getRetryCount(), e);
            event.setRetryCount(event.getRetryCount() + 1);
            if (event.getRetryCount() > MAX_RETRY_COUNT) {
                // 超过重试上限转为死信，不再参与后续轮询，需要人工介入排查
                event.setStatus(OutboxEventStatus.FAILED);
            }
            // 未超过上限的记录保持PENDING(status字段本身没有改动)，下一轮轮询会再次选中它
        }
        outboxEventMapper.updateById(event);
    }

}
