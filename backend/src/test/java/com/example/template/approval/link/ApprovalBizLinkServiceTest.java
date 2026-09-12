package com.example.template.approval.link;

import com.example.template.approval.link.entity.ApprovalBizLink;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link ApprovalBizLinkService} 的基本读写：新增、按业务键查询、按流程实例ID查询、状态更新。
 */
@SpringBootTest
@Transactional
@Rollback
class ApprovalBizLinkServiceTest {

    @Autowired
    private ApprovalBizLinkService approvalBizLinkService;

    @Test
    void save_thenQueryByBizKeyAndProcessInstanceId_thenUpdateStatus() {
        String bizId = UUID.randomUUID().toString();
        String processInstanceId = "pi-" + UUID.randomUUID();

        ApprovalBizLink link = new ApprovalBizLink();
        link.setBizType("USER_CREATE");
        link.setBizId(bizId);
        link.setProcessInstanceId(processInstanceId);
        link.setStatus("PENDING");
        link.setInitiatorUserId("operator");
        link.setApproverSnapshot("[\"alice\",\"bob\"]");
        link.setPayloadSnapshot("{\"field\":\"value\"}");
        ApprovalBizLink saved = approvalBizLinkService.save(link);
        assertThat(saved.getId()).isNotNull();

        Optional<ApprovalBizLink> byBizKey = approvalBizLinkService.findLatestByBizKey("USER_CREATE", bizId);
        assertThat(byBizKey).isPresent();
        assertThat(byBizKey.get().getProcessInstanceId()).isEqualTo(processInstanceId);

        Optional<ApprovalBizLink> byProcessInstance = approvalBizLinkService.findByProcessInstanceId(processInstanceId);
        assertThat(byProcessInstance).isPresent();
        assertThat(byProcessInstance.get().getBizId()).isEqualTo(bizId);

        ApprovalBizLink byId = approvalBizLinkService.findById(saved.getId()).orElseThrow();
        assertThat(byId.getInitiatorUserId()).isEqualTo("operator");
        assertThat(byId.getApproverSnapshot()).isEqualTo("[\"alice\",\"bob\"]");
        assertThat(byId.getPayloadSnapshot()).isEqualTo("{\"field\":\"value\"}");

        approvalBizLinkService.updateStatus(saved.getId(), "APPROVED");
        Optional<ApprovalBizLink> reloaded = approvalBizLinkService.findLatestByBizKey("USER_CREATE", bizId);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void findLatestByBizKey_returnsEmpty_whenNotExists() {
        assertThat(approvalBizLinkService.findLatestByBizKey("USER_CREATE", "not-exist-" + UUID.randomUUID()))
                .isEmpty();
    }
}
