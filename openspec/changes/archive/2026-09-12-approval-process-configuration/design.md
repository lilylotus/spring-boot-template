# Design: 审批流程配置

## 1. Current State

后端已经存在以下能力：

- `GET /api/approval/switch`：查询全局审批开关。
- `PUT /api/approval/switch`：更新全局审批开关。
- `GET /api/approval/chains/{bizType}`：查询指定业务类型的审批链。
- `PUT /api/approval/chains/{bizType}`：整体替换指定业务类型的审批链。
- `approval_switch` 为单行全局开关；`approval_chain_config` 以 `biz_type + level_no` 唯一保存指定审批人。
- `UserServiceImpl` 已根据全局开关决定直接生效或发起审批；Flowable 启动时会复制当次审批人列表，因此后续配置变更不影响运行中的流程。

缺口集中在前端配置入口，以及开启总开关时缺少配置完整性的后端兜底校验。

## 2. Product and Visual Direction

页面面向系统管理员，单一任务是“先配置两条顺序审批链，再决定是否启用审批”。视觉延续现有 Element Plus 管理端的白色、浅灰和主色体系，不引入新的字体或装饰主题。

页面结构使用“配置状态条 + 两条审批轨道”：顶部开关区域明确解释影响范围；下方两张业务卡片分别承载用户新增和用户编辑。每一级用真实顺序编号连接成纵向审批轨道，编号不是装饰，而是直接表达审批先后关系。窄屏下卡片单列，操作按钮保持可触达。

```text
+-------------------------------------------------------+
| 审批配置                         [审批流程  开/关]     |
| 开启后，新提交的用户新增与编辑将依次进入审批。        |
+-------------------------------------------------------+
| 用户新增审批链              | 用户编辑审批链          |
| ① [选择审批人 v] [删除]     | ① [选择审批人 v] [删除] |
| │                           | │                       |
| ② [选择审批人 v] [删除]     | ② [选择审批人 v] [删除] |
| [+ 添加一级]       [保存]    | [+ 添加一级]     [保存] |
+-------------------------------------------------------+
```

设计自检：编号轨道只用于真正有先后含义的审批链；开关、提示和保存按钮保持克制，避免把配置页面做成与现有系统割裂的营销式界面。

## 3. Frontend Architecture

新增文件：

- `src/types/approval-config.ts`
  - `ApprovalSwitch`
  - `ApprovalChainLevel`
  - `ApprovalChainSaveRequest`
  - `ApprovalBizType = 'USER_CREATE' | 'USER_EDIT'`
- `src/api/approval-config.ts`
  - `getApprovalSwitch()`
  - `updateApprovalSwitch(enabled)`
  - `listApprovalChain(bizType)`
  - `saveApprovalChain(bizType, levels)`
- `src/views/ApprovalSettingsView.vue`

`ApprovalSettingsView` 复用 `listUsers()` 获取审批人候选项。新选择只展示 `status === 'ACTIVE'` 的用户，候选项标签使用“姓名（账号）”。若后端返回的旧审批人不在有效用户中，页面仍展示其用户 ID 和“已失效”提示，要求管理员修正后才能保存该审批链。

两条审批链分别维护加载、脏状态和保存状态，避免保存一条链时覆盖另一条。前端提交前根据数组顺序重建连续的 `levelNo`，拖拽排序不在本次范围内；管理员通过“上移/下移”调整顺序。

全局开关的更新规则：

- 关闭：允许直接提交，二次确认“仅影响后续新提交，进行中的审批不会取消”。
- 开启：先确认两条审批链都至少有一级且当前页面没有未保存修改；否则不调用接口并定位到缺失配置。
- 更新失败：恢复开关原值，保留审批链编辑内容。

路由新增 `/approval-settings`，主导航顺序为“用户管理 / 审批中心 / 审批配置”。

## 4. Backend Changes

保留现有接口路径和 DTO，不新增数据库迁移。

增强 `ApprovalConfigService.updateApprovalSwitch(boolean enabled)`：当 `enabled == true` 时，验证 `USER_CREATE` 和 `USER_EDIT` 均存在至少一级连续审批链；任一缺失则抛出明确的 `BusinessException`，不更新开关。关闭时不要求审批链存在。

为避免 `approval.config` 反向依赖 `identity` 模块，审批配置模块内部定义本系统当前受控业务类型集合，值保持为 `USER_CREATE`、`USER_EDIT`。该集合仅用于配置完整性校验；业务模块仍使用自己的 `UserBizType` 调用审批防腐层。

本次不新增审批人存在性数据库校验，因为这会造成 `approval` 模块直接依赖 `identity` 数据模型。前端通过用户列表限制新选择；后端继续接受非空字符串 ID，运行时原有校验与待办指派行为保持不变。后续如需强制跨模块校验，应在 `approval.api` 中增加独立的审批人目录契约，而不是让配置 Service 直接调用 `SysUserMapper`。

Controller 保持轻量：完整 URL 继续写在方法级注解，仅负责 `@Valid`、调用 Service 和统一 `RestResult` 返回。项目当前未引入 Springdoc，因此不增加 OpenAPI 注解。

## 5. Validation and Consistency

- 审批链至少一级。
- 级别由前端数组顺序生成，后端继续校验从 1 开始连续且无重复。
- 每一级审批人不能为空。
- 同一用户允许出现在不同级别；这是合法的顺序审批配置，不额外禁止。
- 开启全局开关要求两条受控业务审批链均存在。
- 保存审批链采用现有事务内“删除旧配置后插入新配置”的整体替换方式。
- 配置修改不触碰 Flowable 运行时表，也不改变进行中的流程变量。

## 6. Testing

后端：

- 开启时两条链均配置成功。
- 任一链缺失时开启失败且开关保持原值。
- 关闭时不受审批链完整性影响。
- 现有审批链连续性、非空审批人和 Controller 接口测试保持通过。

前端：

- 类型检查与生产构建通过。
- 验证两条链的加载、增加、删除、上移、下移和独立保存。
- 验证只可新选有效用户，旧失效审批人有明确提示。
- 验证未配置完整或存在未保存修改时不能开启。
- 验证开关关闭确认、接口失败回滚、桌面与窄屏布局。

## 7. Compatibility and Rollout

数据库中已有配置会被直接读取，无需迁移。默认开关继续使用当前数据库值。部署后管理员可以先完成两条审批链，再开启审批流程；已运行流程不受页面配置变化影响。
