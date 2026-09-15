package com.example.template.outbox;

import com.example.template.outbox.entity.LeaveRequest;
import com.example.template.outbox.entity.LeaveRequestStatus;
import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.entity.OutboxEventStatus;
import com.example.template.outbox.entity.ProcessedEvent;
import com.example.template.outbox.event.ApprovalFinishedEvent;
import com.example.template.outbox.mapper.LeaveRequestMapper;
import com.example.template.outbox.mapper.OutboxEventMapper;
import com.example.template.outbox.mapper.ProcessedEventMapper;
import com.example.template.outbox.relay.OutboxMessageRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Outbox模式端到端集成测试，需要本地MySQL已执行{@code sql/outbox.sql}建表。
 * <p>
 * 完整链路：发布{@link ApprovalFinishedEvent} -&gt; 监听器同事务写入业务表与Outbox
 * -&gt; 手动触发一次消息中继轮询 -&gt; Outbox记录变为SENT，下游消费者收到并处理
 * -&gt; {@code processed_event}表中出现对应记录。
 */
@SpringBootTest
class OutboxEndToEndTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private ProcessedEventMapper processedEventMapper;

    @Autowired
    private OutboxMessageRelay outboxMessageRelay;

    private Long leaveRequestId;
    private String outboxEventId;

    @AfterEach
    void cleanUp() {
        if (outboxEventId != null) {
            processedEventMapper.deleteById(outboxEventId);
            outboxEventMapper.deleteById(outboxEventId);
        }
        if (leaveRequestId != null) {
            leaveRequestMapper.deleteById(leaveRequestId);
        }
    }

    @Test
    void shouldDeliverOutboxEventAndProcessItIdempotently() {
        LeaveRequest request = new LeaveRequest();
        request.setStatus(LeaveRequestStatus.APPROVING);
        LocalDateTime now = LocalDateTime.now();
        request.setCreateTime(now);
        request.setUpdateTime(now);
        leaveRequestMapper.insert(request);
        leaveRequestId = request.getId();

        // 第一步+第二步：业务写入和Outbox写入在同一本地事务提交
        eventPublisher.publishEvent(new ApprovalFinishedEvent(leaveRequestId, true));

        List<OutboxEvent> pendingEvents =
            outboxEventMapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, 10);
        assertEquals(1, pendingEvents.size());
        outboxEventId = pendingEvents.get(0).getId();

        // 第三步：手动触发一次消息中继轮询，模拟定时任务执行一次
        outboxMessageRelay.relay();

        OutboxEvent afterRelay = outboxEventMapper.selectById(outboxEventId);
        assertEquals(OutboxEventStatus.SENT, afterRelay.getStatus());

        // 第四步+第五步：下游消费者收到投递事件后完成幂等处理，落地processed_event记录
        ProcessedEvent processedEvent = processedEventMapper.selectById(outboxEventId);
        assertNotNull(processedEvent);
    }

}
