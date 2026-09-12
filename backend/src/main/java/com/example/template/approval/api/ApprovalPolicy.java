package com.example.template.approval.api;

/**
 * 审批策略：判断某个业务动作当前是否需要经过审批。
 * <p>
 * 当前实现是单一全局总开关，接口签名保留 {@code bizType} 参数是为将来可能按业务类型拆分开关预留扩展点，
 * 调用方（如 {@code UserService}）不需要因为开关粒度变化而改动调用方式。
 */
public interface ApprovalPolicy {

    /**
     * 判断指定业务动作当前是否需要经过审批。
     *
     * @param bizType 业务动作类型，如 {@code USER_CREATE}、{@code USER_EDIT}
     * @return {@code true} 表示需要经过审批，{@code false} 表示直接生效
     */
    boolean isEnabled(String bizType);
}
