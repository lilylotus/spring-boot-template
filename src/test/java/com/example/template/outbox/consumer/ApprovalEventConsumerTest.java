package com.example.template.outbox.consumer;

import com.example.template.outbox.entity.ProcessedEvent;
import com.example.template.outbox.event.OutboxMessageDeliveredEvent;
import com.example.template.outbox.mapper.ProcessedEventMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovalEventConsumer} 幂等处理的离线单元测试：用Mockito模拟{@link ProcessedEventMapper}，
 * 验证首次处理会落地记录、重复收到同一eventId的事件会被跳过、不重复插入。
 */
class ApprovalEventConsumerTest {

    @Test
    void shouldProcessAndRecordOnFirstDelivery() {
        ProcessedEventMapper mapper = mock(ProcessedEventMapper.class);
        when(mapper.selectById("event-1")).thenReturn(null);
        ApprovalEventConsumer consumer = new ApprovalEventConsumer(mapper);

        consumer.handle(new OutboxMessageDeliveredEvent("event-1", "leave-1", "ApprovalFinishedEvent", "{}"));

        verify(mapper).insert(any(ProcessedEvent.class));
    }

    @Test
    void shouldSkipWhenEventAlreadyProcessed() {
        ProcessedEventMapper mapper = mock(ProcessedEventMapper.class);
        when(mapper.selectById("event-1")).thenReturn(new ProcessedEvent());
        ApprovalEventConsumer consumer = new ApprovalEventConsumer(mapper);

        consumer.handle(new OutboxMessageDeliveredEvent("event-1", "leave-1", "ApprovalFinishedEvent", "{}"));

        verify(mapper, never()).insert(any(ProcessedEvent.class));
    }

}
