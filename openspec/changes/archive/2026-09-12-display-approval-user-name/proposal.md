## Why

审批中心“待我审批”和“审批记录”当前把用户业务单据 ID 直接显示为“业务标识”，审批人无法快速判断本次审批对应哪位用户。页面已有用户列表数据，应优先展示用户姓名以提升可读性。

## What Changes

- 两个审批列表中的“业务标识”列改为“审批用户”。
- 对 `USER_CREATE`、`USER_EDIT` 类型，按 `bizId` 匹配用户列表并展示 `realName`。
- 用户不存在、用户列表加载失败或遇到未知业务类型时回退显示原 `bizId`，保证历史数据仍可识别。
- 不修改后端接口、审批关联键、数据库字段或审批详情。

## Capabilities

### Modified Capabilities

- `approval/approval-center`：待审批列表与审批记录以用户姓名展示用户类业务标识。

## Impact

仅修改 `fronted/src/views/ApprovalCenterView.vue` 及对应规格；复用页面已有的 `listUsers()` 数据，不新增请求和依赖。
