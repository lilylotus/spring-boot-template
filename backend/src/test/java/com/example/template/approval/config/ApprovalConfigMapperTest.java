package com.example.template.approval.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.entity.ApprovalSwitch;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.mapper.ApprovalSwitchMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 approval_switch / approval_chain_config 对应 Mapper 的基本增删改查。
 */
@SpringBootTest
@Transactional
@Rollback
class ApprovalConfigMapperTest {

    @Autowired
    private ApprovalSwitchMapper approvalSwitchMapper;

    @Autowired
    private ApprovalChainConfigMapper approvalChainConfigMapper;

    @Test
    void approvalSwitch_seedRow_existsAndCanBeUpdated() {
        ApprovalSwitch seed = approvalSwitchMapper.selectById(ApprovalSwitch.SINGLETON_ID);
        assertThat(seed).isNotNull();

        ApprovalSwitch update = new ApprovalSwitch();
        update.setId(ApprovalSwitch.SINGLETON_ID);
        update.setApprovalEnabled(true);
        update.setUpdatedTime(LocalDateTime.now());
        int affected = approvalSwitchMapper.updateById(update);
        assertThat(affected).isEqualTo(1);

        ApprovalSwitch reloaded = approvalSwitchMapper.selectById(ApprovalSwitch.SINGLETON_ID);
        assertThat(reloaded.getApprovalEnabled()).isTrue();
    }

    @Test
    void approvalChainConfig_insertQueryUpdateDelete() {
        LocalDateTime now = LocalDateTime.now();
        ApprovalChainConfig level1 = newLevel("UNIT_TEST_BIZ", 1, "alice", now);
        ApprovalChainConfig level2 = newLevel("UNIT_TEST_BIZ", 2, "bob", now);
        approvalChainConfigMapper.insert(level1);
        approvalChainConfigMapper.insert(level2);

        List<ApprovalChainConfig> chain = approvalChainConfigMapper.selectList(
                Wrappers.<ApprovalChainConfig>lambdaQuery()
                        .eq(ApprovalChainConfig::getBizType, "UNIT_TEST_BIZ")
                        .orderByAsc(ApprovalChainConfig::getLevelNo));
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).getApproverUserId()).isEqualTo("alice");
        assertThat(chain.get(1).getApproverUserId()).isEqualTo("bob");

        level1.setApproverUserId("alice2");
        approvalChainConfigMapper.updateById(level1);
        ApprovalChainConfig reloaded = approvalChainConfigMapper.selectById(level1.getId());
        assertThat(reloaded.getApproverUserId()).isEqualTo("alice2");

        approvalChainConfigMapper.deleteById(level1.getId());
        approvalChainConfigMapper.deleteById(level2.getId());
        List<ApprovalChainConfig> afterDelete = approvalChainConfigMapper.selectList(
                Wrappers.<ApprovalChainConfig>lambdaQuery().eq(ApprovalChainConfig::getBizType, "UNIT_TEST_BIZ"));
        assertThat(afterDelete).isEmpty();
    }

    private ApprovalChainConfig newLevel(String bizType, int levelNo, String approver, LocalDateTime now) {
        ApprovalChainConfig entity = new ApprovalChainConfig();
        entity.setBizType(bizType);
        entity.setLevelNo(levelNo);
        entity.setApproverUserId(approver);
        entity.setCreatedTime(now);
        entity.setUpdatedTime(now);
        return entity;
    }
}
