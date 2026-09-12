package com.example.template.identity.service;

import com.example.template.identity.dto.UserCreateRequest;
import com.example.template.identity.dto.UserOperationResultVO;
import com.example.template.identity.dto.UserUpdateRequest;
import com.example.template.identity.dto.UserVO;

import java.util.List;

/**
 * 用户管理能力：新增、编辑、查询，以及审批结果驱动的状态流转。
 * <p>
 * 本接口及其实现只依赖 {@code approval.api} 里的 {@code ApprovalGateway}/{@code ApprovalPolicy}
 * 接口与 DTO，不依赖任何 {@code org.flowable.*} 类型。
 */
public interface UserService {

    /**
     * 新增用户。审批关闭时直接生效；审批开启时创建“待审批”用户并发起审批。
     */
    UserOperationResultVO createUser(UserCreateRequest request, String operatorId);

    /**
     * 编辑用户。审批关闭时直接生效；审批开启时记录待生效变更并发起审批，已生效信息保持不变。
     */
    UserOperationResultVO updateUser(Long userId, UserUpdateRequest request, String operatorId);

    /**
     * 查询用户列表（含处于待审批状态的用户）。
     */
    List<UserVO> listUsers();

    /**
     * 查询用户详情；若存在待审批中的编辑变更，一并返回。
     */
    UserVO getUser(Long userId);

    /**
     * 审批结果驱动“用户新增”状态流转：待审批 -> 已生效/已驳回。幂等：非待审批状态时直接跳过。
     *
     * @param userIdStr 审批发起时使用的 bizId（新增场景下就是用户ID的字符串形式）
     * @param approved  是否审批通过
     */
    void applyCreateApprovalResult(String userIdStr, boolean approved);

    /**
     * 审批结果驱动“用户编辑”状态流转：待审批变更 -> 已应用到用户/已驳回丢弃。幂等：非待审批状态时直接跳过。
     *
     * @param pendingChangeIdStr 审批发起时使用的 bizId（编辑场景下是 user_pending_change 记录ID的字符串形式）
     * @param approved           是否审批通过
     */
    void applyEditApprovalResult(String pendingChangeIdStr, boolean approved);
}
