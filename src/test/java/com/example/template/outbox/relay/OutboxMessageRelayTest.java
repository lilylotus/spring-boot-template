package com.example.template.outbox.relay;

import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.entity.OutboxEventStatus;
import com.example.template.outbox.mapper.OutboxEventMapper;
import com.example.template.outbox.publisher.OutboxMessagePublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link OutboxMessageRelay} 的离线单元测试：用Mockito模拟{@link OutboxEventMapper}和
 * {@link OutboxMessagePublisher}，不需要真实数据库/消息队列，专注验证成功标记、失败重试计数、
 * 超过上限转死信、以及"重试状态只存在于mapper返回的记录里、不依赖relay对象自身状态"这几条规则。
 */
class OutboxMessageRelayTest {

    @Test
    void shouldMarkSentWhenPublishSucceeds() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxMessagePublisher publisher = mock(OutboxMessagePublisher.class);
        OutboxEvent event = OutboxEvent.pending("leave-1", "ApprovalFinishedEvent", "{}");
        when(mapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, 100)).thenReturn(List.of(event));
        doNothing().when(publisher).publish(event);

        new OutboxMessageRelay(mapper, publisher).relay();

        assertEquals(OutboxEventStatus.SENT, event.getStatus());
        verify(mapper).updateById(event);
    }

    @Test
    void shouldIncrementRetryCountAndStayPendingWhenBelowThreshold() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxMessagePublisher publisher = mock(OutboxMessagePublisher.class);
        OutboxEvent event = OutboxEvent.pending("leave-1", "ApprovalFinishedEvent", "{}");
        when(mapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, 100)).thenReturn(List.of(event));
        doThrow(new RuntimeException("模拟投递失败")).when(publisher).publish(any());

        new OutboxMessageRelay(mapper, publisher).relay();

        assertEquals(OutboxEventStatus.PENDING, event.getStatus());
        assertEquals(1, event.getRetryCount());
    }

    @Test
    void shouldMarkFailedWhenRetryCountExceedsThreshold() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxMessagePublisher publisher = mock(OutboxMessagePublisher.class);
        OutboxEvent event = OutboxEvent.pending("leave-1", "ApprovalFinishedEvent", "{}");
        event.setRetryCount(10); // 已经重试了10次，本次失败后应该突破上限转FAILED
        when(mapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, 100)).thenReturn(List.of(event));
        doThrow(new RuntimeException("模拟投递失败")).when(publisher).publish(any());

        new OutboxMessageRelay(mapper, publisher).relay();

        assertEquals(OutboxEventStatus.FAILED, event.getStatus());
        assertEquals(11, event.getRetryCount());
    }

    @Test
    void shouldContinueProcessingPendingRecordAfterSimulatedProcessRestart() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        OutboxMessagePublisher publisher = mock(OutboxMessagePublisher.class);
        OutboxEvent event = OutboxEvent.pending("leave-1", "ApprovalFinishedEvent", "{}");
        when(mapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, 100)).thenReturn(List.of(event));
        doThrow(new RuntimeException("第一次投递失败")).when(publisher).publish(any());

        // 第一次轮询：投递失败，retryCount从0变成1，记录仍是PENDING
        new OutboxMessageRelay(mapper, publisher).relay();
        assertEquals(1, event.getRetryCount());

        // 模拟进程重启：重新构造一个全新的OutboxMessageRelay实例(不共享任何内存状态)，
        // mapper依然返回同一条记录(代表它的状态是从数据库里读出来的，不是靠进程内存延续的)
        doNothing().when(publisher).publish(event);
        new OutboxMessageRelay(mapper, publisher).relay();

        assertEquals(OutboxEventStatus.SENT, event.getStatus());
        assertEquals(1, event.getRetryCount());
    }

}
