# Repository Guidelines

## 项目结构与模块组织

本仓库是前后端分离的单体仓库，顶层没有统一构建命令。`backend/` 是 Java 21、Spring Boot、Flowable、MyBatis-Plus 后端；业务代码位于 `src/main/java/com/example/template`，配置、BPMN 和 Flyway 脚本位于 `src/main/resources`，测试位于 `src/test/java`。`fronted/`（目录名为既定拼写，请勿改名）是 Vue 3 + TypeScript + Vite 应用；页面、组件、API 和类型分别放在 `src/views`、`src/components`、`src/api`、`src/types`，静态资源放在 `public/`。`openspec/changes/` 保存需求提案、设计、任务和规格。

## 构建、测试与本地开发

- `cd backend; .\gradlew.bat build`：编译后端并运行测试。
- `cd backend; .\gradlew.bat bootRun`：在默认端口 `34567` 启动 API。
- `cd backend; .\gradlew.bat test`：运行 JUnit 5 测试；可加 `--tests "com.example.template.identity.UserServiceImplTest"` 定向执行。
- `cd fronted; npm ci; npm run dev`：安装锁定依赖并启动 Vite；`/api` 会代理到后端。
- `cd fronted; npm run build`：执行 TypeScript 检查并生成生产构建。

## 编码风格与命名

Java 使用 4 空格缩进，包名全小写，类型使用 `PascalCase`，方法和字段使用 `camelCase`；Controller、Service、Mapper、Entity、DTO 按现有分层放置。Vue 单文件组件使用 `<script setup lang="ts">`、2 空格缩进、无分号；组件文件使用 `PascalCase.vue`，组合式函数使用 `useXxx.ts`。Flyway 文件遵循 `V<序号>__<说明>.sql`。仓库未配置独立格式化或 lint 命令，提交前应保持邻近代码风格。

## 测试指南

后端使用 Spring Boot Test、JUnit 5 和 AssertJ。测试类以 `Test` 结尾，测试方法采用 `operation_condition_expectedResult` 格式，并覆盖成功、失败和幂等路径。涉及数据库的集成测试优先使用 `@Transactional` 与 `@Rollback`。前端尚无测试框架；UI 改动至少运行 `npm run build` 并手工验证相关页面。

## 变更流程、提交与 PR

编码前先在 `openspec/changes/<change-name>/` 更新 `proposal.md`、`design.md`、`tasks.md`，获得人工确认后再实现；仓库文档和 PR 描述使用中文。Git 提交必须遵循 Angular 提交信息规范，页眉格式为 `<type>(<scope>): <subject>`，例如 `fix(frontend): 刷新操作人列表`；常用类型包括 `feat`、`fix`、`docs`、`refactor`、`test`、`chore`。每次提交都必须包含页眉，并在正文中使用 `1.`、`2.` 等有序列表逐项说明具体变更。PR 应说明目的、OpenSpec 路径、验证命令和配置/迁移影响；界面变更附截图，关联对应 issue，并避免混入无关重构。

## 安全与配置

不要提交真实数据库、Redis、OAuth 密钥或生产凭据。`application.yaml` 中的连接信息仅供本地开发；修改 MySQL URL 时保留 `nullCatalogMeansCurrent=true`。新增外部 HTTP 调用时，不要默认沿用禁用 SSL 校验的示例行为。
