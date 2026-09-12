# process-template-lifecycle Specification

## Purpose

建立流程设计与业务运行之间的模板桥接层，以业务类型和作用域选择已发布流程，通过防腐接口完成部署和启动，并永久记录模板版本、流程定义、流程实例与业务数据的对应关系。

## Requirements

### Requirement: 模板按业务类型和作用域映射

系统 SHALL 以 `bizType + scope` 唯一标识逻辑流程模板；首版使用 `GLOBAL` scope，并允许每个映射只有一个当前生效发布版本。

#### Scenario: 两类业务映射不同模板

- **WHEN** 用户新增和用户编辑分别配置并发布流程
- **THEN** 两个业务类型各自解析到对应模板的当前生效版本

#### Scenario: 尚无生效版本

- **WHEN** 业务类型没有当前生效发布版本却尝试发起审批
- **THEN** 系统拒绝发起并提示先发布流程模板

#### Scenario: 升级前受控审批链兼容

- **WHEN** `USER_CREATE` 或 `USER_EDIT` 保留升级前审批链、尚无任何模板记录且业务首次发起
- **THEN** 系统按旧链生成并发布 v1 后发起；已有未发布模板草稿不得进入该兼容路径

### Requirement: 模板支持草稿和不可变发布版本

系统 SHALL 将可编辑草稿与不可变发布版本分离；每次成功发布产生递增版本并切换当前生效版本，旧版本继续保留用于审计。

#### Scenario: 仅保存草稿

- **WHEN** 管理员保存模板草稿但未发布
- **THEN** 当前生效版本及后续审批的流程选择不发生变化

#### Scenario: 发布新版本

- **WHEN** 管理员发布当前最新草稿且部署成功
- **THEN** 系统新增模板版本、记录部署元数据并将其设为当前生效版本

#### Scenario: 发布旧修订草稿

- **WHEN** 发布请求引用的草稿修订号不是服务端最新值
- **THEN** 系统拒绝发布，避免发布管理员未确认的新草稿或过期内容

### Requirement: 设计时 Flowable 防腐层

系统 SHALL 通过审批域自有发布接口部署和删除流程定义；Controller、模板 Service 和业务模块不得引用 Flowable 类型，只有 Flowable 适配器内部可以调用 `createDeployment()` 等引擎 API。

#### Scenario: 部署发布版本

- **WHEN** 模板 Service 请求部署生成的 BPMN
- **THEN** Flowable 适配器创建 deployment 并以审批域自有结果返回 deployment、流程定义 ID、key 和引擎版本

#### Scenario: 部署后数据库提交失败

- **WHEN** Flowable 部署成功但模板版本或激活指针持久化失败
- **THEN** 系统补偿删除本次新部署，旧生效版本保持不变

### Requirement: 运行时按业务上下文解析生效模板

系统 SHALL 在业务发起审批时按 `bizType + scope` 查询模板映射并取得当前生效流程定义；业务请求和 `StartApprovalCommand` 不得指定 Flowable processDefinitionKey、deployment 或流程定义 ID。

#### Scenario: 发起用户新增审批

- **WHEN** 用户新增业务以 `bizType=USER_CREATE`、`scope=GLOBAL` 发起审批
- **THEN** 审批域解析 USER_CREATE 当前生效版本，并按其 `processDefinitionId` 启动实例

#### Scenario: 新增业务类型

- **WHEN** 新业务模块使用新的 `bizType` 调用审批接口且管理员已为其发布 GLOBAL 模板
- **THEN** 审批运行核心无需新增流程 key 常量或该业务类型专用 Flowable 分支即可启动审批

### Requirement: 保存业务、模板版本和实例关联

系统 MUST 为每次审批保存 `bizType`、`bizId`、businessKey、模板 ID、模板版本 ID/号、流程定义 ID和流程实例 ID，使业务到流程与流程到业务均可追溯。

#### Scenario: 从业务查询审批实例

- **WHEN** 业务模块按 `bizType + bizId` 查询最近一次审批
- **THEN** 审批域返回对应流程实例、所用模板版本和当前状态

#### Scenario: 从流程实例反查业务

- **WHEN** 监听器或运维工具持有流程实例 ID 或 businessKey
- **THEN** 系统可定位关联记录并还原原始 `bizType + bizId`

#### Scenario: 模板改版后的历史审计

- **WHEN** 当前模板已发布更高版本后查看旧审批详情
- **THEN** 详情仍展示旧实例实际使用的模板名称、版本号和流程定义，不误用当前版本

### Requirement: 运行中实例与模板发布隔离

系统 SHALL 只让新发布版本影响发布成功后新发起的审批，运行中实例继续执行启动时绑定的流程定义和审批人快照。

#### Scenario: 运行中发布新版本

- **WHEN** v2 实例仍在运行时管理员发布并激活 v3
- **THEN** v2 实例继续按 v2 执行，新实例使用 v3，二者关联记录分别保留正确版本
