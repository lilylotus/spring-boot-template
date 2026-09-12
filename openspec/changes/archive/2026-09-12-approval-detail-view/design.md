# Design: 审批详情视图

## 1. Current State and Gaps

现有结构：

- `ApprovalTaskView` 和 `ApprovalRecordView` 只返回 `bizType + bizId`，没有审批实例级 ID。
- `approval_biz_link` 已有自增主键 `id` 与 `process_instance_id`，但没有发起人、审批链和业务数据快照。
- `FlowableApprovalGateway.start` 已把 `initiatorUserId`、`approverList`、`payloadSnapshot` 写入流程变量。
- `approval_action_record` 稳定保存每个已完成节点的审批人、级别、动作、意见和时间。
- 顺序多实例只创建当前级任务，未来节点必须通过发起时的 `approverList` 才能展示。

因此使用 `approval_biz_link.id` 作为对外的 `approvalId`，详情由关联快照、动作记录和当前 Flowable 任务组合生成。

## 2. Database Snapshot

新增 `V5__add_approval_detail_snapshot.sql`：

```text
approval_biz_link
  initiator_user_id  VARCHAR(64) NULL
  approver_snapshot  LONGTEXT    NULL   -- JSON 字符串数组
  payload_snapshot   LONGTEXT    NULL   -- 业务方 JSON 快照
```

字段允许为空以兼容已有记录。新流程启动时与关联记录在同一事务保存：

- `initiator_user_id = command.initiatorUserId`
- `approver_snapshot = approverList` 的 JSON
- `payload_snapshot = command.payloadSnapshot`

不保存审批人姓名，避免用户改名后数据库出现两套身份数据；详情返回用户 ID，前端通过当前用户列表显示“姓名（账号）”，无法匹配时显示用户 ID。

## 3. Stable Approval Identity

扩展列表 DTO：

- `ApprovalTaskView.approvalId`
- `ApprovalRecordView.approvalId`

待办通过任务的 `processInstanceId` 查 `approval_biz_link` 后返回 link ID。审批记录通过记录中的 `processInstanceId` 查 link ID。任何详情入口都调用：

```http
GET /api/approval/instances/{approvalId}
X-User-Id: 当前操作人
```

不把 Flowable `processInstanceId` 暴露给前端，继续保持引擎防腐边界。

## 4. Detail Contract

新增防腐层 DTO：

```text
ApprovalDetailView
  approvalId
  bizType
  bizId
  status                 PENDING / APPROVED / REJECTED
  currentLevel           进行中时为当前级；已结束时为空
  initiatorUserId
  submittedTime
  payloadSnapshot        JSON 字符串；不可恢复时为空
  payloadAvailable       区分真实空值和历史不可恢复
  nodes[]

ApprovalNodeView
  level
  approverUserId
  status                 APPROVED / REJECTED / PENDING / WAITING / SKIPPED
  taskTitle
  action                 AGREE / REJECT / null
  comment
  taskCreatedTime
  actedTime
```

节点状态计算：

- 有动作记录且 `AGREE`：`APPROVED`。
- 有动作记录且 `REJECT`：`REJECTED`。
- 当前活动任务对应级别：`PENDING`。
- 流程仍进行且位于当前级之后：`WAITING`。
- 流程已驳回且位于驳回级之后：`SKIPPED`。
- 流程已通过时所有节点应已有 `AGREE` 记录；若历史数据残缺，未匹配节点按 `WAITING` 降级并记录测试覆盖。

“本次审批到了哪个审批点”由 `status + currentLevel` 明确表达；前端同时在流程轨道上高亮 `PENDING` 节点。已结束流程没有当前节点，并显示最终通过或驳回节点。

## 5. Access Control

`ApprovalGateway.getApprovalDetail(Long approvalId, String viewerUserId)` 执行以下校验：

1. link 必须存在。
2. 当前操作人必须满足至少一项：
   - 是该流程当前活动任务的 assignee；
   - 在 `approval_action_record` 中存在该流程、该审批人的已处理记录。
3. 不满足时抛出统一业务异常，不返回 payload。

这是当前无登录鉴权项目下的业务范围校验，不能替代未来真正的认证授权。

## 6. Legacy Fallback

对于 V5 迁移前记录：

- `approver_snapshot` 为空时，从 `RuntimeService` 或 `HistoryService` 查询 `approverList`。
- `payload_snapshot` 为空时，从运行时或历史变量查询 `payloadSnapshot`。
- `initiator_user_id` 为空时，同样查询 `initiatorUserId`。
- 查询不到时不读取当前审批配置进行伪造；返回 `payloadAvailable=false`，节点只展示能够从动作记录和当前任务确定的部分。

Flowable 查询和类型转换全部留在 `approval.engine.flowable`，Controller 与业务模块不依赖引擎类型。

## 7. Business Payload Format

新用户审批统一使用版本化但保持简单的 JSON 包装：

```json
{
  "before": null,
  "after": {
    "username": "zhangsan",
    "realName": "张三",
    "mobile": "13800000000",
    "email": "zhangsan@example.com"
  }
}
```

- 用户新增：`before=null`，`after` 为申请创建的数据。
- 用户编辑：`before` 为当前已生效数据，`after` 为申请修改后的数据。
- 历史旧 payload 为扁平 `UserFieldsSnapshot`，前端识别后按“申请数据”展示。
- 未知业务类型或未知 JSON 结构使用格式化 JSON 兜底，不丢弃原始内容。

新增 identity DTO `UserApprovalPayloadSnapshot`，避免用无类型 Map 组装；审批模块仍只接收字符串，不依赖 identity DTO。

## 8. Frontend Experience

页面面向正在判断“该不该批”和回看“当时怎么批”的操作人，详情弹窗的核心信息顺序为：当前结论 → 审批数据 → 完整流程。

```text
+--------------------------------------------------+
| 新增用户 · 审批详情                         [×] |
| 进行中 · 当前第 2 级                            |
+--------------------------------------------------+
| 审批数据                                         |
| 字段          修改前             修改后          |
| 姓名          李雷               李磊            |
+--------------------------------------------------+
| 完整审批流程                                     |
| ✓ 第1级  张一（alice）  已同意  意见/时间         |
| ● 第2级  王二（bob）    待审批   当前审批点       |
| ○ 第3级  赵三（carol）  未开始                    |
+--------------------------------------------------+
```

视觉延续审批配置页的顺序轨道，但状态颜色承担真实语义：绿色通过、红色驳回、蓝色当前、灰色等待/跳过。弹窗使用较宽桌面布局；窄屏将数据对比由表格切换为字段卡片，流程轨道保持纵向。支持 Esc 关闭、可见键盘焦点和加载失败重试。

新增：

- `ApprovalDetailDialog.vue`
- `getApprovalDetail(approvalId)` API
- `ApprovalDetail`、`ApprovalNode`、节点状态、payload 类型

待办和审批记录的每行均增加“详情”。切换当前操作人时关闭详情，避免旧身份的敏感数据继续显示。详情请求使用序号或 AbortController，防止快速切换行时旧响应覆盖。

设计自检：流程轨道编号对应真实审批顺序，颜色只表达状态；不增加与任务无关的装饰。数据对比放在流程之前，因为待审批人的首要问题是“正在审批什么”。

## 9. Testing

后端：

- V5 字段映射与新流程快照保存。
- 待办、记录均返回正确 `approvalId`。
- 进行中详情展示完整三级链、已通过节点、当前节点和等待节点。
- 首级/中间驳回后的节点状态包含 `REJECTED` 和 `SKIPPED`。
- 全部通过后所有节点为 `APPROVED`。
- 当前待办审批人、已处理审批人可查看；无关用户不能查看。
- 新旧 payload 格式与旧记录 Flowable 变量兜底。
- Controller 使用 `@CurrentOperator` 并返回统一 `RestResult`。

前端：

- 两个列表都能打开正确实例详情。
- 当前节点、节点状态、审批意见和时间显示正确。
- 新增、编辑前后数据及旧扁平 payload 正确展示，未知 JSON 有兜底。
- 操作人切换关闭详情；加载失败可重试且不残留旧数据。
- 桌面、窄屏和键盘交互通过。

## 10. Compatibility

V5 字段均可空，不阻断旧数据迁移。列表 API 只增加字段。旧审批优先从 Flowable 历史变量恢复；无法恢复时明确降级。新流程详情不依赖当前审批链配置，因此配置修改不会改变历史展示。
