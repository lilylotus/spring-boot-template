package com.example.template.operator;

/**
 * 从请求头解析出的当前操作人上下文。{@code userId} 参与业务判断（如作为
 * 新增/编辑用户的操作人标识、审批人标识），{@code userName} 仅用于展示/审计，
 * 不参与任何业务判断（见 operator-header-identity 变更 spec.md）。
 *
 * @param userId   操作人用户 ID
 * @param userName 操作人姓名，可能为 {@code null}
 */
public record OperatorContext(String userId, String userName) {
}
