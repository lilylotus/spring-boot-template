## Why

流程画布只有与业务类型、发布版本和具体业务实例建立稳定关联后，才能成为真正可执行、可审计的审批能力。当前系统把固定流程定义和审批链写死在运行时实现中，缺少“设计—发布—业务选模—实例追溯”的完整桥梁。

## What Changes

- 在审批配置页为用户新增、用户编辑提供可视化画布，支持开始、审批人、结束节点的布局、连线和属性配置。
- 引入“保存草稿 → 发布”生命周期：保存只持久化画布；发布才校验、生成 BPMN、调用设计时防腐层部署，并切换业务类型当前生效版本。
- 新增流程模板及版本模型，以 `bizType + scope` 绑定适用业务；首版只启用 `GLOBAL` scope，同一业务类型仅有一个当前生效版本。
- 运行时按 `bizType + scope` 解析当前发布版本，再按流程定义 ID 启动，不由业务代码指定 `processDefinitionKey`。
- 扩展 `approval_biz_link`，记录模板、模板版本、流程定义与流程实例；保留 `bizType + bizId` 到实例的正向查询，以及由实例/businessKey 返回业务的反向查询。
- 业务层与设计层均只依赖本项目定义的接口和 DTO；只有 `approval.engine.flowable` 实现允许调用 Flowable API，包括 `createDeployment()`。
- 模板改版只影响发布后新发起的审批；历史审批继续关联并展示当时的模板版本。
- 首版流程仍为无环、无分支的顺序指定用户审批；条件路由和多 scope 匹配规则作为后续扩展。

## Capabilities

### New Capabilities

- `approval/visual-process-designer`：画布建模、草稿编辑、结构校验与 BPMN 生成。
- `approval/process-template-lifecycle`：模板业务映射、草稿/发布、部署防腐层、版本激活与审计留痕。

### Modified Capabilities

- `approval/approval-configuration`：列表式审批链配置升级为可视化模板草稿与发布入口。
- `approval/user-approval-workflow`：发起审批时按业务类型解析生效模板，并持久化业务、模板版本与实例的双向关联。

## Impact

- 前端：重构 `ApprovalSettingsView.vue`，新增流程设计器、模板状态、草稿保存与发布操作。
- 后端：新增模板/版本/发布领域模型、接口、服务和 Flowable 设计时适配器；重构运行时模板解析。
- 数据库：新增模板与版本表，扩展 `approval_biz_link` 的模板版本审计字段；保留现有审批链数据用于升级兼容。
- Flowable：从固定 classpath 流程切换为发布时生成和部署的版本化 BPMN；业务模块仍不引用 Flowable 类型。
