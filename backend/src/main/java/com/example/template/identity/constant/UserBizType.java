package com.example.template.identity.constant;

/**
 * 用户管理模块提交给审批能力的业务动作类型常量，作为 {@code ApprovalGateway}/{@code ApprovalPolicy}
 * 调用时的 {@code bizType} 取值。
 */
public final class UserBizType {

    /** 用户新增。 */
    public static final String USER_CREATE = "USER_CREATE";

    /** 用户编辑。 */
    public static final String USER_EDIT = "USER_EDIT";

    private UserBizType() {
    }
}
