package com.example.template.approval.record.service;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.record.entity.ApprovalActionRecord;
import com.example.template.approval.record.mapper.ApprovalActionRecordMapper;

/**
 * 审批动作审计记录服务，负责事务内保存记录并按审批人查询处理历史。
 */
@Service
@RequiredArgsConstructor
public class ApprovalActionRecordService {

    private final ApprovalActionRecordMapper approvalActionRecordMapper;

    /**
     * 保存一条审批动作审计记录。
     *
     * @param record 待保存的审批动作记录
     * @return 已保存并带有主键及处理时间的记录
     */
    @Transactional
    public ApprovalActionRecord save(ApprovalActionRecord record) {
        if (record.getActedTime() == null) {
            record.setActedTime(LocalDateTime.now());
        }
        approvalActionRecordMapper.insert(record);
        return record;
    }

    /**
     * 查询指定审批人的审批记录，并按处理时间和主键倒序排列。
     *
     * @param approverUserId 审批人标识
     * @return 该审批人的审批动作记录
     */
    public List<ApprovalActionRecord> listByApproverUserId(String approverUserId) {
        return approvalActionRecordMapper.selectList(
                Wrappers.<ApprovalActionRecord>lambdaQuery()
                        .eq(ApprovalActionRecord::getApproverUserId, approverUserId)
                        .orderByDesc(ApprovalActionRecord::getActedTime)
                        .orderByDesc(ApprovalActionRecord::getId));
    }

    /**
     * 查询指定流程实例的动作记录，并按审批级别、处理时间和主键稳定升序排列。
     *
     * @param processInstanceId 流程实例标识
     * @return 流程实例的审批动作记录
     */
    public List<ApprovalActionRecord> listByProcessInstanceId(String processInstanceId) {
        return approvalActionRecordMapper.selectList(
                Wrappers.<ApprovalActionRecord>lambdaQuery()
                        .eq(ApprovalActionRecord::getProcessInstanceId, processInstanceId)
                        .orderByAsc(ApprovalActionRecord::getLevelNo)
                        .orderByAsc(ApprovalActionRecord::getActedTime)
                        .orderByAsc(ApprovalActionRecord::getId));
    }
}
