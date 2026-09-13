package com.example.template.usergroup.controller;
import com.example.template.usergroup.service.UserGroupService;
import com.example.template.usergroup.dto.*;

import java.util.List;
import com.example.template.common.RestResult;
import com.example.template.operator.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 用户组管理 HTTP 入口，只负责校验和转调服务，不修改现有用户业务接口。 */
@RestController
@RequiredArgsConstructor
public class UserGroupController {
    private final UserGroupService service;

    /** 返回用户组及最新成员，供管理列表和审批设计器使用。 */
    @GetMapping("/api/user-groups")
    public RestResult<List<UserGroupView>> list() {
        return RestResult.success(service.list());
    }

    /** 创建用户组与初始成员，创建人来自当前操作人上下文。 */
    @PostMapping("/api/user-groups")
    public RestResult<UserGroupView> create(@Valid @RequestBody UserGroupRequest request,
                                           @CurrentOperator OperatorContext operator) {
        return RestResult.success(service.save(null, request, operator.userId()));
    }

    /** 编辑组信息、停用状态或成员，使用客户端版本阻止覆盖他人修改。 */
    @PutMapping("/api/user-groups/{id}")
    public RestResult<UserGroupView> update(@PathVariable Long id, @Valid @RequestBody UserGroupRequest request,
                                           @CurrentOperator OperatorContext operator) {
        return RestResult.success(service.save(id, request, operator.userId()));
    }
}
