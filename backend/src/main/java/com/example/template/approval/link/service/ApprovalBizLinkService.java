package com.example.template.approval.link.service;

import java.time.LocalDateTime;
import java.util.Optional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.link.entity.ApprovalBizLink;
import com.example.template.approval.link.mapper.ApprovalBizLinkMapper;

/**
 * {@code approval_biz_link} 的读写封装：按 {@code bizType + bizId} 查询、按
 * {@code processInstanceId} 查询、更新状态。仅供 {@code approval} 包内部（如
 * {@code engine.flowable}）使用，不对 {@code identity} 等业务模块暴露。
 */
@Service
@RequiredArgsConstructor
public class ApprovalBizLinkService {

    private final ApprovalBizLinkMapper approvalBizLinkMapper;

    /**
     * 新增一条关联记录。
     *
     * @param link 待保存的审批业务关联
     * @return 已保存并带有主键和时间的关联记录
     */
    @Transactional
    public ApprovalBizLink save(ApprovalBizLink link) {
        LocalDateTime now = LocalDateTime.now();
        link.setCreatedTime(now);
        link.setUpdatedTime(now);
        approvalBizLinkMapper.insert(link);
        return link;
    }

    /**
     * 按 {@code bizType + bizId} 查询最近一次的关联记录（同一业务单据历史上可能存在多次审批）。
     *
     * @param bizType 业务动作类型
     * @param bizId   业务单据标识
     * @return 最近一次审批关联；不存在时为空
     */
    public Optional<ApprovalBizLink> findLatestByBizKey(String bizType, String bizId) {
        return Optional.ofNullable(approvalBizLinkMapper.selectOne(
                Wrappers.<ApprovalBizLink>lambdaQuery()
                        .eq(ApprovalBizLink::getBizType, bizType)
                        .eq(ApprovalBizLink::getBizId, bizId)
                        .orderByDesc(ApprovalBizLink::getId)
                        .last("LIMIT 1")));
    }

    /**
     * 按 {@code processInstanceId} 查询关联记录。
     *
     * @param processInstanceId 流程实例标识
     * @return 对应审批关联；不存在时为空
     */
    public Optional<ApprovalBizLink> findByProcessInstanceId(String processInstanceId) {
        return Optional.ofNullable(approvalBizLinkMapper.selectOne(
                Wrappers.<ApprovalBizLink>lambdaQuery()
                        .eq(ApprovalBizLink::getProcessInstanceId, processInstanceId)));
    }

    /**
     * 按对外审批实例标识查询关联记录。
     *
     * @param approvalId 审批关联记录主键
     * @return 对应关联记录；不存在时为空
     */
    public Optional<ApprovalBizLink> findById(Long approvalId) {
        return Optional.ofNullable(approvalBizLinkMapper.selectById(approvalId));
    }

    /**
     * 更新关联记录状态（如收到审批最终结果时）。
     *
     * @param id     审批关联记录主键
     * @param status 新审批状态
     */
    @Transactional
    public void updateStatus(Long id, String status) {
        ApprovalBizLink update = new ApprovalBizLink();
        update.setId(id);
        update.setStatus(status);
        update.setUpdatedTime(LocalDateTime.now());
        approvalBizLinkMapper.updateById(update);
    }
}
