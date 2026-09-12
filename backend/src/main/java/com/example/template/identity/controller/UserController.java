package com.example.template.identity.controller;

import com.example.template.common.RestResult;
import com.example.template.identity.dto.UserCreateRequest;
import com.example.template.identity.dto.UserOperationResultVO;
import com.example.template.identity.dto.UserUpdateRequest;
import com.example.template.identity.dto.UserVO;
import com.example.template.identity.service.UserService;
import com.example.template.operator.CurrentOperator;
import com.example.template.operator.OperatorContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理接口：新增、编辑、列表、详情。Controller 只做参数接收、校验触发、调用 Service、
 * 返回结果，不写业务逻辑，只注入 {@link UserService} 接口。
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping("/api/users")
    public RestResult<UserOperationResultVO> createUser(@Valid @RequestBody UserCreateRequest request,
                                                          @CurrentOperator OperatorContext operator) {
        return RestResult.success(userService.createUser(request, operator.userId()));
    }

    @PutMapping("/api/users/{id}")
    public RestResult<UserOperationResultVO> updateUser(@PathVariable Long id,
                                                          @Valid @RequestBody UserUpdateRequest request,
                                                          @CurrentOperator OperatorContext operator) {
        return RestResult.success(userService.updateUser(id, request, operator.userId()));
    }

    @GetMapping("/api/users")
    public RestResult<List<UserVO>> listUsers() {
        return RestResult.success(userService.listUsers());
    }

    @GetMapping("/api/users/{id}")
    public RestResult<UserVO> getUser(@PathVariable Long id) {
        return RestResult.success(userService.getUser(id));
    }
}
