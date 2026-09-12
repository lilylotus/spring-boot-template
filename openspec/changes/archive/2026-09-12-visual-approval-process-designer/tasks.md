## 1. 模板与关联数据模型

- [x] 1.1 新增 Flyway 迁移，创建 `approval_process_template`、`approval_process_template_version`，包含 `biz_type + scope_key` 唯一映射、草稿修订号和当前生效版本
- [x] 1.2 扩展 `approval_biz_link`，增加 businessKey、模板 ID、模板版本 ID/号和流程定义 ID，并更新 Entity 与查询映射
- [x] 1.3 新增模板/版本 Entity、Mapper、画布节点/连线模型、草稿与发布 DTO，限制请求规模并完成 Jakarta Validation

## 2. 草稿、模板版本与业务映射

- [x] 2.1 新增模板 Service：按 `bizType + GLOBAL` 查询模板，模板不存在时从现有审批链生成初始草稿
- [x] 2.2 实现 `saveDraft()`，使用 `expectedDraftRevision` 防并发覆盖，只保存画布且不影响生效版本
- [x] 2.3 实现图结构权威校验：节点/边唯一、端点、规模、单入单出、可达、无环/分叉及审批人完整性
- [x] 2.4 实现不可变版本创建、版本号递增、当前版本激活和历史版本查询
- [x] 2.5 新增模板 GET、草稿 PUT、发布 POST 接口，确保 Controller 只做参数校验和 Service 转调

## 3. BPMN 生成与设计时防腐层

- [x] 3.1 在审批核心定义 `ProcessDefinitionPublisher` 及部署/删除命令结果 DTO，不引用 Flowable 类型
- [x] 3.2 实现受控 BPMN 生成器，将单路径审批节点编译为包含现有同意/驳回语义和监听器的可执行 XML
- [x] 3.3 在 `approval.engine.flowable` 实现发布适配器，内部调用 `RepositoryService.createDeployment()` 并返回部署与流程定义元数据
- [x] 3.4 实现发布编排：校验目标草稿 revision、生成/解析、部署、写版本并激活；数据库失败时补偿删除新部署

## 4. 运行时业务关联桥接

- [x] 4.1 新增 `PublishedProcessResolver`，按 `bizType + scope` 返回审批域自有的当前生效模板信息和审批人顺序
- [x] 4.2 重构 `FlowableApprovalGateway.start`，移除固定流程 key 与直接链表查询，改用 resolver 结果和 `processDefinitionId` 启动
- [x] 4.3 启动时使用复合 businessKey `bizType:bizId`，并在 `approval_biz_link` 保存原始业务键、模板版本、流程定义与实例 ID
- [x] 4.4 扩展审批查询/详情以返回模板名称和版本号，并验证按业务键、实例 ID、businessKey 的正反向追溯
- [x] 4.5 保持 `StartApprovalCommand` 和业务模块无 Flowable 标识，验证新增 bizType 不要求审批运行核心增加流程 key 分支

## 5. 旧配置升级与开关约束

- [x] 5.1 为现有 USER_CREATE、USER_EDIT 审批链实现仅在无模板记录时触发的幂等 v1 生成与首次部署激活，避免自动发布管理员草稿
- [x] 5.2 发布版本时同步保留 `approval_chain_config` 的审批人投影，确保应用回滚仍可使用旧固定流程
- [x] 5.3 调整全局开关开启校验，要求两类业务均存在 GLOBAL 当前发布版本；运行中实例继续使用原定义和审批人快照

## 6. 前端流程设计器

- [x] 6.1 新增模板、版本、草稿 revision、节点与连线 TypeScript 类型及查询/保存草稿/发布 API
- [x] 6.2 新增 `ApprovalProcessDesigner.vue`，实现画布、节点添加/选择/删除、审批人属性编辑和发布状态展示
- [x] 6.3 使用 Pointer Events 与 SVG 实现拖动、输出到输入端口连线、连线重绘与删除，并适配窄屏横向滚动
- [x] 6.4 实现即时校验、问题定位、脏状态、两个模板草稿隔离、加载失败重试和保存失败内容保护
- [x] 6.5 重构 `ApprovalSettingsView.vue` 为两个模板页签，提供“保存草稿”“发布生效”独立操作及发布确认
- [x] 6.6 调整开关开启前检查：两类模板已发布且无未保存草稿；保留关闭确认和接口失败回滚

## 7. 自动化与端到端验证

- [x] 7.1 覆盖模板映射、草稿 revision、不可变版本、图校验、BPMN 生成和发布补偿的后端单元/集成测试
- [x] 7.2 覆盖 resolver 选模、按 definitionId 启动、关联留痕、正反向查询和模板改版后历史版本审计
- [x] 7.3 验证旧链兼容生成 v1、持久化草稿不被自动发布，以及运行中 v1 与新发起 v2 并存
- [x] 7.4 运行 `backend\gradlew.bat test`、`backend\gradlew.bat build` 和前端 `npm run build`
- [ ] 7.5 浏览器验证画图、保存草稿、刷新恢复、发布、并发冲突、失败重试、版本展示和响应式布局
- [x] 7.6 端到端验证用户新增/编辑分别按发布模板运行，业务键与实例可双向追溯，旧实例显示当时模板版本

## 8. 文档同步核对

- [x] 8.1 对照 proposal、design、tasks 与四份 delta spec 核对实现；如模板生命周期、部署边界或关联模型偏离，先更新文档并重新确认
- [x] 8.2 更新接口、数据库、模板发布、业务接入和回滚说明，明确 GLOBAL 一对一映射及未来 scope 路由扩展点
