## Context

见 `proposal.md - Why`：项目尚未接入登录鉴权，`UserController` 目前用硬编码常量 `PLACEHOLDER_OPERATOR_ID = "system"`，`ApprovalTaskController`（`user-management-approval` 变更任务组 5.7 新增，尚无前端页面消费）要求调用方在路径参数/请求体中显式传 `approverUserId`。`identity`/`approval` 两个业务模块目前都只依赖各自的 Service/Gateway 接口，Controller 层没有任何"当前操作人"相关的公共基础设施。

`operatorId`/`approverUserId` 在现有代码中统一是 `String` 类型（`UserServiceImpl.createUser(request, String operatorId)`、`ApprovalActCommand.approverUserId(): String`），本次不改变这个约定，请求头 `X-User-Id` 的值按不透明字符串直接使用，不做数值校验/转换。

## Goals / Non-Goals

**Goals:**
- 后端提供一个与具体业务 Controller 解耦的"解析当前操作人"机制，`identity`/`approval` 的 Controller 都能复用，不重复写header 解析逻辑。
- 请求头缺失时有明确、稳定、可预测的默认行为（回退默认管理员），不因为联调时忘记传 header 而报错。
- 前端操作人选择是纯客户端行为（localStorage），不引入登录态、不新增后端会话概念。

**Non-Goals:**
- 不做任何身份鉴权/防篡改校验——`X-User-Id` 由前端自行携带，恶意调用方可以伪造任意操作人标识，这是本次明确接受的过渡期限制，真正的登录鉴权留待后续变更。
- 不涉及审批待办的前端页面（`user-management-approval` 变更已明确排除，本次也不新增）。
- 不改变 `identity`/`approval` 现有的业务规则、数据模型、事务边界。

## Decisions

### D1. 后端解析方式：自定义参数解析器（`HandlerMethodArgumentResolver`），不是拦截器/过滤器

新增 `com.example.template.operator` 包（与 `identity`/`approval` 平级的基础设施包，不属于任何一个业务模块，供两者共同依赖）：

```
operator/
  OperatorContext.java              record(String userId, String userName)：解析结果
  CurrentOperator.java               自定义注解，标注在 Controller 方法参数上
  CurrentOperatorArgumentResolver.java   HandlerMethodArgumentResolver 实现，解析 X-User-Id/X-User-Name，缺失时回退默认值
  DefaultOperator.java               默认管理员的固定常量（ID、姓名，见 D2）
  config/OperatorWebMvcConfig.java   WebMvcConfigurer，注册上述 ArgumentResolver
```

Controller 方法签名形如：

```java
@PostMapping("/api/users")
public RestResult<UserOperationResultVO> createUser(@Valid @RequestBody UserCreateRequest request,
                                                      @CurrentOperator OperatorContext operator) {
    return RestResult.success(userService.createUser(request, operator.userId()));
}
```

替代方案 1：`OncePerRequestFilter`/拦截器 + `ThreadLocal` 持有当前操作人，Service 内部主动读取。放弃原因：会让"操作人是谁"变成一个隐式的线程上下文，Service 方法签名看不出依赖关系，测试时容易忘记设置 ThreadLocal 导致读到脏数据；且本仓库 Spring Boot 规范要求 Controller 只做参数接收/校验/调用 Service，用显式方法参数更符合"职责清晰"的取向。

替代方案 2：Controller 直接注入一个 `OperatorResolver` Service，在方法体内调用 `resolver.resolve(request)`。放弃原因：仓库规范明确"Controller 只允许注入 Service 层接口"，`OperatorResolver` 不是业务 Service；参数解析器方案完全不需要 Controller 注入任何额外 Bean，签名上也更清楚地表达"这是一个框架自动填充的上下文参数"，和 `@Valid @RequestBody` 是同一层次的机制，对已有 Controller 侵入最小。

`CurrentOperatorArgumentResolver` 内部直接从 `HandlerMethodArgumentResolver.resolveArgument` 拿到的 `NativeWebRequest` 读取 `X-User-Id`/`X-User-Name` 两个 header；不查询数据库，不做任何校验，`X-User-Id` 缺失（`null` 或空白）时整体回退 `DefaultOperator` 常量（见 D2），不是"userId 用默认值、userName 单独判断"这种字段级别的部分回退。

### D2. 默认管理员：真实 `sys_user` 种子行 + 后端固定常量双写，不在缺省路径查库

新增 Flyway 迁移脚本，在 `sys_user` 插入一条固定 ID 的种子记录（`id=1, username='admin', real_name='默认管理员', status='ACTIVE'`）。`DefaultOperator` 类中同时硬编码 `ID = "1"`、`NAME = "默认管理员"` 两个常量，`CurrentOperatorArgumentResolver` 在请求头缺失时直接使用这两个常量，**不查询 `sys_user` 表**。

替代方案：缺省时查 `sys_user` 表按某个约定用户名（如 `admin`）查出记录再取其 ID/姓名。放弃原因：操作人解析是每个受影响接口的必经路径，为一个"缺省兜底"场景引入额外的数据库查询、且需要处理"种子数据被误删导致查不到"的异常分支，复杂度高于收益；直接固定常量与已知的种子数据 ID 保持一致即可，两者通过本设计文档和迁移脚本注释显式关联，若未来种子数据的 ID 需要变化，同步改这一个常量类。

`sys_user.id` 是自增主键，为保证种子行拿到确定的 `id=1`，迁移脚本需要在业务侧还没有任何其他新增用户请求之前执行（Flyway 迁移在应用启动时先于业务流量执行，天然满足）。

### D3. `ApprovalTaskController` 接口契约调整（无历史前端消费方，直接变更不做兼容）

- `GET /api/approval/tasks/{approverUserId}` → `GET /api/approval/tasks`（无路径参数，审批人身份完全来自 `@CurrentOperator`）。
- `POST /api/approval/tasks/act` 请求体 `ApprovalActRequest` 移除 `approverUserId` 字段（该字段此前是 `@NotBlank` 必填），审批人身份改由 `@CurrentOperator` 提供。
- `user-management-approval` 变更的 `ApprovalTaskControllerTest` 中直接调用这两个接口、手工构造 `approverUserId` 的测试用例需要同步改为通过请求头传递（`MockMvc` 的 `.header("X-User-Id", "alice")`）。
- 该接口目前没有任何前端页面消费（`user-management-approval` proposal 已明确前端范围不含审批待办页面），因此这是一次没有存量调用方的契约调整，不需要兼容期/双写。

### D4. 前端：全局布局 + Axios 请求拦截器 + localStorage，不新增后端可写状态

- `fronted/src/App.vue` 从"仅 `<router-view/>`"改为一个固定的顶部布局（如 `el-header` + `el-main`），左上角放置操作人下拉选择器组件（新增 `src/components/OperatorSwitcher.vue`），下拉数据源直接复用已有的 `listUsers()`（`GET /api/users`）。
- `OperatorSwitcher` 在首次挂载时加载用户列表，并监听 `el-select` 的展开事件；每次用户点击并展开下拉框时重新调用 `listUsers()`，用最新结果整体替换选项。这样新增用户后无需刷新页面即可立即出现在操作人列表中，也避免在用户管理页与全局布局之间增加事件总线或组件层级透传。收起下拉框时不发请求；刷新失败时保留上一次成功加载的选项与当前操作人，交由现有 Axios 响应拦截器提示错误。
- 新增 `src/composables/useOperator.ts`：封装"当前操作人"状态的读取（优先 localStorage，否则回退前端侧硬编码的 `DEFAULT_OPERATOR = { id: '1', name: '默认管理员' }` 常量，与后端 D2 的种子数据/常量保持一致）与写入（切换后立即写回 localStorage）。选择这种"读 localStorage 失败/为空就退回一份和后端约定好的默认值常量"的方式，而不是应用启动时先等 `GET /api/users` 返回、再从结果里挑出默认管理员那一条：这样首屏第一个业务请求（不管发生在用户列表加载完成之前还是之后）都能立即拿到一个有效的操作人 header，不需要等待任何网络往返。
- `src/api/http.ts` 的 Axios 实例新增请求拦截器：每次请求前从 `useOperator()` 读取当前操作人，写入 `X-User-Id`/`X-User-Name` 请求头。

  > **实现落地说明（编码阶段核实后补充）**：`X-User-Name` 的取值（如默认管理员姓名"默认管理员"）通常含中文，浏览器 `XMLHttpRequest.setRequestHeader`/`fetch` 只接受 ISO-8859-1（Latin-1）范围内的字符作为请求头值，直接写入非 ASCII 字符串会在运行时抛 `TypeError`，导致请求根本发不出去。修正为：前端写入前用 `encodeURIComponent` 对 `X-User-Name` 的值做百分号编码，后端 `CurrentOperatorArgumentResolver` 读取该请求头后用 `URLDecoder.decode(value, StandardCharsets.UTF_8)` 解码还原；`X-User-Id` 约定始终是数字 ID 的字符串形式，不含非 ASCII 字符，不需要编解码。此修正不改变 D1/D2/spec 中"`X-User-Name` 仅用于展示/审计、不参与业务判断"的既定行为，只是让这一行为在浏览器环境下能够正常发生。前端 `fronted/src/api/http.ts` 已实现。后端 `CurrentOperatorArgumentResolver` 解码逻辑已实现（含 `X-User-Name` 缺失时保持 `null`、解码失败兜底为原始值两个分支），并补充单元测试覆盖。
- 不引入 Pinia：`useOperator` 内部用一个模块级 `ref` 包一层，配合 localStorage 读写即可满足"全局唯一、跨组件共享"的需求，规模上不足以引入状态管理库（与 `design.md`（`user-management-approval` 变更）D10 的既有取向一致）。
- 选择结果不持久化到后端、不跨浏览器/跨设备同步，符合 proposal 中"过渡方案"的定位。

### D5. 安全边界（明确记录，不是遗漏）

`X-User-Id` 完全由前端自行携带、后端不做任何校验，任何知道接口地址的调用方都可以伪造任意操作人身份。这在当前"没有登录鉴权"的项目阶段是已知且接受的限制，proposal 的 Non-Goals 已明确排除鉴权；后续一旦接入真实登录鉴权，`CurrentOperatorArgumentResolver` 的实现需要替换为从登录态（Token/Session）解析，而不是继续信任请求头——`OperatorContext`/`@CurrentOperator` 这一层抽象保留，Controller 侧代码不需要改动，只需要替换 `operator` 包内部的解析实现，类似 `identity` 模块通过 `ApprovalGateway` 接口与 Flowable 解耦的思路。

## Risks / Trade-offs

- [Risk] 前端硬编码默认管理员的 `id='1'`/姓名与后端种子数据脱节（例如后续有人改了种子数据的 ID 或姓名，忘记同步前端常量）→ Mitigation：两处都在各自设计文档（本文件 D2/D4）中显式互相引用，且属于同一次变更一起落地、一起验证，非平行独立演进的模块。
- [Trade-off] `ApprovalTaskController` 直接做破坏性契约调整（移除 `approverUserId` 参数）而非新增一个平行接口保留旧签名兼容。判断为值得：该接口是本仓库最近一次变更中刚新增、尚无任何前端/外部调用方的内部接口，保留双签名只会增加维护成本，没有实际兼容收益。
- [Risk] 任何人构造请求头即可冒充任意操作人（见 D5），当前阶段判断为可接受的已知限制，非本次要解决的问题。
