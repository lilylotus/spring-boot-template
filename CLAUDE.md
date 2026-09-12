# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目结构约定

本仓库是前后端分离的单体仓库（monorepo），两个子目录相互独立，没有统一的顶层构建工具串联：

- `backend/` — 后端代码，Spring Boot + Flowable 工作流引擎项目（Gradle 构建）。
- `fronted/` — 前端代码（目录名如此拼写，非笔误，请勿擅自改名为 `frontend`）。目前为空目录，尚未初始化任何前端工程。
- `openspec/` — OpenSpec 规范驱动变更管理配置（`config.yaml`），配合 `.claude/commands/opsx/*.md`（`propose` / `apply` / `archive` / `sync` / `explore`）使用。本项目的变更需遵循 proposal → design → tasks → 人工确认 → 编码的流程。

所有面向用户/仓库产出的文档（如 OpenSpec 的 proposal.md、design.md、tasks.md、PR 描述等）必须使用中文撰写。

## 强制约束：编码前必须先有 OpenSpec 文档

- 任何编码、功能实现、修改、重构、修复，动手写代码之前，必须先在 `openspec/` 下生成/更新对应变更的 `proposal.md`、`design.md`、`tasks.md` 三份文档，明确目标、范围、设计方案与任务拆分。
- 三份文档写完/改完后必须停下来，等待用户人工确认（如“确认”“可以开始编码”等明确指令），不得因为文档已完成就视为已获批准、自动进入编码阶段（详见用户全局规则 `~/.claude/rules/openspec-workflow.md`）。
- 编码前后都要同步比对「代码实现」与「`proposal.md`/`design.md`/`tasks.md`」是否一致：
  - 编码前：确认待执行的改动范围、设计与 `tasks.md` 列出的任务一致，且文档与当前代码库现状（含此前已合并的其他改动）没有偏差。
  - 编码后：改动落地后需回头核对实现是否仍与文档描述的方案、范围一致；如实现过程中不得不偏离已确认的设计，须先停止编码、更新 OpenSpec 文档并重新等待人工确认，禁止直接按新方案继续写代码。
- 不得擅自扩大 `proposal.md` 定义的需求范围，不得添加 `tasks.md` 未包含的大规模重构任务。

## Agent 分工

本仓库配置了两个专用编码 agent（定义见 `.claude/agents/`），编码阶段（即完成 OpenSpec 人工确认之后）按目录派发给对应 agent，主对话不直接改写 `backend/`、`fronted/` 下的业务代码：

- `backend-agent`：负责 `backend/` 目录下的 Spring Boot（Java）后端编码。
- `frontend-agent`：负责 `fronted/` 目录下的 Vue3 前端编码。

两者职责互不重叠，改动范围严格限定在各自目录内。当已确认的 `tasks.md` 中同时包含前端和后端任务，且两部分改动互不依赖（不需要等待对方产出如接口约定才能开工）时，应在同一次调用中并行派发给 `frontend-agent` 和 `backend-agent` 同时进行，而不是串行等待其中一个完成后再开始另一个；若任务之间存在依赖（如前端需要后端接口先落地），则按依赖顺序串行派发。

## 常用命令（backend）

在 Windows 环境下于 `backend/` 目录内执行（也可用 `gradlew` 于 Git Bash/WSL）：

```powershell
# 构建
.\gradlew.bat build

# 启动应用（默认端口 54100）
.\gradlew.bat bootRun

# 运行全部测试
.\gradlew.bat test

# 运行单个测试类
.\gradlew.bat test --tests "com.example.template.TemplateApplicationTests"
```

Java 工具链固定为 21（见 `build.gradle` 的 `toolchain.languageVersion`）。Gradle 依赖仓库优先走阿里云镜像（`https://maven.aliyun.com/repository/public/`），其后才是 Maven Central。

前端（`fronted/`）目前没有代码和构建脚本，尚无可执行命令。

## 后端架构（backend）

- Gradle 单模块项目，`rootProject.name = FlowableSpringBootTemplate`，基础包名 `com.example.template`。
- 核心技术栈：
  - Spring Boot 3.5.16 + Spring Web。
  - **Flowable 7.2.0**（`flowable-spring-boot-starter` + `flowable-spring-boot-starter-process`）：BPMN 流程引擎，是本项目的核心能力，新增业务功能时优先考虑是否需要与流程实例/任务交互。
  - **MyBatis-Plus 3.5.16**（`mybatis-plus-spring-boot3-starter` + `mybatis-plus-jsqlparser` 分页插件依赖）+ `mybatis-spring`：数据访问层。Mapper XML 位置为 `classpath*:mybatis/mapper/*.xml`，主配置文件为 `classpath:mybatis/mybatis.conf`（均在 `application.yaml` 中的 `mybatis-plus` 节点声明）。
  - **Flyway**（`flyway-mysql`）：数据库版本管理，迁移脚本须放在 `src/main/resources/db/migration`，命名规范为 `V<版本号>__<描述>.sql`；`baseline-on-migrate: true`。
  - MySQL（`mysql-connector-j`）、Redis（`spring-boot-starter-data-redis` 系列，`spring.data.redis` 配置）。
  - MapStruct 1.6.3 做对象映射（`src/main/generated` 为其生成代码输出目录）。
  - Lombok、Apache Commons Lang3、Apache HttpClient 4.5（用于同步 HTTP 调用第三方接口，如钉钉）。
- 统一响应结构：`common/BaseResponse`（code/traceId/timestamp/error 等公共字段）与 `common/RestResult<T>`（继承 `BaseResponse`，`success(T)` 固定 `code="0"`，`failure(String)` 固定 `code="500"`）。新增接口应复用 `RestResult<T>` 作为返回类型。
- `util/JacksonUtils`：项目内部统一的 JSON 序列化工具（独立于 Spring 自动装配的 `ObjectMapper` Bean，只影响内部序列化场景，不影响 `@RestController` 接口的实际 JSON 输出格式）。日期统一格式为 `yyyy-MM-dd HH:mm:ss` / `yyyy-MM-dd` / `HH:mm:ss`，忽略未知字段，序列化时跳过 null 字段。
- `util/SimpleHttpClientUtils`：基于连接池的 Apache HttpClient 封装，**默认禁用 SSL 证书校验**（`DEFAULT_DISABLE_SSL_VALIDATION = true`，见 `DisabledValidationTrustManager`）。在新代码中复用该工具调用外部服务时需留意此默认行为是否符合安全要求。
- `controller/SimpleController` 目前是早期脚手架/联调用的示例接口集合（钉钉 OAuth 登录、数据同步回调等），其中硬编码了钉钉 `appKey`/`appSecret` 等疑似敏感信息。修改此类或参考其写法新增接口时：
  - 不要把真实密钥继续硬编码进源码，应改为从配置/密钥管理读取。
  - 该类当前直接在 Controller 内编写了业务逻辑（OAuth 请求编排等），不是本项目 Controller 层应遵循的目标写法，新增接口不要照搬这种结构。

## 配置说明（`backend/src/main/resources/application.yaml`）

- `server.port: 54100`。
- `spring.datasource` / `spring.data.redis` 中的连接信息均为**本地开发占位值**（`localhost:3306/flowable`、`root/mysql`；Redis `10.10.88.31:6379` db 9），仅用于离线编译和脚手架联调，各环境需替换为真实连接信息，禁止把生产凭据提交到仓库。
- 数据源 URL 中的 `nullCatalogMeansCurrent=true` 是刻意配置：避免 Flowable 在同一 MySQL 实例内的其他库中发现 `ACT_*` 表后，误判当前库已初始化而跳过应有的升级脚本，修改数据源配置时不要遗漏此参数。
- `logging.level.com.example: debug`：项目基础包的默认日志级别为 debug。

## 当前进度

- Git 仓库尚无任何提交（`master` 分支为空历史）。
- `backend/` 目前只有应用入口、通用响应类、两个工具类和一个示例 Controller，尚无实际业务模块（Service/Mapper/Entity/DTO 等分层尚未建立）、也没有针对 Flowable 流程的具体集成代码。
- `fronted/` 尚为空目录，前端技术选型和工程结构均未确定。
