## Why

`user-management-approval` 变更落地时，`UserController`/`ApprovalTaskController` 的操作人/审批人标识全部依赖硬编码占位符（`UserController.PLACEHOLDER_OPERATOR_ID = "system"`）或由前端在请求体/路径参数里显式传入（`ApprovalTaskController` 的 `approverUserId`），项目尚未接入登录鉴权模块。为了让联调、演示和后续人工验收能区分"谁在操作"，需要一个轻量的操作人识别机制：后端从请求头读取，前端提供一个可切换"当前操作人"的入口，在正式接入登录鉴权之前作为过渡方案。

## What Changes

- 后端新增操作人上下文解析能力：从请求头 `X-User-Id`（用户 ID）/`X-User-Name`（用户姓名，仅透传展示，不参与业务判断）解析当前操作人；`UserController`（新增/编辑用户）与 `ApprovalTaskController`（查询待办、提交同意/驳回）改为使用解析出的操作人 ID，不再使用固定占位符或要求前端在请求体中显式传 `approverUserId`。
- 新增一个真实存在于 `sys_user` 表的"默认管理员"种子用户（Flyway 迁移插入），作为请求头缺失 `X-User-Id` 时的回退操作人，前端下拉列表中也会展示这个用户。
- 后端行为：请求头 `X-User-Id` 缺失（如直接用 curl/Postman 调接口、不经过前端）时，直接回退为默认管理员，不报错、不拒绝请求。
- 前端新增全局"当前操作人"选择器（页面左上角常驻下拉框），数据源为已有的用户列表接口（`GET /api/users`）；首次访问或未手动选择时默认选中"默认管理员"；每次展开下拉框时立即刷新选项，使刚新增的用户无需刷新页面即可被选择；选择后后续所有请求自动携带 `X-User-Id`/`X-User-Name` 请求头，选择结果只保存在浏览器本地（不持久化到后端），刷新后保留、清除浏览器数据后回到默认值。
- `ApprovalTaskController` 的 `POST /api/approval/tasks/act` 请求体不再要求前端传 `approverUserId` 字段（改为从请求头解析），`GET /api/approval/tasks/{approverUserId}` 路径参数同步移除，改为无参查询"当前操作人"的待办列表。

## Capabilities

### New Capabilities
- `identity/operator-identity`：从请求头解析当前操作人（含默认管理员回退）的能力，供 `identity`/`approval` 等业务模块的 Controller 层统一使用；前端对应的"当前操作人选择器"全局交互能力。

### Modified Capabilities
（无。`identity/user-management`、`approval/user-approval-workflow` 两个能力目前只存在于尚未归档的 `user-management-approval` 变更中，`openspec/specs/` 下没有已归档的主规格可供标记"修改"；本次对 `UserController`/`ApprovalTaskController` 的调用方式调整作为新能力 `identity/operator-identity` 的消费方在其自身 spec 中说明，不改动 `user-management-approval` 变更下尚未归档的 delta spec 文件。）

## Impact

- 新增代码：`backend/src/main/java/com/example/template` 下新增操作人上下文解析组件（如一个从 `HttpServletRequest` 读取请求头、解析失败/缺失时回退默认管理员的工具类或拦截器/参数解析器），供 `UserController`、`ApprovalTaskController` 使用。
- 修改代码：`UserController`（不再用 `PLACEHOLDER_OPERATOR_ID`）、`ApprovalTaskController`（`act`/`listPendingTasks` 改为从操作人上下文取值，移除 `approverUserId` 请求体字段与路径参数）。
- 新增数据库迁移：Flyway 脚本插入一条默认管理员种子用户到 `sys_user` 表。
- 前端：`fronted/` 新增全局"当前操作人"选择器组件（布局层面，非某个业务页面内部），`src/api/http.ts` 的 Axios 请求拦截器新增自动携带 `X-User-Id`/`X-User-Name` 请求头的逻辑；`src/api/user.ts` 中依赖 `approverUserId` 的调用方式相应调整（如涉及）。
- 不涉及：真实登录鉴权、Token/Session、权限校验——本次仍是无鉴权的过渡方案，`X-User-Id` 由前端自行携带，不做防篡改校验。
