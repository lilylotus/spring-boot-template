## 1. 审批动作审计存储

- [x] 1.1 新增 `V4__create_approval_action_record.sql`，创建审批动作记录表及 `task_id` 唯一索引、`approver_user_id + acted_time` 查询索引。
- [x] 1.2 在 `approval` 包新增审批动作记录 Entity、Mapper、Service，提供事务内保存与按审批人、处理时间倒序查询能力。
- [x] 1.3 编写最小数据访问测试，验证动作记录保存、唯一约束和按审批人查询排序。

## 2. 审批防腐层与接口

- [x] 2.1 在 `approval.api.dto` 新增 `ApprovalRecordView`，确认不依赖 `org.flowable.*`。
- [x] 2.2 扩展 `ApprovalGateway`，新增 `listApprovalRecords(String approverUserId)` 查询契约。
- [x] 2.3 修改 `FlowableApprovalGateway.act`，在同一事务中记录已校验任务的业务标识、任务信息、审批人、级别、动作与意见，再完成 Flowable 任务。
- [x] 2.4 实现 `FlowableApprovalGateway.listApprovalRecords`，通过审批动作记录 Service 查询并转换为 `ApprovalRecordView`。
- [x] 2.5 扩展集成测试，验证同意/驳回都会形成正确记录，非当前审批人或失败操作不产生记录，流程失败时记录回滚。

## 3. 审批记录 HTTP API

- [x] 3.1 在 `ApprovalTaskController` 新增 `GET /api/approval/records`，使用 `@CurrentOperator` 查询当前操作人的审批记录并返回统一 `RestResult`。
- [x] 3.2 扩展 `ApprovalTaskControllerTest`，验证请求头操作人隔离、默认管理员回退及返回字段。
- [x] 3.3 运行后端测试与构建，确认现有用户管理、审批流转和操作人机制没有回归。

## 4. 前端审批 API

- [x] 4.1 新增 `src/types/approval.ts`，定义待办、审批记录、审批动作和请求体类型，与后端 DTO 保持一致。
- [x] 4.2 新增 `src/api/approval.ts`，封装 `listPendingApprovalTasks`、`listApprovalRecords`、`actOnApprovalTask`。

## 5. 前端审批中心页面

- [x] 5.1 新增 `ApprovalActionDialog.vue`，支持同意/驳回确认与可选意见；提交中阻止重复操作，失败时保留输入。
- [x] 5.2 新增 `ApprovalCenterView.vue`，以“待我审批”“审批记录”页签展示两类列表，包含业务类型映射、结果标签、时间和空状态。
- [x] 5.3 监听当前操作人变化并刷新两类列表，使用请求序号避免快速切换时旧响应覆盖新操作人的数据。
- [x] 5.4 审批成功后关闭操作弹窗，同时刷新待办与审批记录；列表加载失败时由统一 HTTP 层提示。

## 6. 导航与路由

- [x] 6.1 在路由中新增 `/approvals` 审批中心页面。
- [x] 6.2 修改 `App.vue` 顶部布局，加入“用户管理”“审批中心”导航，并保持当前操作人选择器在右侧及窄屏可用。

## 7. 联调与验收

- [x] 7.1 运行 `fronted` 类型检查与生产构建，确认通过。
- [ ] 7.2 浏览器验证导航、两个页签、空状态、当前操作人切换刷新及响应式布局。
- [ ] 7.3 配置至少两级审批，验证第一级同意后任务进入下一审批人待办，当前人的记录新增“同意”；末级同意后流程完成。
- [ ] 7.4 验证任一级驳回后待办消失、当前人的记录新增“驳回”，后续审批人无待办。
- [ ] 7.5 验证审批意见为空和非空两种记录展示，以及接口失败时操作弹窗保留输入。

## 8. 文档同步核对

- [x] 8.1 实现完成后逐项核对 `proposal.md`、`design.md`、`tasks.md` 与代码；若必须改变已确认设计，先停止编码并重新进入人工确认流程。
