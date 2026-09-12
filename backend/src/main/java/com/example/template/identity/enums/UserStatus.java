package com.example.template.identity.enums;

/**
 * 用户主数据的状态。
 */
public enum UserStatus {

    /** 待审批：审批开启场景下新增/编辑期间的中间状态，不具备已生效用户的能力。 */
    PENDING,

    /** 已生效。 */
    ACTIVE,

    /** 已驳回：新增被驳回，不作为已生效用户存在。 */
    REJECTED
}
