# sequential-multi-instance Specification

## Purpose

约束指定用户顺序多实例审批的元素绑定、循环数量来源、变量作用域和驳回短路行为，确保全部通过、首级驳回和中间级驳回均可预测且可测试。

## Requirements

### Requirement: 使用当前集合元素指派审批人

系统 SHALL 使用顺序多实例 collection 的当前元素变量指派每一级审批人，不通过集合索引表达式再次计算审批人。

#### Scenario: 创建当前级审批任务

- **WHEN** Flowable 为 `approverList` 的当前元素创建顺序多实例用户任务
- **THEN** 用户任务的 assignee 直接取 `currentApprover`，且与当前集合元素一致

### Requirement: 审批实例数量具有唯一来源

系统 SHALL 仅使用 `flowable:collection="approverList"` 决定多实例数量，不同时配置 `loopCardinality`。

#### Scenario: 审批人列表包含三级

- **WHEN** 新流程使用包含三个审批人的 `approverList` 启动
- **THEN** Flowable 最多按集合顺序创建三个审批实例，不存在另一套 cardinality 与集合长度冲突

### Requirement: 驳回动作使用流程可见变量作用域

系统 SHALL 使用非 Local 的 `setVariable` 保存审批动作，使多实例完成条件和流程结束监听器能够读取最终动作。

#### Scenario: 当前审批人驳回

- **WHEN** complete 监听器处理 `approvalAction=REJECT`
- **THEN** 驳回动作在多实例完成条件可见的作用域中保持为 `REJECT`，流程立即终止剩余实例

#### Scenario: 防止变量被局部化

- **WHEN** 维护者阅读监听器实现
- **THEN** 代码注释明确说明不得用 `setVariableLocal` 替代 `setVariable`，并说明局部作用域会破坏短路条件

### Requirement: 顺序多实例边界路径可验证

系统 SHALL 通过三级审批集成测试验证全部通过、首级驳回和中间级驳回，并检查多实例关键变量。

#### Scenario: 三级全部通过

- **WHEN** 三名审批人按顺序全部同意
- **THEN** `loopCounter` 按 0、1、2 推进，`nrOfCompletedInstances` 按完成数量增加，最终 `approvalAction=AGREE` 且流程状态为 `APPROVED`

#### Scenario: 首级驳回

- **WHEN** 第一名审批人驳回
- **THEN** 只完成第一个实例，不创建第二、三级任务，最终 `approvalAction=REJECT` 且流程状态为 `REJECTED`

#### Scenario: 中间级驳回

- **WHEN** 第一名审批人同意、第二名审批人驳回
- **THEN** 完成两个实例后立即终止，不创建第三级任务，最终 `approvalAction=REJECT` 且流程状态为 `REJECTED`
