# Change: 加固顺序多实例审批定义与边界测试

## Why

当前 `sequential-designated-user-approval.bpmn20.xml` 已能完成指定用户顺序审批和驳回短路，但流程定义同时使用集合索引表达式与 `loopCardinality`，存在重复的循环控制来源；现有测试仅覆盖两级全部同意和首级驳回，没有验证三级流程的中间驳回，也没有直接断言 Flowable 多实例关键变量。

顺序多实例与提前终止组合依赖变量作用域。若后续维护者把监听器中的 `setVariable` 改为 `setVariableLocal`，`completionCondition` 可能无法在多实例执行作用域读取驳回动作，进而继续创建剩余审批任务。因此需要同时简化 BPMN、明确作用域约束并补齐边界测试。

## What Changes

- 将用户任务审批人表达式由 `${approverList[loopCounter]}` 改为 `${currentApprover}`，直接使用 `flowable:elementVariable`。
- 删除 `loopCardinality`，审批实例数量只由 `flowable:collection="approverList"` 决定。
- 在 `ApprovalActionTaskListener` 中明确说明必须使用非 Local 的 `setVariable`，确保流程级变量可被 `completionCondition` 和流程结束监听器读取。
- 将核心集成测试扩展为三级审批链，覆盖全部同意、首级驳回、中间级驳回三条路径。
- 在各路径的有效检查点断言 `nrOfCompletedInstances`、`loopCounter`、`approvalAction`，并验证最终状态及剩余待办。

## Scope

### In Scope

- BPMN 顺序多实例用户任务定义。
- `ApprovalActionTaskListener` 变量作用域说明及必要的显式流程变量写入。
- `FlowableApprovalGatewayIntegrationTest` 的顺序多实例边界断言。
- 后端完整测试与构建。

### Out of Scope

- 改变审批链配置、审批接口或审批记录表结构。
- 并行会签、或签、动态加签、转交审批。
- 修改业务审批结果事件及用户状态流转规则。
- 前端页面变更。

## Impact

- **Runtime behavior**：预期业务行为不变；审批人绑定方式更直接，循环数量来源唯一。
- **Tests**：增加对 Flowable 内部多实例变量与短路行为的约束。
- **Database/API**：无数据库迁移，无接口变化。

## Risks

- Flowable 在流程结束后可能清理运行时变量，因此测试需要在活动任务阶段断言运行时变量，在流程结束后通过历史变量和最终业务状态断言结果。
- 多实例变量可能位于任务所属执行或其父执行，测试辅助方法应使用 Flowable 的变量作用域查找能力，而不是假设固定执行树层级。
