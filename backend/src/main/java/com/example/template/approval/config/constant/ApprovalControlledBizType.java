package com.example.template.approval.config.constant;

/**
 * 受全局审批开关控制的业务类型。
 * <p>
 * 该枚举由审批配置模块自行维护，避免审批模块反向依赖具体业务模块。
 */
public enum ApprovalControlledBizType {

    /** 用户新增审批。 */
    USER_CREATE("用户新增"),

    /** 用户编辑审批。 */
    USER_EDIT("用户编辑");

    private final String displayName;

    ApprovalControlledBizType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取用于业务错误提示的名称。
     *
     * @return 业务类型中文名称
     */
    public String getDisplayName() {
        return displayName;
    }
}
