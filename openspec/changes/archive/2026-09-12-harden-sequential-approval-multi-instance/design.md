# Design: 顺序多实例审批稳健性加固

## 1. Current State

当前流程定义：

- 用户任务通过 `flowable:collection="approverList"` 和 `flowable:elementVariable="currentApprover"` 创建顺序多实例。
- `flowable:assignee` 仍使用 `${approverList[loopCounter]}`，没有使用已声明的 `currentApprover`。
- 同时声明 `<loopCardinality>${approverList.size()}</loopCardinality>`，与 collection 形成两套数量来源。
- `completionCondition` 使用 `approvalAction == 'REJECT' || nrOfCompletedInstances == nrOfInstances` 实现驳回短路或全部完成。
- `ApprovalActionTaskListener` 当前调用 `delegateTask.setVariable(...)` 保存分级动作和意见，没有使用 `setVariableLocal(...)`。
- 集成测试覆盖两级全部同意、首级驳回，但未覆盖中间驳回和多实例内部变量。

## 2. BPMN Simplification

用户任务调整为：

```xml
<userTask id="approvalTask" name="审批" flowable:assignee="${currentApprover}">
    ...
    <multiInstanceLoopCharacteristics isSequential="true"
                                       flowable:collection="approverList"
                                       flowable:elementVariable="currentApprover">
        <completionCondition>
            ${approvalAction == 'REJECT' || nrOfCompletedInstances == nrOfInstances}
        </completionCondition>
    </multiInstanceLoopCharacteristics>
</userTask>
```

`currentApprover` 由 Flowable 在每个多实例执行上根据 collection 当前元素赋值，用户任务无需直接了解集合索引。删除 `loopCardinality` 后，实例数量仅由 `approverList` 决定，避免集合长度和显式 cardinality 不一致。

## 3. Variable Scope Contract

`FlowableApprovalGateway.act(...)` 通过 `taskService.complete(taskId, variables)` 传入 `approvalAction` 和 `approvalComment`。任务 complete 监听器读取这两个变量，并使用 `delegateTask.setVariable(...)` 写入当级审计变量。

监听器必须保留非 Local 写法，并在 Javadoc/行内注释中明确以下原因：

- `setVariable` 将变量写入任务所属执行可向流程树解析的作用域。
- `completionCondition` 在多实例执行上下文求值，需要读取最新 `approvalAction`。
- `ApprovalResultExecutionListener` 在流程结束时也需要读取最终 `approvalAction`。
- `setVariableLocal` 只写任务局部作用域，任务完成后该局部作用域消失，可能导致完成条件看不到驳回动作。

实现时应显式使用 `delegateTask.setVariable("approvalAction", action)` 和 `delegateTask.setVariable("approvalComment", comment)`，再保存 `approvalLevel{N}Action/Comment`。虽然网关当前已把同名变量作为完成参数传入，监听器再次用非 Local API 写入是有意的作用域保证，不应被“去重”为 Local 变量。

## 4. Integration Test Matrix

核心路径统一使用三级审批人 `alice -> bob -> carol`。

### 4.1 全部通过

1. 启动后断言当前任务属于 `alice`，`loopCounter=0`，`nrOfCompletedInstances=0`。
2. `alice` 同意后断言当前任务属于 `bob`，`loopCounter=1`，`nrOfCompletedInstances=1`，`approvalAction=AGREE`。
3. `bob` 同意后断言当前任务属于 `carol`，`loopCounter=2`，`nrOfCompletedInstances=2`，`approvalAction=AGREE`。
4. `carol` 同意后断言无活动任务、最终状态 `APPROVED`，历史最终 `approvalAction=AGREE`；历史多实例变量能证明三个实例均完成。

### 4.2 首级驳回

1. 启动后断言 `alice` 任务的 `loopCounter=0`、`nrOfCompletedInstances=0`。
2. `alice` 驳回后断言流程结束、无 `bob/carol` 任务、最终状态 `REJECTED`、历史最终 `approvalAction=REJECT`。
3. 历史变量/活动记录证明只完成第一个实例，没有推进到后续索引。

### 4.3 中间级驳回

1. `alice` 同意后断言 `bob` 任务的 `loopCounter=1`、`nrOfCompletedInstances=1`、`approvalAction=AGREE`。
2. `bob` 驳回后断言流程结束、无 `carol` 任务、最终状态 `REJECTED`、历史最终 `approvalAction=REJECT`。
3. 历史变量/活动记录证明完成两个实例，未创建第三个审批任务。

测试辅助方法从当前任务使用 `taskService.getVariable(taskId, variableName)` 获取可向父作用域解析的变量；流程结束后的最终动作使用 `HistoryService` 查询历史变量。若 `nrOfCompletedInstances` 或 `loopCounter` 在当前 Flowable 版本未持久化为单一历史变量，则使用历史变量列表或多实例活动记录断言等价事实，不能删除对完成数量和循环索引的验证。

## 5. Existing Tests

- 保留越权审批不写记录测试。
- 保留 Flowable 完成失败时审批记录回滚测试。
- 保留每级审批记录的级别、动作和意见断言。
- 原“两级全部通过”与“首级驳回”测试升级为三级变量断言，不重复保留较弱版本。

## 6. Compatibility

流程定义 ID 不变，由 Flowable 自动部署新版本。已经运行中的流程实例继续引用启动时的流程定义版本；新启动的流程使用新版本。API、业务关联表和审批记录结构均不变化。

## 7. Verification

- 校验 BPMN 可成功解析和自动部署。
- 运行 `FlowableApprovalGatewayIntegrationTest`。
- 运行后端完整 `clean build`，确认所有既有审批和用户管理测试无回归。
- 检查 XML 中不存在 `loopCardinality` 和 `approverList[loopCounter]`，监听器不存在 `setVariableLocal` 调用。
