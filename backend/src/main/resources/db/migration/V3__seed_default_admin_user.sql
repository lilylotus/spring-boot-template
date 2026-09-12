-- 默认管理员种子用户：作为 operator-header-identity 变更中「请求头缺失 X-User-Id 时」的回退操作人。
-- 固定 id=1，与 com.example.template.operator.DefaultOperator 中的常量（ID="1", NAME="默认管理员"）保持一致，
-- 该迁移脚本需在业务侧产生任何新增用户请求之前执行，以保证自增主键从 1 开始拿到确定的 id。
INSERT INTO sys_user (id, username, real_name, status, created_time, updated_time)
VALUES (1, 'admin', '默认管理员', 'ACTIVE', NOW(), NOW());
