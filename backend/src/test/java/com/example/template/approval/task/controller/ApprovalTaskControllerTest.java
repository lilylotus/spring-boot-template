package com.example.template.approval.task.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import com.example.template.approval.api.ApprovalGateway;
import com.example.template.approval.api.dto.StartApprovalCommand;
import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.engine.flowable.SpringContextHolder;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import com.example.template.approval.record.entity.ApprovalActionRecord;
import com.example.template.approval.record.mapper.ApprovalActionRecordMapper;
import com.example.template.operator.DefaultOperator;
import com.example.template.util.JacksonUtils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对 {@link ApprovalTaskController} 做接口层验证：先经 {@link ApprovalGateway} 真实发起一条 2 级
 * 指定用户顺序审批流程，再通过本 Controller 的 HTTP 接口查询待办、提交同意/驳回，验证任务在两级
 * 审批人之间正确流转（对应 design.md D9、tasks.md 5.7，为 8.2/8.3 端到端验证提供 HTTP 入口）。
 * 使用真实本地 MySQL + Flowable 引擎，不做事务回滚，测试后手工清理本次新增的审批链配置。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApprovalTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalGateway approvalGateway;

    @Autowired
    private ApprovalChainConfigMapper approvalChainConfigMapper;

    @Autowired
    private ApprovalActionRecordMapper approvalActionRecordMapper;

    @Autowired
    private ApprovalBizLinkService approvalBizLinkService;

    @Autowired
    private ApplicationContext applicationContext;

    private String bizType;

    @BeforeEach
    void ensureSpringContextHolderInitialized() {
        // SpringContextHolderTest 会用反射把 SpringContextHolder 的静态字段临时置空以验证
        // “未初始化”场景；若它先于本类运行且 Spring 测试上下文被缓存复用（未重新 refresh，
        // 不会再次触发 ApplicationContextAware 回调），会导致本类测试运行时该静态字段仍为
        // null，使流程结束时 ApprovalResultExecutionListener 取不到 ApplicationEventPublisher。
        // 这里显式重新绑定，保证本类测试结果不受其他测试类执行顺序影响。
        new SpringContextHolder().setApplicationContext(applicationContext);
    }

    @AfterEach
    void cleanup() {
        if (bizType != null) {
            approvalActionRecordMapper.delete(
                    Wrappers.<ApprovalActionRecord>lambdaQuery().eq(ApprovalActionRecord::getBizType, bizType));
            approvalChainConfigMapper.delete(
                    Wrappers.<ApprovalChainConfig>lambdaQuery().eq(ApprovalChainConfig::getBizType, bizType));
        }
    }

    @Test
    void listPendingTasks_thenAgreeTwice_flowsToApproved() throws Exception {
        bizType = "TASK_CTRL_" + randomSuffix();
        saveChain(bizType, List.of("alice", "bob"));
        String bizId = "biz-" + UUID.randomUUID();

        approvalGateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));
        Long approvalId = approvalBizLinkService.findLatestByBizKey(bizType, bizId).orElseThrow().getId();

        mockMvc.perform(get("/api/approval/tasks").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].level", Matchers.contains(1)))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].approvalId",
                        Matchers.contains(approvalId.intValue())));

        String agreeAliceBody = JacksonUtils.toJson(Map.of(
                "bizType", bizType,
                "bizId", bizId,
                "action", "AGREE",
                "comment", "同意"));
        mockMvc.perform(post("/api/approval/tasks/act")
                        .header("X-User-Id", "alice")
                        .contentType("application/json")
                        .content(agreeAliceBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/approval/records").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].bizType", Matchers.contains(bizType)))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].level", Matchers.contains(1)))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].taskTitle", Matchers.contains("审批")))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].action", Matchers.contains("AGREE")))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].comment", Matchers.contains("同意")))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].taskCreatedTime", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].actedTime", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].approvalId",
                        Matchers.contains(approvalId.intValue())));

        mockMvc.perform(get("/api/approval/instances/{approvalId}", approvalId).header("X-User-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.result.approvalId").value(approvalId))
                .andExpect(jsonPath("$.result.currentLevel").value(2))
                .andExpect(jsonPath("$.result.nodes[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.result.nodes[1].status").value("PENDING"));

        mockMvc.perform(get("/api/approval/instances/{approvalId}", approvalId).header("X-User-Id", "mallory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.error").value("无权查看该审批详情"))
                .andExpect(jsonPath("$.result").doesNotExist());

        mockMvc.perform(get("/api/approval/records").header("X-User-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')]", Matchers.hasSize(0)));

        mockMvc.perform(get("/api/approval/tasks").header("X-User-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].level", Matchers.contains(2)));

        String agreeBobBody = JacksonUtils.toJson(Map.of(
                "bizType", bizType,
                "bizId", bizId,
                "action", "AGREE",
                "comment", "同意"));
        mockMvc.perform(post("/api/approval/tasks/act")
                        .header("X-User-Id", "bob")
                        .contentType("application/json")
                        .content(agreeBobBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/approval/tasks").header("X-User-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')]", Matchers.hasSize(0)));
    }

    @Test
    void act_reject_stopsProcessImmediately() throws Exception {
        bizType = "TASK_CTRL_" + randomSuffix();
        saveChain(bizType, List.of("alice", "bob"));
        String bizId = "biz-" + UUID.randomUUID();

        approvalGateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));

        String rejectBody = JacksonUtils.toJson(Map.of(
                "bizType", bizType,
                "bizId", bizId,
                "action", "REJECT",
                "comment", "不同意"));
        mockMvc.perform(post("/api/approval/tasks/act")
                        .header("X-User-Id", "alice")
                        .contentType("application/json")
                        .content(rejectBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/approval/tasks").header("X-User-Id", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')]", Matchers.hasSize(0)));
    }

    @Test
    void listPendingTasks_withoutUserIdHeader_fallsBackToDefaultOperator() throws Exception {
        bizType = "TASK_CTRL_" + randomSuffix();
        saveChain(bizType, List.of(DefaultOperator.ID, "bob"));
        String bizId = "biz-" + UUID.randomUUID();

        approvalGateway.start(new StartApprovalCommand(bizType, bizId, "operator", "{}"));

        // 不携带 X-User-Id 请求头时，系统按默认管理员（DefaultOperator.ID）身份处理请求，
        // 接口正常返回成功结果，不因缺少操作人标识而报错（见 spec.md “请求头缺失时回退默认管理员”）。
        mockMvc.perform(get("/api/approval/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].level", Matchers.contains(1)));

        String agreeBody = JacksonUtils.toJson(Map.of(
                "bizType", bizType,
                "bizId", bizId,
                "action", "AGREE",
                "comment", "默认管理员同意"));
        mockMvc.perform(post("/api/approval/tasks/act")
                        .contentType("application/json")
                        .content(agreeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        mockMvc.perform(get("/api/approval/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].action", Matchers.contains("AGREE")))
                .andExpect(jsonPath("$.result[?(@.bizId == '" + bizId + "')].comment",
                        Matchers.contains("默认管理员同意")));
    }

    private String randomSuffix() {
        // biz_type 列为 varchar(32)，只取 UUID 前 8 位保证唯一性且不超长
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private void saveChain(String bizType, List<String> approvers) {
        LocalDateTime now = LocalDateTime.now();
        int level = 1;
        for (String approver : approvers) {
            ApprovalChainConfig entity = new ApprovalChainConfig();
            entity.setBizType(bizType);
            entity.setLevelNo(level++);
            entity.setApproverUserId(approver);
            entity.setCreatedTime(now);
            entity.setUpdatedTime(now);
            approvalChainConfigMapper.insert(entity);
        }
    }
}
