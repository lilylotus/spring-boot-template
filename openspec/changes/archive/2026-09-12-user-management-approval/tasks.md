## 1. 数据库准备

- [x] 1.1 在 `backend/src/main/resources/db/migration` 新增 Flyway 迁移脚本，建 `sys_user`（用户主表，含状态字段：待审批/已生效/已驳回）和 `user_pending_change`（编辑场景的待审批变更快照表），执行 `gradlew.bat flywayMigrate`（或应用启动自动迁移）后确认两张表已创建
- [x] 1.2 新增 Flyway 迁移脚本，建 `approval_switch`（全局总开关，单行记录）、`approval_chain_config`（`biz_type` + `level_no` 唯一，审批链配置）、`approval_biz_link`（`biz_type`+`biz_id` <-> `process_instance_id` 关联表），执行迁移后确认三张表已创建且唯一约束生效

## 2. 审批能力对外契约（approval.api）

- [x] 2.1 创建 `approval.api` 包：`ApprovalGateway`、`ApprovalPolicy` 接口，及 `StartApprovalCommand`/`ApprovalInstanceView`/`ApprovalActCommand`/`ApprovalTaskView` 等 DTO，编译通过且这些类型不依赖 `org.flowable.*`
- [x] 2.2 创建 `approval.api.event.ApprovalResultEvent`（继承 `ApplicationEvent` 的纯 POJO 事件），确认该类文件不 import 任何 `org.flowable.*` 类型

## 3. 审批链配置与全局开关（approval.config）

- [x] 3.1 实现 `approval_switch`/`approval_chain_config` 对应的 Entity + MyBatis-Plus Mapper，编写最小单测验证基本增删改查
- [x] 3.2 实现 `ApprovalConfigController`：全局开关查询/更新接口，以及按 `bizType` 的审批链查询/新增/修改接口（校验级别连续、审批人非空），用接口测试或手工调用验证增删改查行为符合预期
- [x] 3.3 实现 `ApprovalPolicy` 的具体实现类，读取 `approval_switch`，编写单测覆盖开启/关闭两种返回值

## 4. 业务单据与流程实例关联（approval.link）

- [x] 4.1 实现 `approval_biz_link` 对应的 Entity + Mapper，提供按 `bizType+bizId` 查询、按 `processInstanceId` 查询、状态更新的方法，编写单测验证基本读写

## 5. Flowable 引擎适配层（approval.engine.flowable，唯一允许出现 org.flowable.* 的包）

- [x] 5.1 编写 `sequential-designated-user-approval.bpmn20.xml`（顺序多实例用户任务 + `completionCondition` 驳回短路），放入 `backend/src/main/resources/processes/`，启动应用后通过 Flowable 自动部署确认流程定义已成功加载（如查询 `ACT_RE_PROCDEF` 或启动日志确认）
- [x] 5.2 实现 `SpringContextHolder`（`ApplicationContextAware`，静态持有 `ApplicationContext`，`getBean` 在未初始化时抛出明确异常），编写单测验证初始化前后行为
- [x] 5.3 实现 `ApprovalActionTaskListener`（绑定任务 `complete` 事件，把同意/驳回动作写入流程变量，不发布事件、不调用业务代码）
- [x] 5.4 实现 `ApprovalResultExecutionListener`（绑定流程 `end` 事件，读取流程变量得出最终结果，通过 `SpringContextHolder` 获取 `ApplicationEventPublisher` 发布 `ApprovalResultEvent`），确认该类及其所在包之外没有其他业务包直接引用它
- [x] 5.5 实现 `FlowableApprovalGateway`（实现 `ApprovalGateway`）：`start` 按 `bizType` 读取审批链装配 `approverList` 变量并启动流程实例、写入 `approval_biz_link`；`queryByBizKey` 查关联表并回填流程当前状态；`act` 调用 `taskService.complete` 并透传同意/驳回与意见；`listPendingTasks` 按审批人查询待办任务列表
- [x] 5.6 为 `FlowableApprovalGateway` 编写集成测试：发起一条 2 级审批链的流程，第一级同意后校验任务流转到第二级指定审批人，第二级同意后整体状态变为通过；另起一条流程在第一级驳回，校验流程立即结束且不产生第二级任务
- [x] 5.7 实现 `ApprovalTaskController`（位于 `approval` 包，遵循 design.md D9：自身不直接 `import org.flowable.*`，只依赖 `approval.api` 的 `ApprovalGateway`）：`GET` 查询指定审批人的待办列表（调用 `approvalGateway.listPendingTasks(approverUserId)`）、`POST` 提交同意/驳回（调用 `approvalGateway.act(...)`），遵循仓库 Spring Boot 编码规范（方法级完整 URL、统一 `RestResult<T>` 返回），补一个最小接口测试覆盖待办查询与同意/驳回两个接口，为任务组 8.2/8.3 的端到端验证提供 HTTP 入口

## 6. 用户管理模块（identity，禁止出现 org.flowable.*）

- [x] 6.1 实现 `sys_user`/`user_pending_change` 对应的 Entity + Mapper
- [x] 6.2 实现 `UserService`：新增用户、编辑用户方法，依赖 `ApprovalPolicy`/`ApprovalGateway` 接口判断是否需要审批、发起审批前校验无重复进行中审批、开关关闭时直接生效，编译确认 `identity` 包内没有任何 `org.flowable.*` 的 import
- [x] 6.3 实现最小化的用户列表查询、用户详情查询（含展示待审批中的编辑变更），编写单测覆盖
- [x] 6.4 实现 `UserController`：新增用户、编辑用户、列表、详情接口，遵循仓库 Spring Boot 编码规范（方法级完整 URL、统一 `RestResult<T>` 返回、Controller 不写业务逻辑）
- [x] 6.5 实现 `UserApprovalResultListener`（`@Component` + `@TransactionalEventListener(AFTER_COMMIT)` 消费 `ApprovalResultEvent`，按 `bizType` 过滤 `USER_CREATE`/`USER_EDIT`，驱动 `UserService` 完成待审批 -> 已生效/已驳回的状态流转），编写单测验证重复消费同一事件时的幂等行为

## 7. 前端用户管理页面（fronted/，Vue3 + TypeScript + Element Plus）

- [x] 7.1 在 `fronted/` 下用 Vite 初始化 Vue3 + TypeScript 工程（`npm create vite@latest . -- --template vue-ts` 或等价方式），接入 Element Plus、Vue Router、Axios，`npm run dev` 能正常启动空白页面
- [x] 7.2 实现 `src/api/http.ts`（Axios 实例，统一 baseURL/超时，响应拦截按 `RestResult<T>` 的 `code` 区分成功/失败）与 `src/types/user.ts`（用户相关请求/响应的 TS 类型，字段与后端 `UserController` 的 DTO 保持一致）
- [x] 7.3 实现 `src/api/user.ts`：`listUsers`/`getUser`/`createUser`/`updateUser` 方法，分别对接第 6 组已实现的用户管理接口，编写最小联调（连本地后端）验证四个方法都能正确收发数据（已启动本地后端 + `npm run dev`，通过 Vite 代理 `http://localhost:5173/api/...` 实测 4 个接口均正确收发数据，与真实后端联调通过）
- [x] 7.4 实现 `UserListView.vue`：展示用户列表（含状态：已生效/待审批/已驳回）、"新增"入口按钮、每行"编辑"入口，路由为 `/users`
- [x] 7.5 实现 `UserFormView.vue`：新增/编辑共用表单（依据路由参数区分模式），提交后根据后端返回结果提示"已生效"或"已提交审批"，路由为 `/users/new` 与 `/users/:id/edit`
- [ ] 7.6 手工验证：审批开关关闭时，前端新增/编辑用户后列表立即显示为"已生效"；审批开关开启时，前端提交后列表显示为"待审批"，与后端第 7.1/7.2 组端到端验证的场景保持一致（已通过 Vite dev server 代理在 API 层面验证四个接口与真实后端收发数据一致（见 7.3），但当前工具链没有浏览器自动化能力，未能实际打开页面肉眼确认状态标签渲染效果，此项遗留，建议人工在浏览器中做一次可视化确认）

## 8. 端到端验证

- [x] 8.1 关闭全局审批开关，调用新增/编辑用户接口，验证用户直接以"已生效"状态落库，且未产生任何 Flowable 流程实例（`approval_biz_link` 无新记录）（已用真实本地 MySQL/Redis + 启动后端实测：`POST /api/users` 返回 `status=ACTIVE, effective=true`，`approval_biz_link` 表中该 `biz_id` 无记录）
- [x] 8.2 开启全局审批开关并为 `USER_CREATE` 配置 3 级指定审批人，调用新增用户接口，依次用 3 个审批人账号调用同意接口，验证每一步任务正确流转到下一级审批人、最终用户状态变为"已生效"（实测：`alice`→`bob`→`carol` 依次同意，`GET /api/approval/tasks/{approver}` 逐级验证任务流转，最终 `GET /api/users/{id}` 返回 `status=ACTIVE`，`approval_biz_link` 状态为 `APPROVED`）
- [x] 8.3 开启审批开关，对 `USER_EDIT` 发起一次编辑审批，在第一级驳回，验证用户原始信息保持不变、变更记录状态变为"已驳回"、未产生第二级审批任务（实测：`dave` 驳回后 `erin` 待办列表为空，`user_pending_change.status=REJECTED`，`sys_user` 原始字段未变，`approval_biz_link` 状态为 `REJECTED`）
- [x] 8.4 运行 `backend\gradlew.bat build` 确认后端整体编译与已有测试全部通过；运行 `fronted` 工程的构建脚本（如 `npm run build`）确认前端编译通过（backend：`gradlew.bat build` BUILD SUCCESSFUL，28 个测试全部通过；fronted：`vue-tsc -b && vite build` 编译成功）

## 9. 文档同步核对

- [x] 9.1 对照 `proposal.md`/`design.md`/`tasks.md` 与最终代码实现（含 `backend/` 与 `fronted/`）逐项核对是否一致；若实现中出现了必须偏离已确认设计的情况，记录差异并更新对应文档（已核对，架构/契约/包边界均与已确认设计一致；`design.md` 已补充"编码阶段补充说明"章节及 D3/D4/D5 内的"实现落地说明"，记录了脚手架缺口修复、`ApprovalTaskController` 补充、及若干实现细节层面的技术修正，均不改变已确认的架构决策）
