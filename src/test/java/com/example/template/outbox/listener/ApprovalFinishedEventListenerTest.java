package com.example.template.outbox.listener;

import com.example.template.outbox.entity.LeaveRequest;
import com.example.template.outbox.entity.LeaveRequestStatus;
import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.event.ApprovalFinishedEvent;
import com.example.template.outbox.mapper.LeaveRequestMapper;
import com.example.template.outbox.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovalFinishedEventListener} 的事务原子性测试，需要本地MySQL已执行
 * {@code sql/outbox.sql}建表。用{@link MockitoBean}把{@link OutboxEventMapper}换成可控行为的mock，
 * {@link LeaveRequestMapper} 保持真实实现，从而能验证"outbox写入失败时业务表更新也真的被回滚"。
 */
@SpringBootTest
class ApprovalFinishedEventListenerTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private LeaveRequestMapper leaveRequestMapper;

    @MockitoBean
    private OutboxEventMapper outboxEventMapper;

    private Long leaveRequestId;

    @BeforeEach
    void setUp() {
        LeaveRequest request = new LeaveRequest();
        request.setStatus(LeaveRequestStatus.APPROVING);
        LocalDateTime now = LocalDateTime.now();
        request.setCreateTime(now);
        request.setUpdateTime(now);
        leaveRequestMapper.insert(request);
        leaveRequestId = request.getId();
    }

    @AfterEach
    void tearDown() {
        leaveRequestMapper.deleteById(leaveRequestId);
    }

    @Test
    void shouldCommitBusinessUpdateAndWriteOutboxInSameTransaction() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        eventPublisher.publishEvent(new ApprovalFinishedEvent(leaveRequestId, true));

        LeaveRequest updated = leaveRequestMapper.selectById(leaveRequestId);
        assertEquals(LeaveRequestStatus.APPROVED, updated.getStatus());
        verify(outboxEventMapper).insert(any(OutboxEvent.class));
    }

    @Test
    void shouldRollBackBusinessUpdateWhenOutboxWriteFails() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenThrow(new RuntimeException("模拟outbox写入失败"));

        assertThrows(
            RuntimeException.class,
            () -> eventPublisher.publishEvent(new ApprovalFinishedEvent(leaveRequestId, true)));

        // outbox写入失败必须让整个事务回滚，业务表状态不能停留在"已改但outbox没写上"的中间态
        LeaveRequest unchanged = leaveRequestMapper.selectById(leaveRequestId);
        assertEquals(LeaveRequestStatus.APPROVING, unchanged.getStatus());
    }

    @Test
    void shouldSkipWhenBusinessRecordNotInApprovingState() {
        LeaveRequest request = leaveRequestMapper.selectById(leaveRequestId);
        request.setStatus(LeaveRequestStatus.APPROVED);
        leaveRequestMapper.updateById(request);

        // 该记录已经不是APPROVING了(比如已经被处理过)，再收到一次审批完成事件应该被忽略
        eventPublisher.publishEvent(new ApprovalFinishedEvent(leaveRequestId, false));

        LeaveRequest afterEvent = leaveRequestMapper.selectById(leaveRequestId);
        assertEquals(LeaveRequestStatus.APPROVED, afterEvent.getStatus());
        verify(outboxEventMapper, never()).insert(any(OutboxEvent.class));
    }

}
