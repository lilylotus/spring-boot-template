# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## What this is

A Spring Boot 3.5 "starter template" repo (Java 21, Gradle) meant to be copied/forked into real
services. It wires together the infra pieces a typical internal service needs — web, Nacos
config/discovery, OpenFeign, Redis, dual MyBatis setups, Log4j2 async logging with trace IDs,
Actuator/Prometheus, OpenAPI — and documents *why* each piece is configured the way it is via
extensive inline comments (mostly in Chinese). When editing config, preserve that comment style;
it's the main value of this repo as a template.

## Constraints (always apply)

1. **Before writing any code**, check whether the change already has an OpenSpec proposal:
   look for `proposal.md` / `design.md` / `tasks.md` under `openspec/changes/<change-id>/`. If
   they don't exist for the change you're about to make, create them first via the
   `openspec-propose` skill (or `openspec-update-change` if a proposal already exists but needs
   revising) rather than jumping straight into implementation. Only skip this for trivial fixes
   the user explicitly asked to do directly, with no spec involved.
2. **禁止在写完 OpenSpec 文档后自动接着写代码。** proposal/design/tasks 等文档写完（或改完）后必须
   停下来，把变更内容交给用户人工检查确认；只有在用户明确表示"确认"、"可以了"、"开始实现"之类的
   继续指令后，才能进入编码阶段。不能因为文档已经"生成完毕"、状态显示 `ready`/`All artifacts
   complete` 就默认视为已获批准而自行往下推进实现。
3. **Every Java class needs a class-level comment** explaining what it is/does. **Every non-trivial
   method** (business logic, non-obvious control flow, anything a reader would have to trace
   through to understand) needs a comment on the method and, where a snippet's intent isn't
   obvious from the code alone, inline comments explaining the execution logic — matching the
   density of comments already in this codebase (see `MybatisPlusConfig`, `TraceIdFilter`,
   `RedisConfig` for the expected style/depth). Getter/setter-style trivial code doesn't need this.
4. **所有编写的文档一律使用中文，不使用英文**（如 OpenSpec 的 `proposal.md`/`design.md`/`tasks.md`、
   代码注释、README 等说明性文档）。代码本身的标识符（类名、方法名、变量名）、日志格式、配置
   键名等技术标识符仍按语言/框架惯例使用英文，仅文档性文字内容需要用中文书写。
5. **Git 提交信息遵循 Conventional Commits 规范，且 `scope` 必填**：格式固定为
   `<type>(<scope>): <subject>`，不写成省略 `scope` 的 `<type>: <subject>`。常用 `type` 有
   `feat`（新功能）、`fix`（修复）、`docs`（仅文档改动，含 OpenSpec 文档）、`refactor`
   （不改变行为的重构）、`perf`（性能优化）、`test`（测试相关）、`build`（构建/依赖，如
   `build.gradle`）、`ci`、`chore`（杂项）、`revert`（回滚）。`scope` 用改动所在的模块/包名
   （如 `redis`、`thread-pool`、`mybatis`、`openfeign`、`config`、`openspec`、`gradle`
   等），改动横跨多个模块、找不到合适单一 `scope` 时才允许省略括号退化为 `<type>: <subject>`。
   破坏性变更在 `type`/`scope` 后加 `!`（如 `feat(redis)!:`）或在正文/脚注写
   `BREAKING CHANGE:` 说明。`type`/`scope`/`!` 这些是规范固定的英文关键字，不受第 4 条
   "文档一律中文"约束；`subject` 及提交正文（body/footer）按第 4 条用中文书写即可。一次提交
   只做一件逻辑上独立的事，避免把无关改动混进同一条提交信息里。
6. **提交信息中不要包含 `Co-Authored-By: Codex Sonnet 5 <noreply@anthropic.com>` 这类署名脚注**。
   这条覆盖 Codex 默认会在提交信息末尾追加该署名的行为——本仓库的提交信息到 `footer`
   为止即可，不再额外加这一行。

## Build, run, test

All commands via the Gradle wrapper (`./gradlew` on bash, `.\gradlew.bat` on PowerShell).

```
./gradlew build              # compile + test + assemble
./gradlew bootRun             # run the app locally (port 34567)
./gradlew test                # run all tests
./gradlew test --tests "com.example.template.mybatis.SystemMetaObjectTest"   # single test class
./gradlew test --tests "*SystemMetaObjectTest.testObject"                    # single test method
```

Java toolchain is pinned to 21 (`java.toolchain.languageVersion` in `build.gradle`); `gradle.properties`
also pins `org.gradle.java.home` to a local JDK path — update that if the JDK location differs on
your machine.

**Most `@SpringBootTest` tests require live local infrastructure**: MySQL on `127.0.0.1:3306`
(db `test`, user `root`/`mysql`), Redis on `127.0.0.1:6379` (db 5, password `redis`), and Nacos on
`127.0.0.1:8848` (config+discovery both `enabled: true` in `application.yml`, imported as
`optional:nacos:...` so the app still boots without it — but `@EnableDiscoveryClient` still needs it
for registration). Tests with no Spring context (e.g. `SystemMetaObjectTest`) run standalone.
`spring.profiles.active` is `prod` by default in `application.yml` — the datasource/redis values
there are actually local-dev placeholders despite the profile name.

There is no linter/formatter config beyond `.editorconfig` (4-space indent, LF, UTF-8; YAML uses
2-space). Java compilation uses `-Xlint:deprecation` and fails on bad encoding, not on lint warnings.

## Architecture

### Two parallel MyBatis stacks (intentional, for comparison/demo purposes)

- `mybatis.mapper` / `mybatis.entity` — plain MyBatis with hand-written SQL in
  `src/main/resources/mybatis/mapper/*.xml` (e.g. `BatchTestMapper` + `BatchTestMapper.xml`).
  Several methods (`query`, `queryOptimal`, `queryOptimalJoin`, `queryOptimalAssociate`,
  `queryOptimalPrimaryId`) exist to compare different pagination/join strategies — check the XML
  before assuming behavior from the method name alone.
- `mybatis.plus.mapper` — MyBatis-Plus `BaseMapper<T>` interfaces needing no XML
  (e.g. `BatchTestPlusMapper`).

Both are picked up by the single `@MapperScan(basePackages = "com.example.template",
annotationClass = Mapper.class)` in the application class — mappers from either stack just need
`@Mapper`. Global MyBatis settings (camelCase mapping, plugin registration) live in
`src/main/resources/mybatis/mybatis.conf`, loaded via `mybatis-plus.config-location` — MyBatis-Plus's
own settings (`global-config.db-config.id-type`, etc.) stay in `application.yml` since
`mybatis.conf` can't express them. `ExecutorDurationInterceptor` (a MyBatis plugin registered in
`mybatis.conf`, not a Spring bean) logs per-statement execution time. `MybatisPlusConfig` registers
the pagination interceptor required for `IPage`/`Page`-based queries to actually paginate.

### Trace ID propagation

`TraceIdFilter` (servlet filter) reads/generates `X-Trace-Id` and puts it in Log4j2's
`ThreadContext` (MDC) for the request. `OpenFeignConfig`'s `RequestInterceptor` reads it back out of
MDC and forwards it as a header on outgoing Feign calls, so trace IDs propagate across service
calls. `MdcTaskDecorator` / `MdcExecutorWrapper` carry the same MDC context across thread-pool task
boundaries (async work loses ThreadContext otherwise). Always clean up `ThreadContext` in a
`finally` block when adding new code that touches it — thread-pool reuse will otherwise leak trace
IDs across unrelated requests.

### Logging

Log4j2 (not Logback — excluded explicitly in `build.gradle`'s `configurations.configureEach`),
globally async via `AsyncLoggerContextSelector` set in `log4j2.component.properties` (backed by
LMAX Disruptor). `application.yml` points at `log4j2-spring.xml`; `log4j2-spring-async.xml` is an
alternate config with explicit per-appender `<Async>` wrappers — not currently referenced, kept as
a reference variant since the global async selector already makes everything async.

### Feign / OpenFeign

`OpenFeignConfig` forwards trace ID and `Authorization` header to downstream calls.
`FeignHttpClientConfig` (connection-pooled HttpClient5 wired as the Feign `Client`) has
`@Configuration` commented out and isn't `@Import`-ed anywhere — it's currently inactive/reference
code, not part of the live bean graph. If you need pooled HttpClient5 behavior for Feign, re-enable
this class deliberately rather than assuming it's already active.

### Redis

`RedisConfig` builds a `RedisTemplate<String, Object>` with string keys and Jackson JSON values,
using `activateDefaultTyping` restricted to `com.example` and `java.util` packages (polymorphic
type info embedded in stored JSON, scoped to avoid deserialization-gadget risk). Any new type meant
to be cached must live under `com.example` or be a `java.util` collection or it won't
deserialize correctly.

### API docs

springdoc-openapi 2.x (not 3.x — pinned because 3.x targets Spring Boot 4, this project is on 3.5).
Only scans `com.example.template.controller` (`springdoc.packages-to-scan`). Swagger UI at
`/swagger-ui.html`, "try it out" disabled by config.

### Actuator

Locked down via `management.endpoints.access.default: none` (allowlist model) — only
`health`, `info`, `prometheus`, `metrics` are exposed, each explicitly set to `unrestricted`. New
endpoints are hidden by default until both exposed and given an access level.

## OpenSpec

This repo has OpenSpec skills configured (`openspec-propose`, `openspec-apply-change`,
`openspec-update-change`, `openspec-sync-specs`, `openspec-archive-change`,
`openspec-explore`) but `openspec/changes/` and `openspec/specs/` are currently empty — no active
proposals to be aware of. Use the corresponding skill when asked to propose, implement, or archive
a spec-driven change rather than improvising the workflow.
