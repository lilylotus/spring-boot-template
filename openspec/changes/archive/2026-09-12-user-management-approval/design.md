## Context

`backend/` 目前是一个空的 Spring Boot 3.5.16 + Java 21 脚手架：`build.gradle` 已声明 `flowable-spring-boot-starter`、`flowable-spring-boot-starter-process`、MyBatis-Plus、Flyway、Redis 等依赖，但尚未被任何代码实际使用；除入口类、统一响应类、两个工具类和一个示例 Controller 外没有业务代码（见仓库根 `CLAUDE.md`）。本设计是仓库第一次引入 Flowable，需求与动机见 `proposal.md`；具体行为契约见 `specs/identity/user-management/spec.md` 与 `specs/approval/user-approval-workflow/spec.md`，本文档不重复列出，只讲如何实现。

`fronted/` 目前是空目录，尚无任何前端工程。本次追加范围：用户管理的"新增""编辑"（及支撑编辑入口的最小列表）改为同时交付对应的 Vue3 前端页面，作为 `fronted/` 下的第一个前端工程；技术栈选型（TypeScript + Element Plus，见 D10）已与用户确认。

## Goals / Non-Goals

**Goals:**
- 业务侧代码（`identity` 包，即用户管理的 Controller/Service）编译期零依赖 `org.flowable.*`；`org.flowable.*` 只允许出现在审批能力内部一个受控的引擎适配包中。
- 用**一份** BPMN 流程定义支撑"可配置的多级指定用户顺序审批"，配置多一级或少一级不需要新增/切换流程定义。
- 提供全局开关，关闭时用户新增/编辑完全绕过审批能力（不产生任何流程实例）。
- 业务数据表与 Flowable 运行时表之间不直接互相持有对方的主键/运行时 ID，只通过审批能力自己维护的关联表间接关联。
- Flowable 监听器（引擎反射创建，非 Spring Bean）只发布不依赖 Flowable 类型的 Spring 领域事件，不直接调用任何业务 Service。
- 达到可用于生产的工程质量：事务边界清晰、事件消费幂等、有基本的输入校验与并发保护。
- `fronted/` 下交付用户新增/编辑（及最小列表）的 Vue3 页面，作为 `identity` 模块 REST 接口的唯一直接消费方，不在前端重复实现后端已有的业务校验逻辑（前端只做基础表单校验，最终以后端返回结果为准）。

**Non-Goals:**
- 会签/并行多人共同决策、按角色或组织架构动态解析审批人。
- 通用流程设计器、前端动态编排 BPMN 的能力。
- 审批链配置、全局审批开关、审批人待办列表与审批操作对应的前端页面（本次这些能力仅交付后端接口，前端页面留给后续变更承接；本次前端范围只有"用户新增/编辑"）。
- 审批模块之外的其他业务能力（如角色、权限、部门等 4A 模块的其余部分）。
- 事件丢失后的自动对账/补偿机制的完整实现（仅在风险中说明兜底思路，不在本次任务范围内落地）。

## Decisions

### D1. 包结构：防腐层的物理边界

在 `com.example.template` 下新增两个顶层业务包，彼此的依赖方向单向（`identity` → `approval.api`，禁止反向，`identity` 禁止依赖 `approval.engine.*`）：

```
identity/                          # 用户管理，业务层，禁止出现 org.flowable.*
  controller/  service/  entity/  mapper/  dto/

approval/                          # 审批能力，唯一允许出现 org.flowable.* 的地方
  api/                             # 对外契约：业务层只允许依赖这一层
    ApprovalGateway.java           # 接口：发起/查询/审批人操作/开关判断/待办查询
    ApprovalPolicy.java            # 接口：全局开关判断（isEnabled(bizType)）
    dto/  ...                      # StartApprovalCommand / ApprovalInstanceView / ApprovalActCommand / ApprovalTaskView
    event/
      ApprovalResultEvent.java     # 纯 POJO 领域事件，extends ApplicationEvent，不依赖 org.flowable.*
  config/                          # 审批链配置 + 全局开关，纯数据库读写，不依赖 org.flowable.*
    entity/ mapper/ service/ controller/   # 供前端配置指定审批人、级数、总开关
  link/                            # 业务单据 <-> processInstanceId 关联表，不依赖 org.flowable.*
    entity/ mapper/
  engine/flowable/                 # 唯一可以 import org.flowable.* 的包
    FlowableApprovalGateway.java   # 实现 ApprovalGateway / ApprovalPolicy
    SpringContextHolder.java       # 桥接：ApplicationContextAware，静态持有 ApplicationContext
    listener/
      ApprovalActionTaskListener.java     # TaskListener(complete)，记录本级审批动作
      ApprovalResultExecutionListener.java # ExecutionListener(end)，发布 ApprovalResultEvent
    resources: src/main/resources/processes/sequential-designated-user-approval.bpmn20.xml
```

替代方案：把 `ApprovalGateway` 放进 `identity` 或一个通用 `common` 包。放弃原因：`approval` 是一个独立能力（对应 `specs/approval/user-approval-workflow`），不应该寄生在 `identity` 里；未来其他业务动作（不只是用户新增/编辑）接入审批时，直接依赖 `approval.api` 即可，不需要经过 `identity`。

### D2. `ApprovalGateway` 契约

```java
public interface ApprovalGateway {
    ApprovalInstanceView start(StartApprovalCommand command);
    ApprovalInstanceView queryByBizKey(String bizType, String bizId);
    void act(ApprovalActCommand command);
    List<ApprovalTaskView> listPendingTasks(String approverUserId);
}

public interface ApprovalPolicy {
    boolean isEnabled(String bizType);
}
```

`StartApprovalCommand(bizType, bizId, initiatorUserId, payloadSnapshot)`、`ApprovalInstanceView(bizType, bizId, status: PENDING|APPROVED|REJECTED, currentLevel, currentApproverUserId)`、`ApprovalActCommand(bizType, bizId, approverUserId, action: AGREE|REJECT, comment)`、`ApprovalTaskView(bizType, bizId, level, taskTitle, createdTime)`。这些类型全部是普通 Java 类/record，`identity` 模块以及未来任何想接入审批的业务模块只依赖这一组接口和 DTO。

`ApprovalTaskController`（供审批人查看待办、执行同意/驳回）与 `ApprovalConfigController`（供前端配置审批链、全局开关）都只依赖 `approval.api` 与 `approval.config`，不直接使用 `RuntimeService`/`TaskService` 等 Flowable 类型，让 `engine/flowable` 成为唯一接触 Flowable 运行时 API 的代码位置。

### D3. 用户新增/编辑如何接入审批（业务层视角）

`UserService`（`identity` 包）依赖 `ApprovalPolicy` 与 `ApprovalGateway`（均为接口，Spring 注入具体实现，`UserService` 不知道也不需要知道实现是 Flowable）：

1. 校验入参（账号唯一性等）。
2. `approvalPolicy.isEnabled("USER_CREATE"|"USER_EDIT")` 为 `false`：直接落库，用户状态 = 已生效（新增）或直接覆盖已生效信息（编辑），流程结束，不调用 `ApprovalGateway`。
3. 为 `true`：
   - 新增：插入用户行，状态 = 待审批。
   - 编辑：插入一条"待生效变更"记录（`identity` 自己的 `user_pending_change` 表，保存变更前后的字段差异快照），不动已生效的用户行。
   - 发起前先调用 `approvalGateway.queryByBizKey(bizType, bizId)`，若已存在进行中的审批（状态 PENDING），拒绝本次提交，避免同一用户并发提交多条审批（对应 `spec` 中“同一时刻只允许一条待审批变更”的隐含约束，写清楚防止竞态）。
   - 调用 `approvalGateway.start(new StartApprovalCommand(bizType, bizId, operatorId, snapshot))`，`bizId` 用 `新增` 场景下新生成的用户 ID，或 `编辑` 场景下新生成的 `user_pending_change` ID。
4. 返回响应，明确告知调用方本次是"已生效"还是"待审批"。

审批结果回写（成功/驳回）**不**由 `UserService` 主动轮询，而是被动消费 `approval.api.event.ApprovalResultEvent`（见 D5）。

> **实现落地说明（编码阶段核实后补充）**：本节第 3 步描述的"发起前调用 `approvalGateway.queryByBizKey(bizType, bizId)`"这一检查，其 `bizId` 是本次新生成的（新用户 ID / 新 `user_pending_change` ID），单独无法拦住"同一用户并发提交多条审批"这一真正要防的场景。实现中保留了这次字面描述的调用（作为对同一 `bizId` 重复发起的防御性检查）,并在其之外补充了真正生效的并发校验：新增场景依赖账号唯一性约束；编辑场景在写入新的 `user_pending_change` 记录前，先按 `userId` 查询是否已存在 `status = PENDING` 的记录，存在则拒绝本次提交。不改变已确认的数据流和防腐层边界。

### D4. 用一份 BPMN 支撑可配置的多级指定用户顺序审批

部署时机：应用启动时通过 Flowable 的自动部署（`classpath:processes/*.bpmn20.xml`，Flowable Spring Boot Starter 默认行为）部署且仅部署一份流程定义，`processDefinitionKey = sequentialDesignatedUserApproval`，版本随流程文件变化自然递增，不随审批链级数变化。

流程结构：`StartEvent -> UserTask(顺序多实例) -> ExclusiveGateway(按结果分叉) -> EndEvent(通过/驳回两个结束事件，或用一个 EndEvent + 变量表达结果)`。关键点在这个"顺序多实例（Sequential Multi-Instance）"用户任务：

- `flowable:sequential="true"`（顺序执行，不是并行会签）。
- `loopCardinality` 表达式指向发起流程时传入的变量，如 `${approverList.size()}`。
- `flowable:assignee` 表达式引用每次循环的当前元素，如 `${approverList[loopCounter]}`（或使用 `flowable:elementVariable` 绑定循环变量后引用）。
- `completionCondition` 表达式：`${approvalAction == 'REJECT' || nrOfCompletedInstances == nrOfInstances}` —— 任意一级驳回立即终止后续循环，不再创建下一级任务，直接对应 `spec` 中“任一级别驳回则整体驳回”“不再创建后续级别的审批任务”。

`approverList`（有序审批人 ID 列表）由 `FlowableApprovalGateway.start(...)` 在调用 `runtimeService.startProcessInstanceByKey(...)` 时，依据 `approval.config` 里为该 `bizType` 配置的审批链（按 `level_no` 排序取出的 `approver_user_id` 列表）作为流程变量传入；配置改成 3 级还是 5 级，都复用同一份流程定义，只是传入的变量长度不同，满足“不必为每种级数各建一个流程定义”。

替代方案：为每种级数动态生成/部署一份 BPMN（如 2 级一份、3 级一份）。放弃原因：流程定义会随配置项膨胀、每次改配置要重新部署新版本，管理与追踪成本明显高于顺序多实例这一原生机制。

> **实现落地说明（编码阶段核实后补充）**：`loopCounter` 是顺序多实例子执行上的**局部**变量，`ApprovalActionTaskListener`/`FlowableApprovalGateway` 中读取当前处理到第几级时必须用非 Local 的 `getVariable`（从任务所在执行开始逐级向上查找），用 `getVariableLocal`（只查任务自身作用域）读不到值；已按此修正实现，并被 5.6 集成测试的两个场景（2 级通过、首级驳回）覆盖验证。

### D5. 监听器桥接：静态 `ApplicationContext` 持有类 + 纯领域事件

`TaskListener`/`ExecutionListener`（`TaskListener` 实际包路径为 `org.flowable.task.service.delegate.TaskListener`，非本节文字最初暗示的 `org.flowable.engine.delegate` 包，编码阶段已核实并按实际路径实现）的实现类由 Flowable 引擎按 `flowable:class` 属性反射创建，不经过 Spring 容器，因此不能用 `@Autowired` 注入业务 Bean。桥接方式：

```java
@Component
public class SpringContextHolder implements ApplicationContextAware {
    private static volatile ApplicationContext context;
    @Override
    public void setApplicationContext(ApplicationContext ctx) { context = ctx; }
    public static <T> T getBean(Class<T> type) {
        if (context == null) {
            throw new IllegalStateException("ApplicationContext 尚未初始化，无法获取 Bean: " + type);
        }
        return context.getBean(type);
    }
}
```

`ApprovalActionTaskListener`（绑定在用户任务的 `complete` 事件上）只做一件事：把当前审批人的同意/驳回动作和意见写入流程变量（供 `completionCondition` 和后续判断使用），**不发布事件、不调用任何业务代码**。

`ApprovalResultExecutionListener`（绑定在流程的 `end` 事件上）读取流程变量得到最终结果，发布：

> **实现落地说明（编码阶段核实后补充）**：`ApplicationEventPublisher` 由 `ApplicationContext` 直接实现，不会作为独立可查询的 Bean 注册到容器中，字面调用 `SpringContextHolder.getBean(ApplicationEventPublisher.class)` 实测会抛 `NoSuchBeanDefinitionException`。实现改为 `SpringContextHolder` 新增 `getApplicationContext()` 方法，监听器直接对取到的 `ApplicationContext` 调用 `publishEvent(...)`，效果与本节设计意图一致，`getBean(Class)` 的其余用法（如 `ApprovalConfigService` 等业务 Bean 获取场景）不受影响、行为不变。

```java
public class ApprovalResultEvent extends ApplicationEvent {
    private final String bizType;
    private final String bizId;
    private final boolean approved;
    private final String comment;
    // 构造器/getter，无任何 org.flowable.* 依赖
}
```

监听器完成的工作到此为止——它不知道、也不关心谁会消费这个事件。`identity` 模块中的 `UserApprovalResultListener`（普通 Spring `@Component`）用 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` 监听 `ApprovalResultEvent`，按 `bizType` 过滤出 `USER_CREATE`/`USER_EDIT`，调用 `UserService` 内部的状态流转方法完成"待审批 → 已生效/已驳回"的落库。`approval` 包完全不知道 `identity` 包的存在，符合"监听器不能反向感知业务实体"的要求。

替代方案：监听器直接注入/查找 `UserService` 并调用。放弃原因：这会让审批引擎的监听器反向依赖具体业务 Service，一旦以后有第二个业务（如角色审批）接入，监听器要写一堆 `if (bizType == ...)` 的分支去调用不同 Service，且 Flowable 包直接 import 业务类型，违反防腐层方向。

### D6. 业务数据与审批运行时数据的间接关联

新增 `approval.link` 包下的表 `approval_biz_link(id, biz_type, biz_id, process_instance_id, status, created_time, updated_time)`，由 `FlowableApprovalGateway` 在 `start(...)` 成功后写入一行，在收到最终结果时更新 `status`。`identity` 的 `sys_user` / `user_pending_change` 表**不**新增任何 Flowable 相关列（不存 `process_instance_id`，不存 `task_id`）；`identity` 需要判断某个业务单据当前审批进度时，一律经 `ApprovalGateway.queryByBizKey(bizType, bizId)` 查询，由 `FlowableApprovalGateway` 内部完成 `biz_id -> process_instance_id -> Flowable 运行时/历史表` 的查找并转换成 `ApprovalInstanceView` 返回。

同时在 `runtimeService.startProcessInstanceByKey(...)` 时也会设置 Flowable 原生的 `businessKey`（`bizType + ":" + bizId`），仅作为在 Flowable 自带的运维/查询工具中定位流程实例的辅助手段，不作为任何业务查询路径的依据——业务查询路径永远走自维护的 `approval_biz_link` 表。这样即便未来更换工作流引擎，只需要新的引擎适配实现继续维护同样结构的 `approval_biz_link` 表，`identity` 一侧的调用方式不需要变化。

### D7. 事务边界与事件消费幂等性

- `UserService` 的新增/编辑方法整体 `@Transactional`：业务表写入与 `approvalGateway.start(...)`（内部包含 `approval_biz_link` 写入与 `runtimeService.startProcessInstanceByKey(...)`）在同一个 Spring 管理事务中；`application.yaml` 未为 Flowable 单独配置数据源，Flowable 默认复用应用的主 `DataSource`，因此可以共享同一个 `PlatformTransactionManager`，业务落库与流程启动要么一起提交、要么一起回滚。
- `UserApprovalResultListener` 用 `@TransactionalEventListener(AFTER_COMMIT)`：确保只有在触发该事件的 Flowable 事务（审批人完成最后一级任务）已经提交之后，才去更新业务状态，避免读到尚未提交、之后可能回滚的中间态。这个机制成立的前提是业务事务与 Flowable 引擎事务共用同一个 `PlatformTransactionManager`——本次选择让二者继续共用同一 `DataSource`，是在存储层面暂不追求"业务库与 Flowable 引擎库彻底隔离"这一目标下的权衡，而不是出于分库分表等物理扩展考虑（见 Risks 中对应条目）。
- 事件处理前先查当前业务状态，若已经不是"待审批"（例如重复消费同一事件），直接跳过，保证幂等。

### D8. 全局开关与审批链配置的存储

- `approval_switch`：单行总开关表（固定一行记录 `approval_enabled`），对应“可全局配置是否开启审批流程”这一按当前需求描述是**一个**总开关（不区分新增/编辑分别开关）。`ApprovalPolicy.isEnabled(String bizType)` 接口签名仍保留 `bizType` 参数，当前实现直接忽略该参数只读总开关；如果未来需要按业务类型拆分开关，只需要改动 `approval_switch` 表结构与 `ApprovalPolicy` 的实现，调用方（`UserService`）完全不用改。
- `approval_chain_config(id, biz_type, level_no, approver_user_id, created_time, updated_time)`，`(biz_type, level_no)` 唯一，按 `biz_type` 分别维护"用户新增""用户编辑"各自的审批链，由 `ApprovalConfigController` 提供给前端做增删改查（含校验：级别连续、审批人不能为空、同一 `bizType` 下允许同一人出现在不同级别）。

### D9. 审批人操作与待办查询

`ApprovalTaskController`（位于 `approval` 包，可以间接经 `engine/flowable` 但自身不直接 import `org.flowable.*`）提供：
- `GET` 我的待审批列表：`approvalGateway.listPendingTasks(approverUserId)`，内部由 `FlowableApprovalGateway` 用 `taskService.createTaskQuery().taskAssignee(approverUserId)...` 查询并转换为 `ApprovalTaskView`。
- `POST` 同意/驳回：`approvalGateway.act(new ApprovalActCommand(...))`，内部调用 `taskService.complete(taskId, variables)`，把 `AGREE/REJECT` 结果和意见写入流程变量供 `ApprovalActionTaskListener`/`completionCondition` 使用。

### D10. 前端技术方案（用户新增/编辑）

技术栈（已与用户确认）：Vue3（`<script setup>` 组合式 API）+ TypeScript + Vite（构建工具，Vue3 官方推荐标准，未额外征求意见）+ Element Plus（UI 组件库，表单/表格/弹窗直接复用其 `el-form`/`el-table`）+ Vue Router（页面路由）+ Axios（HTTP 客户端）。范围内页面只有"用户列表"和"用户新增/编辑表单"两个页面级组件，规模不足以引入 Pinia 等全局状态管理，页面内部状态用组合式函数（`composables`）承载即可；后续若前端页面增多（如接入审批配置、审批待办），再评估是否引入。

工程结构（`fronted/`）：

```
fronted/
  index.html
  vite.config.ts
  tsconfig.json
  package.json
  src/
    main.ts                       # 挂载 Vue 应用、Element Plus、Router
    router/index.ts               # /users（列表）、/users/new（新增）、/users/:id/edit（编辑）
    api/
      http.ts                     # Axios 实例：统一 baseURL、超时、响应拦截（按 RestResult<T> 的 code 判断成功/失败）
      user.ts                     # 用户管理接口封装：listUsers/getUser/createUser/updateUser，返回类型对应后端 DTO 的 TS 类型
    types/user.ts                 # 与后端 UserController 出入参一致的 TS 类型定义（手写维护，非自动生成，保持与后端 DTO 字段一致）
    views/
      UserListView.vue            # 用户列表页：表格 + "新增" 按钮 + 每行"编辑"入口，展示状态（已生效/待审批/已驳回）
      UserFormView.vue            # 新增/编辑共用的表单页：根据路由参数判断新增或编辑模式，提交后跳回列表并提示"已生效"或"已提交审批"
    components/                   # 表单页内如有可拆分的子组件（如状态标签 StatusTag.vue）放这里
```

与后端的对接方式：`api/user.ts` 中的方法直接对应 `identity` 模块的 `UserController` 接口（新增、编辑、列表、详情），请求/响应体的 TS 类型与后端 `RestResult<T>` 及各 DTO 字段保持一致；前端不感知、也不需要感知审批相关的任何接口或状态细节之外的信息——只需要展示后端返回的用户状态（已生效/待审批/已驳回），不在前端重新实现审批链、事件等逻辑，保持前端与"审批到底怎么做"完全解耦，只认后端返回的用户状态字段。

替代方案：直接用不带类型的 JavaScript + 手写 fetch。放弃原因：用户已选择 TypeScript + Element Plus 作为本次及后续前端工程的统一技术栈，此处遵循已确认的选型。

## 编码阶段补充说明（任务组 9.1 文档核对）

- **脚手架遗留缺口修复**：`application.yaml` 早已声明 `mybatis-plus.config-location: classpath:mybatis/mybatis.conf`，但该文件此前不存在（编码前 baseline 构建即因此 `FileNotFoundException` 失败，与本次改动内容无关）；`build.gradle` 此前未引入 `spring-boot-starter-validation`，导致 `jakarta.validation`（`@Valid`/`@NotBlank` 等）无法解析。两者均属于运行/编译前置条件缺失、不修复则本次任何代码都无法跑通，判断为必要修复而非扩大 `proposal.md` 定义的范围，编码阶段已一并补齐。
- **新增 `ApprovalTaskController`（对应 tasks.md 5.7）**：本文档 D9 已描述其职责，但最初的 `tasks.md` 任务组 1-6 遗漏了对应任务项，导致编码阶段一度缺失。已于任务组 8 端到端验证前补充 `tasks.md` 5.7 并经用户确认后实现，接口路径为 `GET /api/approval/tasks/{approverUserId}`（查待办）与 `POST /api/approval/tasks/act`（提交同意/驳回），与 D9 描述的职责边界一致。
- 其余实现细节层面的技术修正（`TaskListener` 实际包路径、`loopCounter` 变量作用域、`ApplicationEventPublisher` 获取方式、D3 防重复提交的实际生效机制）已在对应决策小节（D3/D4/D5）内以"实现落地说明"注明，均不改变 D1-D10 已确认的包结构、契约签名、事件驱动方式等架构决策。
- 任务组 8 端到端验证已使用真实本地 MySQL/Redis 完整跑通：开关关闭直接生效（8.1）、`USER_CREATE` 3 级顺序审批全部同意后生效（8.2）、`USER_EDIT` 首级驳回后原始信息不变且未产生第二级任务（8.3），并额外通过前端 Vite dev server 代理对 `listUsers`/`getUser`/`createUser`/`updateUser` 四个接口做了真实联调（对应 tasks.md 7.3）。前端页面状态展示的可视化走查（tasks.md 7.6）因当前工具链没有浏览器自动化能力，未做到"实际打开页面肉眼确认"这一程度，仅完成了代码走查和编译验证，建议后续由人工在浏览器中做一次可视化确认。

## Risks / Trade-offs

- [Risk] 顺序多实例审批任务在 `approverList` 为空或配置未完成时可能导致流程无法正常启动或卡死 → Mitigation：`FlowableApprovalGateway.start(...)` 发起前校验审批链非空且级别连续，为空直接拒绝并提示需要先完成审批链配置（对应 spec 中"审批链未配置时开启审批开关"的场景）。
- [Risk] 监听器在 `SpringContextHolder` 尚未初始化时被调用会拿不到 `ApplicationEventPublisher`，导致审批结果事件丢失 → Mitigation：`SpringContextHolder.getBean` 在未初始化时直接抛出明确异常而不是静默返回 null，让问题在测试/联调阶段尽早暴露；正常运行时流程只会在应用完全启动、接收业务请求之后才会被发起，不存在上下文未就绪的窗口期。
- [Risk] `@TransactionalEventListener(AFTER_COMMIT)` 依赖业务事务与 Flowable 引擎事务共用同一个事务管理器。这里真正要关注的是**业务与 Flowable 引擎在存储层面的隔离程度**（即防腐层的边界是否要从"代码层不依赖 Flowable 类型"延伸到"数据层业务表与 Flowable 运行时表也分开存放/分开管理"），而不是为了分库分表等物理扩展目的去拆分数据源——本次为了让 `AFTER_COMMIT` 这种轻量事件投递机制可以直接工作，选择让业务表与 Flowable 的 `ACT_*` 运行时表暂时留在同一个数据源里 → 记录为已知限制：若后续出于业务隔离的目标要把 Flowable 迁到独立的数据源/Schema，需要重新设计事件投递方式（如改为可靠消息表 Outbox + 轮询/MQ，不再依赖共享事务保证投递时机），不在本次范围内处理。
- [Trade-off] 自建 `approval_biz_link` 关联表而不是只依赖 Flowable 原生 `businessKey`，多了一张表和一次写入，换来的是业务查询路径与具体引擎实现彻底解耦、且不受 Flowable 历史数据保留策略影响，符合防腐层目标，判断为值得。
- [Risk] 全局开关目前是单一总开关而非按业务类型（新增/编辑分别控制），若后续需求变成"只审批编辑、不审批新增"，当前数据模型需要调整 → Mitigation：`ApprovalPolicy` 接口已预留 `bizType` 参数，改动只发生在 `approval_switch` 存储结构与其实现内部，`UserService` 调用方式不受影响。

## Open Questions

（无会影响本次 specs、方案选择或任务拆分的遗留未决问题。审批人到期提醒、消息通知渠道等增强能力留待后续变更按需提出。）
