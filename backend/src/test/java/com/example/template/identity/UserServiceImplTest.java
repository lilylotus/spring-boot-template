package com.example.template.identity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.config.entity.ApprovalChainConfig;
import com.example.template.approval.config.mapper.ApprovalChainConfigMapper;
import com.example.template.approval.config.service.ApprovalConfigService;
import com.example.template.approval.link.service.ApprovalBizLinkService;
import com.example.template.common.BusinessException;
import com.example.template.identity.constant.UserBizType;
import com.example.template.identity.dto.UserApprovalPayloadSnapshot;
import com.example.template.identity.dto.UserCreateRequest;
import com.example.template.identity.dto.UserOperationResultVO;
import com.example.template.identity.dto.UserUpdateRequest;
import com.example.template.identity.dto.UserVO;
import com.example.template.identity.service.UserService;
import com.example.template.util.JacksonUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link com.example.template.identity.service.impl.UserServiceImpl} 的新增/编辑/查询/
 * 审批结果状态流转行为验证（tasks.md 6.3、6.5 对应的单测部分）。
 */
@SpringBootTest
@Transactional
@Rollback
class UserServiceImplTest {

    @Autowired
    private UserService userService;

    @Autowired
    private ApprovalConfigService approvalConfigService;

    @Autowired
    private ApprovalChainConfigMapper approvalChainConfigMapper;

    @Autowired
    private ApprovalBizLinkService approvalBizLinkService;

    @AfterEach
    void resetSwitch() {
        approvalConfigService.updateApprovalSwitch(false);
    }

    @Test
    void createUser_approvalDisabled_becomesActiveImmediately() {
        approvalConfigService.updateApprovalSwitch(false);

        UserOperationResultVO result = userService.createUser(newCreateRequest(), "tester");

        assertThat(result.isEffective()).isTrue();
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(userService.getUser(result.getUserId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void createUser_approvalEnabled_becomesPending_thenApprovalResultDrivesStatus_idempotently() {
        saveRequiredChains();
        approvalConfigService.updateApprovalSwitch(true);

        UserOperationResultVO result = userService.createUser(newCreateRequest(), "tester");
        assertThat(result.isEffective()).isFalse();
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(userService.getUser(result.getUserId()).getStatus()).isEqualTo("PENDING");
        String createPayloadJson = approvalBizLinkService
                .findLatestByBizKey(UserBizType.USER_CREATE, result.getUserId().toString())
                .orElseThrow()
                .getPayloadSnapshot();
        assertThat(createPayloadJson).contains("\"before\":null");
        UserApprovalPayloadSnapshot createPayload = JacksonUtils.toObj(
                createPayloadJson, UserApprovalPayloadSnapshot.class);
        assertThat(createPayload.before()).isNull();
        assertThat(createPayload.after().getUsername())
                .isEqualTo(userService.getUser(result.getUserId()).getUsername());
        assertThat(createPayload.after().getRealName()).isEqualTo("张三");

        userService.applyCreateApprovalResult(result.getUserId().toString(), true);
        assertThat(userService.getUser(result.getUserId()).getStatus()).isEqualTo("ACTIVE");

        // 重复消费同一（已通过）审批结果事件：状态已不是 PENDING，第二次调用应直接跳过，不产生副作用
        userService.applyCreateApprovalResult(result.getUserId().toString(), false);
        assertThat(userService.getUser(result.getUserId()).getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void createUser_rejectedApproval_marksUserRejected() {
        saveRequiredChains();
        approvalConfigService.updateApprovalSwitch(true);

        UserOperationResultVO result = userService.createUser(newCreateRequest(), "tester");
        userService.applyCreateApprovalResult(result.getUserId().toString(), false);

        assertThat(userService.getUser(result.getUserId()).getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void createUser_duplicateUsername_rejected() {
        approvalConfigService.updateApprovalSwitch(false);
        UserCreateRequest request = newCreateRequest();
        userService.createUser(request, "tester");

        assertThatThrownBy(() -> userService.createUser(request, "tester"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateUser_approvalEnabled_keepsOriginalInfo_andExposesPendingChangeInDetail() {
        approvalConfigService.updateApprovalSwitch(false);
        UserOperationResultVO created = userService.createUser(newCreateRequest(), "tester");

        saveRequiredChains();
        approvalConfigService.updateApprovalSwitch(true);

        UserUpdateRequest updateRequest = new UserUpdateRequest();
        updateRequest.setRealName("李四");
        updateRequest.setMobile("13900000000");
        updateRequest.setEmail("lisi@example.com");
        UserOperationResultVO updateResult = userService.updateUser(created.getUserId(), updateRequest, "tester");
        assertThat(updateResult.isEffective()).isFalse();

        UserVO detail = userService.getUser(created.getUserId());
        assertThat(detail.getRealName()).isEqualTo("张三"); // 审批通过前，对外仍展示原始信息
        assertThat(detail.getPendingChange()).isNotNull();
        assertThat(detail.getPendingChange().getNewRealName()).isEqualTo("李四");
        assertThat(detail.getPendingChange().getStatus()).isEqualTo("PENDING");
        String editBizId = String.valueOf(detail.getPendingChange().getId());
        String editPayloadJson = approvalBizLinkService.findLatestByBizKey(UserBizType.USER_EDIT, editBizId)
                .orElseThrow()
                .getPayloadSnapshot();
        UserApprovalPayloadSnapshot editPayload = JacksonUtils.toObj(
                editPayloadJson, UserApprovalPayloadSnapshot.class);
        assertThat(editPayload.before().getRealName()).isEqualTo("张三");
        assertThat(editPayload.before().getMobile()).isEqualTo("13800000000");
        assertThat(editPayload.after().getRealName()).isEqualTo("李四");
        assertThat(editPayload.after().getMobile()).isEqualTo("13900000000");

        // 同一用户存在进行中的编辑审批时，再次提交编辑应被拒绝
        assertThatThrownBy(() -> userService.updateUser(created.getUserId(), updateRequest, "tester"))
                .isInstanceOf(BusinessException.class);

        userService.applyEditApprovalResult(String.valueOf(detail.getPendingChange().getId()), true);
        UserVO afterApproved = userService.getUser(created.getUserId());
        assertThat(afterApproved.getRealName()).isEqualTo("李四");
        assertThat(afterApproved.getPendingChange()).isNull();
    }

    @Test
    void updateUser_userNotExist_rejected() {
        UserUpdateRequest updateRequest = new UserUpdateRequest();
        updateRequest.setRealName("李四");
        assertThatThrownBy(() -> userService.updateUser(-1L, updateRequest, "tester"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listUsers_includesCreatedUser() {
        approvalConfigService.updateApprovalSwitch(false);
        UserOperationResultVO created = userService.createUser(newCreateRequest(), "tester");

        List<UserVO> users = userService.listUsers();
        assertThat(users).extracting(UserVO::getId).contains(created.getUserId());
    }

    private UserCreateRequest newCreateRequest() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        request.setRealName("张三");
        request.setMobile("13800000000");
        request.setEmail("zhangsan@example.com");
        return request;
    }

    private void saveChain(String bizType, String approver) {
        approvalChainConfigMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ApprovalChainConfig>lambdaQuery()
                        .eq(ApprovalChainConfig::getBizType, bizType));
        ApprovalChainConfig config = new ApprovalChainConfig();
        config.setBizType(bizType);
        config.setLevelNo(1);
        config.setApproverUserId(approver);
        LocalDateTime now = LocalDateTime.now();
        config.setCreatedTime(now);
        config.setUpdatedTime(now);
        approvalChainConfigMapper.insert(config);
    }

    private void saveRequiredChains() {
        saveChain(UserBizType.USER_CREATE, "approver1");
        saveChain(UserBizType.USER_EDIT, "approver1");
    }
}
