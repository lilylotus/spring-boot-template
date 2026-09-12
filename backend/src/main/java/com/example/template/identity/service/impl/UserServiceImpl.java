package com.example.template.identity.service.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.template.approval.api.ApprovalGateway;
import com.example.template.approval.api.ApprovalPolicy;
import com.example.template.approval.api.dto.ApprovalInstanceView;
import com.example.template.approval.api.dto.ApprovalStatus;
import com.example.template.approval.api.dto.StartApprovalCommand;
import com.example.template.common.BusinessException;
import com.example.template.identity.constant.UserBizType;
import com.example.template.identity.dto.UserApprovalPayloadSnapshot;
import com.example.template.identity.dto.UserCreateRequest;
import com.example.template.identity.dto.UserFieldsSnapshot;
import com.example.template.identity.dto.UserOperationResultVO;
import com.example.template.identity.dto.UserPendingChangeVO;
import com.example.template.identity.dto.UserUpdateRequest;
import com.example.template.identity.dto.UserVO;
import com.example.template.identity.entity.SysUser;
import com.example.template.identity.entity.UserPendingChange;
import com.example.template.identity.enums.PendingChangeStatus;
import com.example.template.identity.enums.UserStatus;
import com.example.template.identity.mapper.SysUserMapper;
import com.example.template.identity.mapper.UserPendingChangeMapper;
import com.example.template.identity.service.UserService;
import com.example.template.util.JacksonUtils;

/**
 * {@link UserService} 实现。只依赖 {@code approval.api} 的 {@link ApprovalPolicy}/
 * {@link ApprovalGateway} 接口判断是否需要审批、发起审批，不知道、也不需要知道背后是 Flowable。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final UserPendingChangeMapper userPendingChangeMapper;
    private final ApprovalPolicy approvalPolicy;
    private final ApprovalGateway approvalGateway;

    @Override
    @Transactional
    public UserOperationResultVO createUser(UserCreateRequest request, String operatorId) {
        boolean usernameExists = sysUserMapper.exists(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, request.getUsername()));
        if (usernameExists) {
            throw new BusinessException("账号已存在：" + request.getUsername());
        }

        LocalDateTime now = LocalDateTime.now();
        boolean approvalEnabled = approvalPolicy.isEnabled(UserBizType.USER_CREATE);

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setRealName(request.getRealName());
        user.setMobile(request.getMobile());
        user.setEmail(request.getEmail());
        user.setStatus((approvalEnabled ? UserStatus.PENDING : UserStatus.ACTIVE).name());
        user.setCreatedTime(now);
        user.setUpdatedTime(now);
        sysUserMapper.insert(user);

        if (!approvalEnabled) {
            return new UserOperationResultVO(user.getId(), UserStatus.ACTIVE.name(), true, "新增成功，已生效");
        }

        String bizId = String.valueOf(user.getId());
        rejectIfAlreadyPending(UserBizType.USER_CREATE, bizId);

        UserFieldsSnapshot after = new UserFieldsSnapshot(
                user.getUsername(), user.getRealName(), user.getMobile(), user.getEmail());
        String snapshot = JacksonUtils.toJson(new UserApprovalPayloadSnapshot(null, after));
        approvalGateway.start(new StartApprovalCommand(UserBizType.USER_CREATE, bizId, operatorId, snapshot));

        return new UserOperationResultVO(user.getId(), UserStatus.PENDING.name(), false, "新增已提交审批，审批通过后生效");
    }

    @Override
    @Transactional
    public UserOperationResultVO updateUser(Long userId, UserUpdateRequest request, String operatorId) {
        SysUser existingUser = sysUserMapper.selectById(userId);
        if (existingUser == null) {
            throw new BusinessException("用户不存在：" + userId);
        }

        boolean approvalEnabled = approvalPolicy.isEnabled(UserBizType.USER_EDIT);
        if (!approvalEnabled) {
            existingUser.setRealName(request.getRealName());
            existingUser.setMobile(request.getMobile());
            existingUser.setEmail(request.getEmail());
            existingUser.setUpdatedTime(LocalDateTime.now());
            sysUserMapper.updateById(existingUser);
            return new UserOperationResultVO(userId, UserStatus.ACTIVE.name(), true, "编辑成功，已生效");
        }

        // 同一时刻只允许一条待审批变更：在生成新的 user_pending_change 记录前，
        // 先用 identity 自己的表校验该用户当前是否已有进行中的编辑审批，避免并发重复提交。
        boolean hasPendingChange = userPendingChangeMapper.exists(
                Wrappers.<UserPendingChange>lambdaQuery()
                        .eq(UserPendingChange::getUserId, userId)
                        .eq(UserPendingChange::getStatus, PendingChangeStatus.PENDING.name()));
        if (hasPendingChange) {
            throw new BusinessException("该用户已存在进行中的编辑审批，请勿重复提交");
        }

        LocalDateTime now = LocalDateTime.now();
        UserFieldsSnapshot before = new UserFieldsSnapshot(existingUser.getUsername(), existingUser.getRealName(),
                existingUser.getMobile(), existingUser.getEmail());
        UserFieldsSnapshot after = new UserFieldsSnapshot(existingUser.getUsername(), request.getRealName(),
                request.getMobile(), request.getEmail());

        UserPendingChange change = new UserPendingChange();
        change.setUserId(userId);
        change.setBeforeSnapshot(JacksonUtils.toJson(before));
        change.setAfterSnapshot(JacksonUtils.toJson(after));
        change.setStatus(PendingChangeStatus.PENDING.name());
        change.setOperatorId(operatorId);
        change.setCreatedTime(now);
        change.setUpdatedTime(now);
        userPendingChangeMapper.insert(change);

        String bizId = String.valueOf(change.getId());
        rejectIfAlreadyPending(UserBizType.USER_EDIT, bizId);

        approvalGateway.start(new StartApprovalCommand(UserBizType.USER_EDIT, bizId, operatorId,
                JacksonUtils.toJson(new UserApprovalPayloadSnapshot(before, after))));

        return new UserOperationResultVO(userId, UserStatus.ACTIVE.name(), false, "编辑已提交审批，审批通过后生效");
    }

    @Override
    public List<UserVO> listUsers() {
        return sysUserMapper.selectList(null).stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public UserVO getUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在：" + userId);
        }
        UserVO vo = toVO(user);

        UserPendingChange pendingChange = userPendingChangeMapper.selectOne(
                Wrappers.<UserPendingChange>lambdaQuery()
                        .eq(UserPendingChange::getUserId, userId)
                        .eq(UserPendingChange::getStatus, PendingChangeStatus.PENDING.name())
                        .orderByDesc(UserPendingChange::getId)
                        .last("LIMIT 1"));
        if (pendingChange != null) {
            UserFieldsSnapshot after = JacksonUtils.toObj(pendingChange.getAfterSnapshot(), UserFieldsSnapshot.class);
            UserPendingChangeVO pendingChangeVO = new UserPendingChangeVO();
            pendingChangeVO.setId(pendingChange.getId());
            pendingChangeVO.setStatus(pendingChange.getStatus());
            pendingChangeVO.setNewRealName(after.getRealName());
            pendingChangeVO.setNewMobile(after.getMobile());
            pendingChangeVO.setNewEmail(after.getEmail());
            pendingChangeVO.setCreatedTime(pendingChange.getCreatedTime());
            vo.setPendingChange(pendingChangeVO);
        }
        return vo;
    }

    @Override
    @Transactional
    public void applyCreateApprovalResult(String userIdStr, boolean approved) {
        Long userId = Long.valueOf(userIdStr);
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || !UserStatus.PENDING.name().equals(user.getStatus())) {
            // 幂等：用户不存在，或当前已不是待审批状态（重复消费同一事件），直接跳过
            return;
        }
        user.setStatus((approved ? UserStatus.ACTIVE : UserStatus.REJECTED).name());
        user.setUpdatedTime(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void applyEditApprovalResult(String pendingChangeIdStr, boolean approved) {
        Long changeId = Long.valueOf(pendingChangeIdStr);
        UserPendingChange change = userPendingChangeMapper.selectById(changeId);
        if (change == null || !PendingChangeStatus.PENDING.name().equals(change.getStatus())) {
            // 幂等：变更记录不存在，或当前已不是待审批状态（重复消费同一事件），直接跳过
            return;
        }
        change.setStatus((approved ? PendingChangeStatus.APPROVED : PendingChangeStatus.REJECTED).name());
        change.setUpdatedTime(LocalDateTime.now());
        userPendingChangeMapper.updateById(change);

        if (approved) {
            SysUser existing = sysUserMapper.selectById(change.getUserId());
            if (existing != null) {
                UserFieldsSnapshot after = JacksonUtils.toObj(change.getAfterSnapshot(), UserFieldsSnapshot.class);
                existing.setRealName(after.getRealName());
                existing.setMobile(after.getMobile());
                existing.setEmail(after.getEmail());
                existing.setUpdatedTime(LocalDateTime.now());
                sysUserMapper.updateById(existing);
            }
        }
    }

    /**
     * 发起审批前的防腐层侧幂等检查：按 design.md D3 描述，用刚生成的 bizId 调一次
     * {@code queryByBizKey}，若已存在进行中的审批则拒绝——这是针对同一 bizId 重复发起
     * 的防御性校验；同一用户层面的并发重复提交由调用方在生成 bizId 之前用 identity
     * 自己的表提前拦截（新增：账号唯一性；编辑：见 {@link #updateUser}）。
     */
    private void rejectIfAlreadyPending(String bizType, String bizId) {
        ApprovalInstanceView existing = approvalGateway.queryByBizKey(bizType, bizId);
        if (existing != null && existing.status() == ApprovalStatus.PENDING) {
            throw new BusinessException("已存在进行中的审批，请勿重复提交");
        }
    }

    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setMobile(user.getMobile());
        vo.setEmail(user.getEmail());
        vo.setStatus(user.getStatus());
        return vo;
    }
}
