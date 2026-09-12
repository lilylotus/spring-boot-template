package com.example.template.approval.config;

import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.config.constant.ApprovalControlledBizType;
import com.example.template.approval.config.dto.ApprovalChainLevelItem;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.service.ApprovalConfigService;
import com.example.template.util.JacksonUtils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对 {@link ApprovalConfigController} 做接口层验证：全局开关查询/更新、审批链查询/整体保存
 * （含级别不连续时的校验失败场景）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class ApprovalConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalConfigService approvalConfigService;

    @Autowired
    private ApprovalChainConfigMapper approvalChainConfigMapper;

    @BeforeEach
    void resetControlledConfiguration() {
        approvalConfigService.updateApprovalSwitch(false);
        for (ApprovalControlledBizType bizType : ApprovalControlledBizType.values()) {
            approvalChainConfigMapper.delete(
                    Wrappers.<ApprovalChainConfig>lambdaQuery()
                            .eq(ApprovalChainConfig::getBizType, bizType.name()));
        }
    }

    @Test
    void switchQueryAndUpdate_roundTrip() throws Exception {
        saveRequiredChains();

        mockMvc.perform(put("/api/approval/switch")
                        .contentType("application/json")
                        .content(JacksonUtils.toJson(Map.of("approvalEnabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/approval/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.approvalEnabled").value(true));
    }

    @Test
    void updateSwitch_rejectsIncompleteConfigurationAndKeepsSwitchDisabled() throws Exception {
        approvalConfigService.saveChain(ApprovalControlledBizType.USER_CREATE.name(),
                List.of(newLevel(1, "alice")));

        mockMvc.perform(put("/api/approval/switch")
                        .contentType("application/json")
                        .content(JacksonUtils.toJson(Map.of("approvalEnabled", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.error").value("无法开启审批流程：用户编辑审批链未配置"));

        mockMvc.perform(get("/api/approval/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.approvalEnabled").value(false));
    }

    @Test
    void saveChain_thenQuery_returnsSavedLevels() throws Exception {
        String body = JacksonUtils.toJson(Map.of("levels", java.util.List.of(
                Map.of("levelNo", 1, "approverUserId", "alice"),
                Map.of("levelNo", 2, "approverUserId", "bob"))));

        mockMvc.perform(put("/api/approval/chains/UNIT_TEST_CONTROLLER_BIZ")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/approval/chains/UNIT_TEST_CONTROLLER_BIZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.length()").value(2))
                .andExpect(jsonPath("$.result[0].approverUserId").value("alice"))
                .andExpect(jsonPath("$.result[1].approverUserId").value("bob"));
    }

    @Test
    void saveChain_rejectsNonContinuousLevels() throws Exception {
        String body = JacksonUtils.toJson(Map.of("levels", java.util.List.of(
                Map.of("levelNo", 1, "approverUserId", "alice"),
                Map.of("levelNo", 3, "approverUserId", "bob"))));

        mockMvc.perform(put("/api/approval/chains/UNIT_TEST_CONTROLLER_BIZ_2")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"));
    }

    private void saveRequiredChains() {
        approvalConfigService.saveChain(ApprovalControlledBizType.USER_CREATE.name(),
                List.of(newLevel(1, "alice")));
        approvalConfigService.saveChain(ApprovalControlledBizType.USER_EDIT.name(),
                List.of(newLevel(1, "bob")));
    }

    private ApprovalChainLevelItem newLevel(int levelNo, String approverUserId) {
        ApprovalChainLevelItem level = new ApprovalChainLevelItem();
        level.setLevelNo(levelNo);
        level.setApproverUserId(approverUserId);
        return level;
    }
}
