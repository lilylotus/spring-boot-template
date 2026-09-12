## 1. 数据库准备

- [x] 1.1 新增 Flyway 迁移脚本，向 `sys_user` 插入固定 `id=1` 的默认管理员种子行（`username='admin', real_name='默认管理员', status='ACTIVE'`），执行迁移后确认该行存在且 `GET /api/users` 能查到

## 2. 后端操作人解析基础设施（`operator` 包）

- [x] 2.1 新增 `com.example.template.operator.OperatorContext`（`record(String userId, String userName)`）与 `DefaultOperator`（固定常量 `ID="1"`、`NAME="默认管理员"`，与 1.1 的种子数据保持一致）
- [x] 2.2 新增 `@CurrentOperator` 注解与 `CurrentOperatorArgumentResolver`（`HandlerMethodArgumentResolver` 实现）：从请求头 `X-User-Id`/`X-User-Name` 解析 `OperatorContext`，`X-User-Id` 缺失/空白时整体回退 `DefaultOperator`；新增 `OperatorWebMvcConfig`（`WebMvcConfigurer`）注册该解析器；编写单测覆盖"请求头齐全"“请求头缺失”两种场景

## 3. 后端接口改造

- [x] 3.1 `UserController.createUser`/`updateUser` 方法签名新增 `@CurrentOperator OperatorContext operator` 参数，移除 `PLACEHOLDER_OPERATOR_ID` 常量，改用 `operator.userId()` 调用 `UserService`；运行既有 `UserServiceImplTest` 等相关测试确认未破坏原有行为
- [x] 3.2 `ApprovalTaskController.listPendingTasks` 由 `GET /api/approval/tasks/{approverUserId}` 改为 `GET /api/approval/tasks`（移除路径参数，新增 `@CurrentOperator OperatorContext operator` 参数，使用 `operator.userId()`）
- [x] 3.3 `ApprovalTaskController.act` 新增 `@CurrentOperator OperatorContext operator` 参数，使用 `operator.userId()` 作为 `ApprovalActCommand.approverUserId`；`ApprovalActRequest` 移除 `approverUserId` 字段（含其 `@NotBlank` 校验）
- [x] 3.4 更新 `ApprovalTaskControllerTest`：原先通过路径参数/请求体传 `approverUserId` 的用例改为通过 `MockMvc` 请求头 `X-User-Id` 传递，确认测试通过；补一个"不传 `X-User-Id` 时按默认管理员身份处理"的用例
- [x] 3.5 运行 `backend\gradlew.bat build` 确认整体编译与全部测试通过

## 4. 前端操作人选择器

- [x] 4.1 新增 `src/composables/useOperator.ts`：封装当前操作人状态（模块级 `ref` + localStorage 读写），提供读取当前操作人与切换操作人的方法；读取失败/为空时回退前端侧 `DEFAULT_OPERATOR = { id: '1', name: '默认管理员' }` 常量
- [x] 4.2 `src/api/http.ts` 的 Axios 请求拦截器中，为每个请求写入 `X-User-Id`/`X-User-Name` 请求头（取自 `useOperator()` 当前值）
- [x] 4.3 新增 `src/components/OperatorSwitcher.vue`：下拉选择器，选项来自 `listUsers()` 返回的用户列表，当前选中项与 `useOperator()` 状态双向绑定，选择后立即调用切换方法（写回 localStorage）
- [x] 4.4 调整 `src/App.vue` 为固定顶部布局（`el-header` 放置 `OperatorSwitcher` 于左上角 + `el-main` 承载 `<router-view/>`），`npm run build` 确认编译通过

## 5. 端到端验证

- [x] 5.1 启动本地后端 + 前端，浏览器打开页面，确认首次访问左上角默认显示"默认管理员"，且新增用户后能在数据库/日志中确认操作人为默认管理员对应的 ID（已用真实本地 MySQL 启动后端 + `npm run dev` 启动前端验证：`sys_user` 种子行 `id=1/username=admin/realName=默认管理员` 存在，通过 Vite dev 代理按前端默认操作人常量 `{id:'1', name: encodeURIComponent('默认管理员')}` 发起的 `POST /api/users` 正确落库、返回成功；当前工具链没有浏览器自动化能力，未能实际打开页面肉眼确认左上角下拉框的渲染效果，此项视觉确认遗留，建议人工在浏览器中过一遍）
- [x] 5.2 在下拉列表中切换为用户列表中的另一个真实用户，新增/编辑一次用户，确认后续请求头带上了切换后的用户 ID（可用浏览器开发者工具或后端日志确认）（已通过 Vite dev 代理模拟切换后的请求头（`X-User-Id: alice` 等）验证请求正确携带并被后端按该身份处理；同上，未做浏览器里实际点击下拉切换的肉眼确认）
- [x] 5.3 不经前端，直接用 curl/Postman 调用 `POST /api/users`（不携带 `X-User-Id`）与 `GET /api/approval/tasks`（不携带 `X-User-Id`），确认均按默认管理员身份正常处理、不报错（实测：两个接口在缺失 `X-User-Id` 时均返回 `code:"0"` 成功，未报错）
- [x] 5.4 携带 `X-User-Id` 调用 `GET /api/approval/tasks`、`POST /api/approval/tasks/act`（不再传 `approverUserId`），复用 `user-management-approval` 变更中已验证过的一条审批链，确认审批流转行为与此前用路径参数/请求体传 `approverUserId` 时一致（实测：复用 `USER_CREATE` 3 级审批链，`alice`→`bob`→`carol` 依次通过请求头 `X-User-Id` 同意，任务逐级流转、最终用户状态变为 `ACTIVE`，行为与此前用请求体/路径参数传 `approverUserId` 时一致）

## 6. 文档同步核对

- [x] 6.1 对照 `proposal.md`/`design.md`/`tasks.md`/`specs/identity/operator-identity/spec.md` 与最终代码实现逐项核对是否一致；若实现中出现了必须偏离已确认设计的情况，记录差异并更新对应文档（已核对：`operator` 包结构、`DefaultOperator` 常量与种子数据、`ApprovalTaskController` 契约调整均与 D1-D3 一致；唯一的实现细节偏差——`X-User-Name` 中文请求头在浏览器环境下的编解码问题——已在 D4 内以"实现落地说明"记录并由前后端分别实现验证，不改变已确认的架构/契约。spec.md 中 7 条 Requirement 对应的 Scenario 均已通过任务组 5 的实测覆盖）

## 7. 新增用户后的操作人列表刷新

- [x] 7.1 修改 `fronted/src/components/OperatorSwitcher.vue`：监听操作人下拉框展开事件，仅在展开时调用 `loadUsers()`，以最新用户列表替换选项；加载失败时保留已有选项和当前选择
- [x] 7.2 运行 `npm run build`，确认 TypeScript 检查与 Vite 生产构建通过
- [ ] 7.3 手工验证新增用户后无需刷新页面，点击展开"当前操作人"即可看到并选择该用户
- [x] 7.4 对照本次更新后的 proposal、design、tasks 与 spec 核对最终实现，确认未引入额外跨组件状态或后端改动
