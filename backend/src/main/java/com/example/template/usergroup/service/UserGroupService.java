package com.example.template.usergroup.service;
import com.example.template.usergroup.entity.*;
import com.example.template.usergroup.mapper.*;
import com.example.template.usergroup.dto.*;

import java.time.LocalDateTime;
import java.util.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.template.common.BusinessException;
import com.example.template.identity.entity.SysUser;
import com.example.template.identity.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 维护用户组及实时成员关系；审批网关通过本服务读取成员并锁定组配置。 */
@Service
@RequiredArgsConstructor
public class UserGroupService {
    private final UserGroupMapper groups;
    private final UserGroupMemberMapper members;
    private final SysUserMapper users;

    /** 查询全部用户组及当前成员，供管理页面和设计器使用。 */
    public List<UserGroupView> list() {
        return groups.selectList(Wrappers.<UserGroup>lambdaQuery().orderByAsc(UserGroup::getId))
                .stream().map(this::view).toList();
    }

    /** 查询用户组；不存在时抛出业务异常，停用组仍可用于历史展示。 */
    public UserGroupView get(Long id) {
        UserGroup group = groups.selectById(id);
        if (group == null) {
            throw new BusinessException("用户组不存在");
        }
        return view(group);
    }

    /** 在调用方事务内锁定组，保证成员修改与审批结算使用同一顺序。 */
    public void lock(Long id) {
        if (groups.selectOne(Wrappers.<UserGroup>lambdaQuery().eq(UserGroup::getId, id)
                .last("FOR UPDATE")) == null) {
            throw new BusinessException("用户组不存在");
        }
    }

    /** 查询最新有效成员；停用或不存在的组返回空集合，绝不复用实例成员缓存。 */
    public List<String> activeMembers(Long id) {
        UserGroup group = groups.selectById(id);
        return group == null ? List.of() : view(group).activeMemberIds();
    }

    /** 发布和启动前检查有效非空组，防止创建无人可审批的新实例。 */
    public void requireAvailable(Long id) {
        if (activeMembers(id).isEmpty()) {
            throw new BusinessException("用户组已停用或没有有效成员：" + id);
        }
    }

    /** 创建或更新用户组及成员；校验失败回滚全部修改，编码创建后不可更改。 */
    @Transactional
    public UserGroupView save(Long id, UserGroupRequest request, String operator) {
        UserGroup group;
        if (id == null) {
            if (request.revision() != 0) {
                throw new BusinessException("新增用户组版本必须为0");
            }
            group = new UserGroup();
            group.setCode(request.code().trim());
            group.setCreatedBy(operator);
            group.setCreatedTime(LocalDateTime.now());
            group.setRevision(0L);
        } else {
            lock(id);
            group = groups.selectById(id);
            if (!group.getRevision().equals(request.revision())) {
                throw new BusinessException("用户组已被修改，请刷新后重试");
            }
            if (!group.getCode().equals(request.code())) {
                throw new BusinessException("用户组编码不可修改");
            }
        }
        Set<Long> requested = new HashSet<>(request.memberIds());
        if (requested.size() != request.memberIds().size()) {
            throw new BusinessException("用户组成员不能重复");
        }
        if (!requested.isEmpty()) {
            List<SysUser> selected = users.selectList(Wrappers.<SysUser>lambdaQuery()
                    .in(SysUser::getId, requested).eq(SysUser::getStatus, "ACTIVE"));
            if (selected.size() != requested.size()) {
                throw new BusinessException("用户组成员必须是有效用户");
            }
        }
        group.setName(request.name().trim());
        group.setDescription(request.description());
        group.setStatus(request.status());
        group.setRevision(group.getRevision() + 1);
        group.setUpdatedBy(operator);
        group.setUpdatedTime(LocalDateTime.now());
        try {
            if (id == null) {
                groups.insert(group);
            } else {
                groups.updateById(group);
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("用户组编码已存在");
        }
        // 组锁覆盖成员整体替换；审批提交取得相同组锁后才读取和结算。
        members.delete(Wrappers.<UserGroupMember>lambdaQuery().eq(UserGroupMember::getGroupId, group.getId()));
        for (Long userId : requested) {
            UserGroupMember member = new UserGroupMember();
            member.setGroupId(group.getId());
            member.setUserId(userId);
            member.setCreatedBy(operator);
            member.setUpdatedBy(operator);
            member.setCreatedTime(LocalDateTime.now());
            member.setUpdatedTime(LocalDateTime.now());
            members.insert(member);
        }
        return get(group.getId());
    }

    /** 组装当前成员视图，有效性依据用户表实时状态与组状态共同判断。 */
    private UserGroupView view(UserGroup group) {
        List<Long> ids = members.selectList(Wrappers.<UserGroupMember>lambdaQuery()
                .eq(UserGroupMember::getGroupId, group.getId()).orderByAsc(UserGroupMember::getUserId))
                .stream().map(UserGroupMember::getUserId).toList();
        List<String> active = List.of();
        if ("ACTIVE".equals(group.getStatus()) && !ids.isEmpty()) {
            active = users.selectList(Wrappers.<SysUser>lambdaQuery()
                    .in(SysUser::getId, ids).eq(SysUser::getStatus, "ACTIVE").orderByAsc(SysUser::getId))
                    .stream().map(user -> String.valueOf(user.getId())).toList();
        }
        return new UserGroupView(group.getId(), group.getCode(), group.getName(), group.getDescription(),
                group.getStatus(), group.getRevision(), ids, active);
    }
}
