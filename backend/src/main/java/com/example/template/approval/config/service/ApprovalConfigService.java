package com.example.template.approval.config.service;

import com.example.template.approval.config.dto.ApprovalChainLevelItem;
import com.example.template.approval.config.entity.ApprovalChainConfig;

import java.util.List;

/**
 * 审批全局开关与审批链配置的读写能力，纯数据库读写，不依赖 {@code org.flowable.*}。
 */
public interface ApprovalConfigService {

    /**
     * 查询全局审批开关当前是否开启。
     */
    boolean isApprovalEnabled();

    /**
     * 更新全局审批开关。
     */
    void updateApprovalSwitch(boolean enabled);

    /**
     * 按 {@code bizType} 查询审批链配置，按级别升序排列。
     */
    List<ApprovalChainConfig> listChain(String bizType);

    /**
     * 整体替换某个 {@code bizType} 的审批链配置。
     * 校验规则：级别必须从1开始连续、每级审批人不能为空。
     *
     * @param bizType 业务动作类型
     * @param levels  新的审批链级别配置
     */
    void saveChain(String bizType, List<ApprovalChainLevelItem> levels);
}
