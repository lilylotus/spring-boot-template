## 1. 审批实例快照存储

- [x] 1.1 新增 `V5__add_approval_detail_snapshot.sql`，为 `approval_biz_link` 增加可空的发起人、审批链 JSON、业务数据 JSON 字段。
- [x] 1.2 扩展 `ApprovalBizLink` 和 Service 查询能力，支持按 link ID 查询。
- [x] 1.3 修改 `FlowableApprovalGateway.start`，在原事务内保存发起人、审批人链和 payload 快照，并补持久化测试。

## 2. 审批详情防腐层

- [x] 2.1 为 `ApprovalTaskView`、`ApprovalRecordView` 增加 `approvalId`，更新构造、测试与前端类型。
- [x] 2.2 新增 `ApprovalDetailView`、`ApprovalNodeView`、节点状态枚举，确保不依赖 `org.flowable.*`。
- [x] 2.3 扩展 `ApprovalGateway`，新增 `getApprovalDetail(Long approvalId, String viewerUserId)`。
- [x] 2.4 为审批动作记录增加按流程实例查询能力，按级别和处理时间稳定排序。

## 3. 审批详情组装与权限

- [x] 3.1 在 Flowable 适配层读取 link 快照、当前任务和动作记录，组装完整节点状态及当前级别。
- [x] 3.2 实现旧数据兜底：快照字段为空时读取 Flowable 运行时/历史 `approverList`、`payloadSnapshot`、`initiatorUserId`，不可恢复时明确返回 unavailable。
- [x] 3.3 实现访问范围校验，仅允许当前待办审批人或该实例已处理审批人查看详情。
- [x] 3.4 扩展集成测试，覆盖三级进行中、全部通过、首级/中间驳回、旧数据兜底和无关用户拒绝访问。

## 4. 审批详情 HTTP API

- [x] 4.1 在 `ApprovalTaskController` 新增 `GET /api/approval/instances/{approvalId}`，通过 `@CurrentOperator` 调用网关并返回统一响应。
- [x] 4.2 扩展 Controller 测试，验证 approvalId 字段、详情返回、当前操作人透传和越权错误。

## 5. 用户审批数据快照

- [x] 5.1 新增 `UserApprovalPayloadSnapshot` DTO，包含 `before`、`after` 两份 `UserFieldsSnapshot`。
- [x] 5.2 用户新增审批写入 `before=null, after=申请数据`；用户编辑审批写入修改前和修改后数据。
- [x] 5.3 扩展 `UserServiceImplTest`，验证新增和编辑发起命令中的 payload 内容。

## 6. 前端详情类型与 API

- [x] 6.1 扩展 `src/types/approval.ts`，增加 approvalId、详情、节点状态和 payload 类型。
- [x] 6.2 扩展 `src/api/approval.ts`，新增 `getApprovalDetail(approvalId)`。

## 7. 前端审批详情弹窗

- [x] 7.1 新增 `ApprovalDetailDialog.vue`，展示业务类型、状态、当前审批点、发起时间和发起人。
- [x] 7.2 实现审批数据展示：支持新增 after 数据、编辑 before/after 对比、旧扁平 payload、未知 JSON 和历史不可用状态。
- [x] 7.3 实现完整审批流程轨道，展示各级审批人、节点状态、动作、意见、到达时间和处理时间。
- [x] 7.4 实现加载、失败重试、请求竞态保护、Esc/键盘焦点及窄屏布局。
- [x] 7.5 在待办与审批记录列表增加详情入口，切换操作人时关闭详情；复用用户列表映射审批人显示名。

## 8. 验证

- [x] 8.1 运行后端完整测试与构建，确认审批发起、流转、记录和用户管理无回归。
- [x] 8.2 运行前端类型检查和生产构建。
- [ ] 8.3 浏览器验证待办、进行中审批、已通过、首级/中间驳回详情及正确当前节点。
- [ ] 8.4 浏览器验证新增数据、编辑前后对比、旧 payload/未知 JSON/不可用状态及无权限错误。
- [ ] 8.5 浏览器验证操作人切换、加载失败重试、桌面与窄屏布局。

## 9. 文档同步核对

- [x] 9.1 实现完成后逐项核对 `proposal.md`、`design.md`、`tasks.md` 与代码；若详情存储或访问范围必须改变，停止编码、更新文档并重新等待确认。
