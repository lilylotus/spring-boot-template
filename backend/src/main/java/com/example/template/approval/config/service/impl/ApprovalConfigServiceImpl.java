package com.example.template.approval.config.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.example.template.approval.config.constant.ApprovalControlledBizType;
import com.example.template.approval.config.dto.ApprovalChainLevelItem;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.entity.ApprovalSwitch;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.mapper.ApprovalSwitchMapper;
import com.example.template.approval.config.service.ApprovalConfigService;
import com.example.template.common.BusinessException;

/**
 * 审批配置服务实现，负责全局开关和顺序审批链的持久化与完整性校验。
 */
@Service
@RequiredArgsConstructor
public class ApprovalConfigServiceImpl implements ApprovalConfigService {

    private final ApprovalSwitchMapper approvalSwitchMapper;
    private final ApprovalChainConfigMapper approvalChainConfigMapper;

    /**
     * 查询全局审批开关状态。
     *
     * @return 开关开启时返回 {@code true}
     */
    @Override
    public boolean isApprovalEnabled() {
        ApprovalSwitch approvalSwitch = approvalSwitchMapper.selectById(ApprovalSwitch.SINGLETON_ID);
        return approvalSwitch != null && Boolean.TRUE.equals(approvalSwitch.getApprovalEnabled());
    }

    /**
     * 更新全局审批开关；开启前要求全部受控业务类型具有完整审批链。
     *
     * @param enabled 目标开关状态
     * @throws BusinessException 开启时任一受控业务类型缺少完整审批链
     */
    @Override
    @Transactional
    public void updateApprovalSwitch(boolean enabled) {
        if (enabled) {
            validateRequiredChains();
        }

        ApprovalSwitch update = new ApprovalSwitch();
        update.setId(ApprovalSwitch.SINGLETON_ID);
        update.setApprovalEnabled(enabled);
        update.setUpdatedTime(LocalDateTime.now());
        int affected = approvalSwitchMapper.updateById(update);
        if (affected == 0) {
            // 理论不会发生（迁移脚本已插入单行种子数据），兜底补插一行
            approvalSwitchMapper.insert(update);
        }
    }

    /**
     * 查询指定业务类型的审批链。
     *
     * @param bizType 业务类型
     * @return 按审批级别升序排列的审批链
     */
    @Override
    public List<ApprovalChainConfig> listChain(String bizType) {
        return approvalChainConfigMapper.selectList(
                Wrappers.<ApprovalChainConfig>lambdaQuery()
                        .eq(ApprovalChainConfig::getBizType, bizType)
                        .orderByAsc(ApprovalChainConfig::getLevelNo));
    }

    /**
     * 整体替换指定业务类型的审批链。
     *
     * @param bizType 业务类型
     * @param levels  新审批链级别
     * @throws BusinessException 审批链为空、审批人为空或级别不连续
     */
    @Override
    @Transactional
    public void saveChain(String bizType, List<ApprovalChainLevelItem> levels) {
        if (levels == null || levels.isEmpty()) {
            throw new BusinessException("审批链级别不能为空");
        }
        for (ApprovalChainLevelItem item : levels) {
            if (!StringUtils.hasText(item.getApproverUserId())) {
                throw new BusinessException("审批人不能为空");
            }
        }
        List<Integer> sortedLevels = levels.stream()
                .map(ApprovalChainLevelItem::getLevelNo)
                .sorted()
                .collect(Collectors.toList());
        for (int i = 0; i < sortedLevels.size(); i++) {
            if (!sortedLevels.get(i).equals(i + 1)) {
                throw new BusinessException("审批链级别必须从1开始连续，不能有重复或跳级");
            }
        }

        approvalChainConfigMapper.delete(
                Wrappers.<ApprovalChainConfig>lambdaQuery().eq(ApprovalChainConfig::getBizType, bizType));

        LocalDateTime now = LocalDateTime.now();
        for (ApprovalChainLevelItem item : levels) {
            ApprovalChainConfig entity = new ApprovalChainConfig();
            entity.setBizType(bizType);
            entity.setLevelNo(item.getLevelNo());
            entity.setApproverUserId(item.getApproverUserId());
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            approvalChainConfigMapper.insert(entity);
        }
    }

    private void validateRequiredChains() {
        for (ApprovalControlledBizType bizType : ApprovalControlledBizType.values()) {
            List<ApprovalChainConfig> chain = listChain(bizType.name());
            if (chain.isEmpty()) {
                throw new BusinessException("无法开启审批流程：" + bizType.getDisplayName() + "审批链未配置");
            }
            for (int index = 0; index < chain.size(); index++) {
                ApprovalChainConfig level = chain.get(index);
                if (!Integer.valueOf(index + 1).equals(level.getLevelNo())
                        || !StringUtils.hasText(level.getApproverUserId())) {
                    throw new BusinessException("无法开启审批流程：" + bizType.getDisplayName() + "审批链配置不完整");
                }
            }
        }
    }
}
