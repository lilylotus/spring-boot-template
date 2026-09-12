## Context

当前 `FlowableApprovalGateway` 固定按 `sequentialDesignatedUserApproval` 启动，并直接读取 `approval_chain_config`。它已使用 `bizType:bizId` 作为 Flowable businessKey，另由 `approval_biz_link` 保存原始 `bizType`、`bizId`、`processInstanceId` 和审批人/业务快照。补充需求要求把设计器、模板业务映射、部署版本和运行时实例串成同一条可追溯链路，同时延续业务模块不依赖 Flowable 的防腐层原则。

## Goals / Non-Goals

**Goals:**

- 用户新增、用户编辑各自映射到可保存草稿、可发布、可版本化的流程模板。
- 发布动作生成并部署 BPMN，成功后原子切换当前生效版本。
- 业务只传 `bizType`、`bizId` 和业务上下文，由审批域解析流程定义并启动。
- 每个审批实例永久记录所用模板版本、流程定义和 businessKey，支持双向追溯。
- Controller 保持薄层，设计时与运行时 Flowable API 均隔离在适配器实现中。

**Non-Goals:**

- 首版不实现条件分流、部门/金额规则、并行会签、脚本节点、任意 BPMN XML 上传。
- 不向 `sys_user` 或 `user_pending_change` 冗余 `processInstanceId`；同一用户可产生多次审批，统一关联表更能表达一对多历史。
- 不删除旧流程部署和历史模板版本，不改变运行中实例。

## Decisions

### D1. 模板、版本与业务映射分层

新增两张表：

- `approval_process_template`：逻辑模板，字段包含 `id`、`biz_type`、`scope_key`、`name`、`draft_model_json`、`draft_revision`、`active_version_id`、时间；唯一键为 `(biz_type, scope_key)`。
- `approval_process_template_version`：不可变发布版本，包含 `template_id`、`version_no`、`model_json`、`bpmn_xml`、`deployment_id`、`process_definition_id`、`process_definition_key`、发布时间；唯一键为 `(template_id, version_no)`。

首版所有请求使用 `scope_key='GLOBAL'`，从而保持一个业务类型一个生效模板；提前保留 scope 维度，后续可增加部门/金额条件与优先级解析而不改变模板身份。`active_version_id` 是业务类型到当前生效流程的唯一桥接点。

### D2. 草稿保存与发布严格分离

`saveDraft()` 只校验模型基本格式与并发修订号，更新 `draft_model_json/draft_revision`，不部署、不影响运行时。`publish()` 读取服务端最新草稿，执行完整图校验和 BPMN 生成，然后通过设计时端口部署；部署成功后新增不可变版本并更新 `active_version_id`。Controller 只做 `@Valid`、路径参数接收和 Service 转调。

接口：

- `GET /api/approval/templates/{bizType}?scope=GLOBAL`
- `PUT /api/approval/templates/{bizType}/draft`
- `POST /api/approval/templates/{bizType}/publish`

保存草稿携带 `expectedDraftRevision` 防止覆盖并发编辑；发布也携带目标 revision，若草稿已变化则拒绝。

### D3. 设计时 Flowable 防腐层

审批核心定义 `ProcessDefinitionPublisher` 端口及自有命令/结果 DTO；`FlowableProcessDefinitionPublisher` 位于 `approval.engine.flowable`，内部才使用 `RepositoryService.createDeployment()`、查询流程定义和必要的失败补偿。生成器输出受控 BPMN XML：开始事件、顺序多实例审批任务、结束事件及现有监听器，审批人列表来自发布版本模型。

发布编排顺序为“生成并校验 → 部署 → 数据库记录版本并激活”。若部署后数据库提交失败，Service 调用端口删除本次部署；旧 `active_version_id` 始终不变。只有全部成功才返回已发布状态。

### D4. 运行时按业务类型解析，不接收流程定义键

新增 `PublishedProcessResolver`，按 `bizType + scope` 返回审批域自有的 `PublishedProcessTemplate`（模板 ID、版本 ID/号、流程定义 ID/键、审批人顺序）。`ApprovalGateway.start(StartApprovalCommand)` 保持业务入口不包含任何 Flowable 标识；Flowable 实现使用解析出的 `processDefinitionId` 启动。

新增业务类型只需注册业务方调用时使用的 `bizType`，再在设计器创建并发布同名模板；审批运行核心不增加该类型对应的流程定义常量或分支。首版受控开关仍只覆盖 `USER_CREATE`、`USER_EDIT`。

### D5. 运行时双向关联与版本留痕

扩展 `approval_biz_link`：`template_id`、`template_version_id`、`template_version_no`、`process_definition_id`、`business_key`。启动实例仍使用当前已存在且可避免跨类型冲突的复合 businessKey `bizType:bizId`；原始 `bizType`、`bizId` 独立保存。

- 正向：按 `bizType + bizId` 查询 `approval_biz_link`，得到具体实例及当时模板版本。
- 反向：按 `processInstanceId` 查询关联表；或从 Flowable 实例的复合 businessKey 拆出业务类型和 ID，再与关联表核对。

相较把 `processInstanceId` 写入业务表，统一关联表能表达同一业务数据的多次审批，不要求不同业务表增加 Flowable 字段，也便于审批中心跨业务查询。详情 DTO 增加模板名称和版本号用于审计展示。

### D6. 画布模型、校验与 BPMN 编译

草稿模型包含 `modelVersion`、节点 `{id,type,x,y,approverUserId}` 和边 `{id,sourceNodeId,targetNodeId}`。服务端限制最多 50 个节点/边，并验证：恰好一个开始和结束、至少一个审批节点、ID/端点合法、无重复边/自环/环、开始无入边单出边、结束单入边无出边、审批节点单入单出、全节点可达、审批人完整。

校验通过后沿唯一连线路径提取审批人，生成版本化 BPMN。流程定义 key 对同一逻辑模板保持稳定（如 `approvalTemplate_<templateId>`），由 Flowable deployment version 与模板 `version_no` 同步留痕；启动时使用不可歧义的 `processDefinitionId`。

### D7. 旧配置升级

迁移不删除 `approval_chain_config`。首次 GET 某业务模板不存在时，Service 从旧链生成仅供编辑的初始画布，但不持久化或发布。为避免升级后已开启审批突然失效，仅对历史受控类型 `USER_CREATE`、`USER_EDIT` 提供一次兼容路径：业务首次发起且数据库中尚无对应模板记录时，按旧链幂等生成并发布 v1；一旦管理员保存过模板记录但尚未发布，运行时必须拒绝发起，绝不能把未确认草稿自动发布。新业务类型始终要求显式发布。

回滚时保留旧链和 classpath 固定流程；新表与新增关联列可留存，旧代码会忽略它们。

### D8. 前端交互

`ApprovalProcessDesigner.vue` 使用 Vue、Pointer Events 与 SVG：拖动节点；先点输出端口再点输入端口建立连线；属性面板选择有效审批人；可删除审批节点/边，开始/结束不可删。两类业务使用页签，各自保留草稿、服务端 revision、发布版本和脏状态。

按钮明确区分“保存草稿”和“发布生效”。发布前展示校验结果和目标版本确认；保存/发布失败保留画布。全局开关开启前要求两类业务均有发布版本且页面无未保存草稿。窄屏允许画布横向滚动，属性面板下移。

## Risks / Trade-offs

- [Risk] Flowable 部署与模板数据库提交跨资源不原子 → 部署失败不写版本，数据库失败补偿删除新部署，旧激活版本不动。
- [Risk] BPMN 生成错误导致不可执行 → 生成器只支持固定节点集合，发布前执行模型校验与 Flowable 解析校验，并做集成测试。
- [Risk] 模板改版混淆历史 → 版本记录不可变，业务关联保存版本 ID/号和流程定义 ID。
- [Risk] businessKey 解析歧义 → 继续使用 `bizType:bizId` 复合值，同时保留原始字段和实例唯一关联。
- [Trade-off] 首版只有 GLOBAL scope 和单路径 → 数据结构预留 scope，路由策略以后独立扩展。

## Migration Plan

1. 新建模板/版本表并扩展业务关联表，保留旧字段与旧链。
2. 现有两类完整审批链在首次运行时分别生成模板、部署 v1 并设置激活版本；已有模板记录或新业务类型不进入兼容路径。
3. 部署运行时 resolver 与新版 Gateway，再上线设计器。
4. 回滚应用时恢复旧固定定义读取路径；旧 `approval_chain_config` 仍保持最近一次发布对应的审批人顺序。

## Open Questions

无。多 scope 的匹配条件、优先级和条件表达式在出现真实业务需求时单独设计，首版固定使用 `GLOBAL`。
