## Why

当前后端仓库（`backend/`）尚无任何业务模块：没有用户管理能力，也没有审批流程能力。作为 4A 管理系统的第一批功能，需要先落地最小可用的"用户新增/编辑"，并让这两个动作可以按需（全局开关控制）先经过审批链才生效，为后续接入更多需要审批的业务动作打好可复用的地基。

同时，这是本仓库第一次引入 Flowable 流程引擎作为具体业务的审批载体，必须一开始就把业务代码与 Flowable 引擎解耦，避免业务模块直接依赖 `org.flowable.*` 类型，否则未来任何业务想接入审批、或者想更换流程引擎，都要改动业务代码。

## What Changes

- 新增用户管理能力：用户新增、用户编辑（含支撑编辑入口所需的最小用户列表/详情查询），用户数据落库于 MySQL，通过 MyBatis-Plus 访问。
- 新增审批流程能力，挂接在"用户新增"和"用户编辑"两个动作上：
  - 审批人为"指定用户"（非角色、非会签），支持按业务动作配置多级审批（顺序审批，每一级一个指定审批人）。
  - 提供全局开关：关闭时用户新增/编辑直接生效并跳过审批；开启时必须走已配置的审批链，全部通过后变更才真正生效。
  - 引入一个不感知 Flowable 的防腐层（Anti-Corruption Layer）：业务层（Controller/Service）只依赖自定义的审批网关接口与 DTO，`org.flowable.*` 任何类型只允许出现在防腐层实现内部。
  - 引入领域事件桥接：Flowable 监听器（引擎反射创建、非 Spring Bean）只发布不依赖 Flowable 类型的 Spring 领域事件，业务层通过事件监听器消费事件并更新业务状态，监听器与业务 Service 之间没有直接调用。
  - 业务数据与 Flowable 运行时数据（ProcessInstance/Task）不强耦合，仅通过防腐层维护的"业务单据 ↔ processInstanceId"关联表间接关联。
  - 用一个通用的 BPMN 流程定义支撑可配置的多级指定用户审批，不为不同的审批级数各建一个流程定义。
- 新增用户管理的 Vue3 前端页面：在 `fronted/` 下用 Vue3 + TypeScript + Element Plus 搭建"用户列表"和"用户新增/编辑表单"两个页面，作为 `fronted/` 的第一个前端工程，直接消费上面新增的用户管理后端接口。审批链配置、全局审批开关、审批人待办与审批操作的前端页面不在本次范围内。

## Capabilities

### New Capabilities
- `identity/user-management`: 用户的新增、编辑（及支撑编辑所需的最小查询）能力，包含新增/编辑记录进入"待审批"或"直接生效"两种路径的行为。
- `approval/user-approval-workflow`: 面向"用户新增/编辑"的指定用户多级审批能力，包括审批链配置、全局审批开关、审批发起/审批人操作/审批结果回写业务状态，以及业务层与 Flowable 引擎之间的防腐层集成方式。

### Modified Capabilities
（无，仓库内此前没有任何已归档的能力规范）

## Impact

- 新增代码：`backend/src/main/java/com/example/template` 下新增用户管理相关的 Controller/Service/Mapper/Entity/DTO 包，以及审批防腐层相关的包（网关接口、Flowable 实现、监听器、领域事件、ApplicationContext 持有类、审批链配置的存储与查询）。
- 新增数据库表（通过 Flyway 迁移脚本管理）：用户表、审批链配置表、审批全局开关配置表、业务单据与 processInstanceId 的关联表。
- 新增/启用 Flowable 相关依赖的实际使用（`build.gradle` 中已引入 `flowable-spring-boot-starter` 与 `flowable-spring-boot-starter-process`，此前未被任何代码使用）：新增 BPMN 流程定义资源文件、流程引擎相关配置。
- 影响范围包括 `backend/` 与 `fronted/`：`backend/` 完成用户管理与审批能力的全部接口；`fronted/` 首次初始化 Vue3 + TypeScript + Vite + Element Plus 前端工程，并实现用户新增/编辑（含列表）页面。审批链配置、全局开关、审批人待办与审批操作的前端页面不在本次范围内，由后续对应的前端变更承接。
