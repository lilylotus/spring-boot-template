package com.example.template.approval.config.design;

/** 审批域内部组指派编码，供顺序执行计划引用组ID；从不包含组成员。 */
public final class GroupAssignment {
    private static final String PREFIX = "group:";

    /** 禁止实例化无状态的编码工具。 */
    private GroupAssignment() {
    }

    /** 将组ID编码为内部执行计划中的指派目标。 */
    public static String encode(Long id) {
        return PREFIX + id;
    }

    /** 判断指派目标是否为用户组；个人审批ID不允许占用此保留前缀。 */
    public static boolean isGroup(String assignment) {
        return assignment != null && assignment.startsWith(PREFIX);
    }

    /** 从已验证的组指派目标读取组ID。 */
    public static Long id(String assignment) {
        return Long.valueOf(assignment.substring(PREFIX.length()));
    }
}
