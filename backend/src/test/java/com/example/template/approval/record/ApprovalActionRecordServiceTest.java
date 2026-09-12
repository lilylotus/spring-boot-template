package com.example.template.approval.record;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.record.entity.ApprovalActionRecord;
import com.example.template.approval.record.service.ApprovalActionRecordService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批动作审计记录数据访问测试，覆盖保存、任务唯一约束、审批人隔离及处理时间倒序。
 */
@SpringBootTest
@Transactional
@Rollback
class ApprovalActionRecordServiceTest {

    @Autowired
    private ApprovalActionRecordService approvalActionRecordService;

    @Test
    void saveAndListByApprover_returnsOnlyOwnRecordsInDescendingOrder() {
        String approver = "record-user-" + UUID.randomUUID();
        LocalDateTime earlier = LocalDateTime.now().minusMinutes(2);
        LocalDateTime later = LocalDateTime.now().minusMinutes(1);
        ApprovalActionRecord earlierRecord = newRecord(approver, "AGREE", earlier);
        ApprovalActionRecord laterRecord = newRecord(approver, "REJECT", later);
        approvalActionRecordService.save(earlierRecord);
        approvalActionRecordService.save(newRecord("other-" + UUID.randomUUID(), "AGREE", LocalDateTime.now()));
        approvalActionRecordService.save(laterRecord);

        List<ApprovalActionRecord> records = approvalActionRecordService.listByApproverUserId(approver);

        assertThat(records).extracting(ApprovalActionRecord::getId)
                .containsExactly(laterRecord.getId(), earlierRecord.getId());
        assertThat(records).extracting(ApprovalActionRecord::getApproverUserId)
                .containsOnly(approver);
    }

    @Test
    void saveDuplicateTaskId_violatesUniqueConstraint() {
        String taskId = "task-" + UUID.randomUUID();
        ApprovalActionRecord first = newRecord("record-user", "AGREE", LocalDateTime.now());
        first.setTaskId(taskId);
        approvalActionRecordService.save(first);

        ApprovalActionRecord duplicate = newRecord("record-user", "REJECT", LocalDateTime.now());
        duplicate.setTaskId(taskId);

        assertThatThrownBy(() -> approvalActionRecordService.save(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void listByProcessInstance_returnsRecordsByLevelAndActedTime() {
        String processInstanceId = "process-" + UUID.randomUUID();
        ApprovalActionRecord level2 = newRecord("bob", "REJECT", LocalDateTime.now());
        level2.setProcessInstanceId(processInstanceId);
        level2.setLevelNo(2);
        ApprovalActionRecord level1 = newRecord("alice", "AGREE", LocalDateTime.now().plusMinutes(1));
        level1.setProcessInstanceId(processInstanceId);
        level1.setLevelNo(1);
        approvalActionRecordService.save(level2);
        approvalActionRecordService.save(level1);

        assertThat(approvalActionRecordService.listByProcessInstanceId(processInstanceId))
                .extracting(ApprovalActionRecord::getLevelNo)
                .containsExactly(1, 2);
    }

    private ApprovalActionRecord newRecord(String approverUserId, String action, LocalDateTime actedTime) {
        String suffix = UUID.randomUUID().toString();
        ApprovalActionRecord record = new ApprovalActionRecord();
        record.setBizType("USER_EDIT");
        record.setBizId("biz-" + suffix);
        record.setProcessInstanceId("process-" + suffix);
        record.setTaskId("task-" + suffix);
        record.setApproverUserId(approverUserId);
        record.setLevelNo(1);
        record.setTaskTitle("审批");
        record.setAction(action);
        record.setComment("测试意见");
        record.setTaskCreatedTime(actedTime.minusMinutes(1));
        record.setActedTime(actedTime);
        return record;
    }
}
