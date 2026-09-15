package com.example.template.outbox.listener;

import com.example.template.outbox.OutboxException;
import com.example.template.outbox.entity.LeaveRequest;
import com.example.template.outbox.entity.LeaveRequestStatus;
import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.event.ApprovalFinishedEvent;
import com.example.template.outbox.mapper.LeaveRequestMapper;
import com.example.template.outbox.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox模式的地基：业务表更新和Outbox记录写入在同一个{@code @Transactional}方法里完成，
 * 数据库保证这两次写入要么都提交、要么都回滚，不存在"业务状态改了但Outbox记录没写上"的中间态。
 * <p>
 * 之所以放在业务侧独立的事件监听器里而不是审批引擎自己的监听器里：如果审批引擎用的是独立
 * 数据库/独立schema，引擎自己的事务没法跟这里的业务库Outbox表在同一个本地事务里原子提交；
 * 让审批完成的事实通过Spring事件传到业务侧、在业务侧自己的事务里落地，才能保证这里说的原子性。
 */
@Component
public class ApprovalFinishedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ApprovalFinishedEventListener.class);

    private final LeaveRequestMapper leaveRequestMapper;

    private final OutboxEventMapper outboxEventMapper;

    private final ObjectMapper objectMapper;

    public ApprovalFinishedEventListener(
        LeaveRequestMapper leaveRequestMapper, OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper) {
        this.leaveRequestMapper = leaveRequestMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理审批完成事件：更新请假记录状态并写入一条Outbox记录，两步在同一事务内完成。
     *
     * @param event 审批完成事件
     */
    @EventListener
    @Transactional
    public void onApprovalFinished(ApprovalFinishedEvent event) {
        LeaveRequest request = leaveRequestMapper.selectById(event.getBusinessKey());
        if (request == null || !LeaveRequestStatus.APPROVING.equals(request.getStatus())) {
            // 记录不存在，或者已经不处于"审批中"(比如已经被处理过)，跳过，避免重复更新/重复写Outbox
            log.warn("跳过审批完成事件，业务记录不存在或状态不是APPROVING: businessKey={}", event.getBusinessKey());
            return;
        }

        request.setStatus(event.isApproved() ? LeaveRequestStatus.APPROVED : LeaveRequestStatus.REJECTED);
        leaveRequestMapper.updateById(request);

        String payload = serialize(event);
        OutboxEvent outboxEvent = OutboxEvent.pending(
            String.valueOf(event.getBusinessKey()), ApprovalFinishedEvent.class.getSimpleName(), payload);
        outboxEventMapper.insert(outboxEvent);
    }

    private String serialize(ApprovalFinishedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            // 序列化失败会让整个方法抛异常，触发事务回滚，业务表更新也一并撤销，符合"同一事务"的预期
            throw new OutboxException("审批完成事件序列化失败: " + event, e);
        }
    }

}
