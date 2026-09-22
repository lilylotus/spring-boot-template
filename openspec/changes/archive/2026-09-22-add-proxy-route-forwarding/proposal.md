# Proposal

## Why

当前网关没有任何本地路由定义，`openspec/specs/` 也没有描述过网关的转发行为。需要一条统一的反向代理入口：调用方访问网关的 `/proxy/**`，由网关转发到注册在 Nacos 上的 `BootDemo` 服务，并且下游拿到的是不带 `/proxy` 前缀的原始业务路径、以及调用方最初请求的 `Host` 头。

## What Changes

- 在 `src/main/resources/application.yaml` 中新增一条网关路由 `proxy-boot-demo`：
    - 谓词 `Path=/proxy/**`
    - 目标 `lb://BootDemo`（走 Nacos 服务发现 + spring-cloud-loadbalancer）
    - 过滤器 `StripPrefix=1`，转发时去掉 `/proxy` 前缀
    - 过滤器 `PreserveHostHeader`，转发时保留调用方原始 `Host` 头，而不是替换成下游实例的 host:port
- 使用 Spring Cloud Gateway 4.3 的新配置前缀 `spring.cloud.gateway.server.webflux.routes`；旧前缀 `spring.cloud.gateway.routes` 自 4.3.0 起已标记 deprecated。
- 不新增 Java 代码，不改动 `TraceFilter`、Feign 相关类。

## Capabilities

### New Capabilities

- `gateway-routing`: 网关的 HTTP 路由转发行为——路径匹配、前缀剥离、下游服务解析、转发时的请求头处理。

### Modified Capabilities

（无。`openspec/specs/` 目前为空，本次是该能力的首次落地。）

## Impact

- **代码**：仅 `src/main/resources/application.yaml`。
- **依赖**：无新增依赖。`spring-cloud-starter-gateway-server-webflux`、`spring-cloud-starter-loadbalancer`、`spring-cloud-starter-alibaba-nacos-discovery` 已在 `build.gradle` 中。
- **运行时前置条件**：`BootDemo` 必须已注册到 Nacos（`10.4.100.125:8848`），否则该路由返回 503。
- **配置优先级风险**：`spring.config.import` 引入的 Nacos `CloudGateway.yml` / `CloudGateway-prod.yml` 优先级高于本地 `application.yaml`。YAML List 属性不会合并，若 Nacos 侧也定义了 `spring.cloud.gateway.server.webflux.routes`，本地这条路由会被整体覆盖。本次按确认方案先落本地，需要线上生效时同一段配置要同步到 Nacos。
- **链路追踪**：`TraceFilter` 排在 `HIGHEST_PRECEDENCE`，早于网关路由过滤器，`X-Trace-Id` 会随代理请求一起转发到 `BootDemo`，本次不需要额外改动。
