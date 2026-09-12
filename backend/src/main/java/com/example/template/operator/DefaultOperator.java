package com.example.template.operator;

/**
 * 默认管理员操作人常量：当请求未携带 {@code X-User-Id} 请求头时，
 * {@link CurrentOperatorArgumentResolver} 直接回退到这两个固定常量，不查询数据库
 * （见 operator-header-identity 变更 design.md D2）。
 *
 * <p>与 Flyway 迁移脚本 {@code V3__seed_default_admin_user.sql} 插入的
 * {@code sys_user} 种子行（{@code id=1, username='admin', real_name='默认管理员'}）
 * 保持一致，两处需同步维护。</p>
 */
public final class DefaultOperator {

    /** 默认管理员的用户 ID，与 {@code sys_user} 种子行的 id 保持一致。 */
    public static final String ID = "1";

    /** 默认管理员的姓名，与 {@code sys_user} 种子行的 real_name 保持一致。 */
    public static final String NAME = "默认管理员";

    private DefaultOperator() {
    }
}
