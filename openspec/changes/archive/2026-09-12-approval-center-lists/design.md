## Context

后端 `ApprovalTaskController` 已提供：

- `GET /api/approval/tasks`：依据 `@CurrentOperator` 查询当前操作人的待办。
- `POST /api/approval/tasks/act`：依据 `@CurrentOperator` 以当前操作人身份同意或驳回。

现有 `ApprovalTaskView` 包含 `bizType`、`bizId`、`level`、`taskTitle`、`createdTime`。前端请求拦截器已经为所有请求附加当前操作人请求头，但路由目前只有 `/users`，没有审批页面或审批 API 封装。

当前审批动作只作为流程变量写入 Flowable。虽然 Flowable 可提供历史任务查询，但历史数据保留策略属于引擎配置，且逐级动作/意见与业务关联的稳定读取会依赖引擎内部变量结构，不适合作为长期的产品查询契约。

## Goals / Non-Goals

**Goals:**

- 为当前操作人提供可操作的待审批列表。
- 为当前操作人提供稳定、按处理时间倒序的已审批记录列表。
- 同意或驳回后，待办和记录列表立即同步。
- 切换全局当前操作人时自动刷新，不混用上一位操作人的数据。
- 审批业务 API 继续通过不暴露 Flowable 类型的 `approval.api` 契约提供。
- 审批动作记录与流程任务完成保持同一事务，要么同时成功，要么同时回滚。

**Non-Goals:**

- 不实现审批链配置、审批开关配置页面。
- 不实现分页、筛选、导出、批量审批或撤回。
- 不实现“我发起的审批”列表或所有人的全局审批审计。
- 不扩展用户新增/编辑表单，不改变审批链与流程定义。
- 不在本次把业务标识解析为用户详情或变更字段对比；列表先展示稳定的业务类型与业务标识。

## Decisions

### D1. 审批记录采用独立审计表

新增 Flyway `V4__create_approval_action_record.sql`，创建 `approval_action_record`：

```text
id, biz_type, biz_id, process_instance_id, task_id,
approver_user_id, level_no, task_title, action, comment,
task_created_time, acted_time
```

`task_id` 建唯一索引，避免同一任务重复形成审计记录；`approver_user_id + acted_time` 建查询索引。记录表属于 `approval` 能力自身，不由 `identity` 业务模块直接访问。

选择独立表而不是直接读取 Flowable 历史表，原因是产品侧审批记录需要稳定保留业务标识、逐级动作和意见，不应受具体引擎历史级别、表结构或清理策略影响。流程引擎仍负责运行时任务，审计表负责产品查询。

### D2. 审批动作与审计记录原子提交

在 `FlowableApprovalGateway.act` 已有事务内，找到当前任务并校验审批人后，先构造并写入审批动作记录，再调用 `taskService.complete`。两者使用当前共享数据源和事务管理器；任一步失败均回滚。

记录内容来自已经校验过的 `ApprovalActCommand`、当前 `Task`、`ApprovalBizLink` 及 `resolveLevel(task)`。不修改 BPMN 和监听器，现有流程变量仍按原逻辑写入并驱动审批流转。

### D3. 扩展防腐层查询契约

在 `approval.api.dto` 新增不依赖 Flowable 的 `ApprovalRecordView`：

```text
bizType, bizId, level, taskTitle, action, comment,
taskCreatedTime, actedTime
```

`ApprovalGateway` 新增：

```java
List<ApprovalRecordView> listApprovalRecords(String approverUserId);
```

`FlowableApprovalGateway` 通过审批记录 Service 查询并转换，不向 Controller 或前端暴露数据库 Entity、Flowable Task 或 HistoryService 类型。

### D4. 新增当前操作人的审批记录接口

`ApprovalTaskController` 新增方法级完整路径：

```text
GET /api/approval/records
```

Controller 使用 `@CurrentOperator OperatorContext operator` 取得当前操作人 ID，调用 `approvalGateway.listApprovalRecords(operator.userId())`，返回 `RestResult<List<ApprovalRecordView>>`。项目未引入 Springdoc/OpenAPI，故不新增 Swagger 注解。

### D5. 前端审批中心结构

新增：

```text
src/types/approval.ts
src/api/approval.ts
src/components/ApprovalActionDialog.vue
src/views/ApprovalCenterView.vue
```

路由新增 `/approvals`。`App.vue` 顶部加入“用户管理”“审批中心”导航，当前操作人选择器仍固定在右侧。`ApprovalCenterView.vue` 用两个页签承载待办和记录；当前操作人的响应式 ID 变化时同时重新加载两张列表，并用请求序号忽略过期响应。

待办行点击“同意”或“驳回”后打开 `ApprovalActionDialog.vue`，显示动作与任务摘要，审批意见可选。提交成功发出事件，由页面刷新两张列表；失败保留意见和弹窗。

### D6. 前端展示语义

- `USER_CREATE` 显示为“新增用户”，`USER_EDIT` 显示为“编辑用户”；未知类型回退显示原始值。
- `AGREE` 显示绿色“同意”，`REJECT` 显示红色“驳回”。
- 时间沿用后端 `LocalDateTime` 字符串，不在本次引入日期库。
- 加载失败保留空表格并由统一 HTTP 拦截器提示；真正为空时使用明确空状态“当前没有待审批任务”或“暂无审批记录”。

## Visual Direction

审批中心面向内部管理员，单一任务是快速判断“现在要处理什么、过去处理过什么”。沿用 Element Plus 现有体系，不做与用户管理割裂的品牌重设计。

- 色彩：页面底色 `#F5F7FA`、内容面 `#FFFFFF`、主操作蓝 `#409EFF`、同意绿 `#67C23A`、驳回红 `#F56C6C`、正文墨色 `#303133`。
- 字体：标题与正文继续使用系统中文无衬线字体；业务标识使用等宽回退字体，便于辨认长 ID。
- 布局：顶部导航保持轻量，审批页面使用标题说明 + 页签 + 单一数据表，不增加仪表盘式装饰。

```text
┌ 用户管理 | 审批中心                         当前操作人 ▾ ┐
├────────────────────────────────────────────────────────┤
│ 审批中心                                                │
│ 以下内容随当前操作人切换                                │
│ [待我审批 2] [审批记录]                                 │
│ ┌ 业务类型 │ 业务标识 │ 级别 │ 到达时间 │ 操作 ┐       │
│ └──────────────────────────────────────────────┘       │
└────────────────────────────────────────────────────────┘
```

标志性交互是页签上的实时待办数量与顶部当前操作人联动：切换身份后，当前人的工作队列立即替换，清楚表达“我正在以谁的身份审批”。该表达直接服务审批语义，不添加无关动画或装饰。

## Risks / Trade-offs

- 新审计表只能记录上线后的审批动作，迁移前已经完成的历史任务不会自动回填；本次明确不做历史数据迁移。
- 列表暂不分页，数据量增长后需要增加分页契约；当前项目所有列表仍为最小实现，本次保持一致。
- 审计写入与 Flowable 依赖共享事务；若未来拆分数据源，需要按既有架构风险改为可靠事件或 Outbox。
- 当前只显示业务标识，不展示完整申请内容；这样可在不让审批模块反向依赖 `identity` 的前提下先交付稳定列表，后续可另行设计业务摘要解析接口。

## Open Questions

无。审批记录按“当前操作人已处理动作”定义；待办页同时提供现有同意/驳回能力。

