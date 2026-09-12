## 1. 简化 BPMN 多实例定义

- [x] 1.1 将 `flowable:assignee` 改为 `${currentApprover}`，保留 `flowable:collection="approverList"` 与 `flowable:elementVariable="currentApprover"`。
- [x] 1.2 删除 `loopCardinality`，确保审批实例数量只有 collection 一个来源。
- [x] 1.3 更新 BPMN 注释，说明当前元素绑定、collection 数量来源和驳回短路条件。

## 2. 明确监听器变量作用域

- [x] 2.1 在 `ApprovalActionTaskListener` 中显式使用非 Local 的 `setVariable` 写入最终动作、意见和分级审计变量。
- [x] 2.2 补充 Javadoc/行内注释，明确禁止改为 `setVariableLocal` 的原因，以及该作用域对 `completionCondition` 和结束监听器的影响。

## 3. 补齐顺序多实例集成测试

- [x] 3.1 将全部同意路径扩展为三级审批，逐级断言审批人、`loopCounter`、`nrOfCompletedInstances`、`approvalAction` 和最终 `APPROVED`。
- [x] 3.2 将首级驳回路径扩展为三级审批，断言只完成首个实例、后续无任务、最终动作 `REJECT` 和状态 `REJECTED`。
- [x] 3.3 新增中间级驳回路径，断言首级同意后进入第二级、第二级驳回后短路、未创建第三级任务，并验证关键变量。
- [x] 3.4 保留并复核审批记录、越权操作和事务回滚断言，避免测试重构削弱已有覆盖。

## 4. 验证

- [x] 4.1 运行流程集成测试，确认三条核心路径及关键变量断言全部通过。
- [x] 4.2 运行后端 `clean build`，确认 BPMN 自动部署及所有既有测试无回归。
- [x] 4.3 静态检查 XML 不再包含 `loopCardinality`/`approverList[loopCounter]`，监听器不调用 `setVariableLocal`。

## 5. 文档同步核对

- [x] 5.1 实现完成后逐项核对 `proposal.md`、`design.md`、`tasks.md` 与代码；如 Flowable 实际变量持久化行为要求改变测试策略，先更新设计并重新等待确认。
