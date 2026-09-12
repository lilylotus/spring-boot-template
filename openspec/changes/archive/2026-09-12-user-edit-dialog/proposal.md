## Why

用户新增已经改为列表页内弹窗，但编辑用户仍会跳转到独立的 `/users/:id/edit` 页面，两个相近操作的交互方式不一致。编辑完成后用户同样需要回到列表查看最新数据和审批状态，页面跳转会中断当前列表上下文。

因此，将编辑用户也改为列表页内的弹出窗口，与新增用户形成一致、连续的操作体验。

## What Changes

- 用户列表行点击“编辑”时，不再跳转独立页面，改为打开 Element Plus 编辑用户对话框。
- 编辑弹窗根据所选用户 ID 加载详情，账号只读，姓名、手机号、邮箱可编辑。
- 加载详情时显示加载状态；加载失败时关闭弹窗并保留列表页上下文。
- 编辑成功后关闭弹窗、提示“已生效”或“已提交审批”，并刷新用户列表。
- 编辑失败时保留弹窗和用户已修改的内容，供用户调整后重试。
- 关闭弹窗后清空表单和校验状态，切换编辑对象时不显示上一位用户的数据。
- 移除不再使用的 `/users/:id/edit` 路由和 `UserFormView.vue` 页面。
- 不修改新增用户弹窗、后端 API、请求字段或审批流程。

## Capabilities

### Modified Capabilities

- `identity/user-management`: 用户编辑的前端交互由独立页面切换为用户列表页内的弹出窗口，后端编辑与审批行为保持不变。

## Impact

- 影响范围仅为 `fronted/` 与本变更 OpenSpec 文档。
- 新增 `src/components/UserEditDialog.vue`，修改 `src/views/UserListView.vue` 和 `src/router/index.ts`，删除不再使用的 `src/views/UserFormView.vue`。
- 不修改 `backend/`，不新增依赖，不改变现有 API 契约。

