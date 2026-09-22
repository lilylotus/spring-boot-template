# Design

## Context

动机见 `proposal.md` - Why。与实现相关的现状约束：

- 依赖实际解析版本：`build.gradle` 声明 `spring-cloud-starter-gateway-server-webflux:4.3.4`，但被 `spring-cloud-dependencies:2025.0.3` BOM 覆盖，实际解析为 **4.3.5**（Gradle 缓存中已确认）。注意项目 `CLAUDE.md` 里"依赖是 `spring-cloud-starter-gateway:3.1.10`"的描述已过期。
- `spring-cloud-starter-loadbalancer` 与 `spring-cloud-starter-alibaba-nacos-discovery` 均在 classpath 上，`spring.cloud.nacos.discovery.enabled: true`，因此 `lb://` scheme 可用。
- `Template4Client` 已用 `@FeignClient(name = "BootDemo")`，说明 `BootDemo` 就是注册在 Nacos 上的服务名。
- 本地 `application.yaml` 目前只有引导配置，没有任何 `spring.cloud.gateway.*`；`openspec/specs/` 为空。
- `spring.config.import` 引入的 Nacos 配置优先级高于本地 `application.yaml`。

## Goals / Non-Goals

**Goals:**

- 用纯配置（YAML）实现路由，不引入 Java 配置类或自定义 `GlobalFilter`。
- 使用 4.3 的现行配置前缀，避免落地即产生 deprecation 债务。

**Non-Goals:**

- 不做鉴权、限流、熔断、重试、超时定制。
- 不启用 `discovery.locator`（按服务名自动建路由）——本次只要一条显式路由。
- 不改 Nacos 上的配置（本地不可达），也不改造 `TraceFilter` / Feign 相关代码。
- 不为该路由写集成测试（需要真实 `BootDemo` 实例，见 Risks）。

## Decisions

### D1：配置前缀用 `spring.cloud.gateway.server.webflux.routes`

Gateway 4.3.0 起把 `spring.cloud.gateway.routes` 标记为 deprecated，替代项为 `spring.cloud.gateway.server.webflux.routes`（已在 4.3.5 的 `spring-configuration-metadata.json` 中核实 `deprecation.replacement` / `since: 4.3.0`）。

- 备选：继续用旧前缀。旧前缀仍能工作，但启动会有 deprecation 警告，且本项目是模板仓库，应给出正确示范。**否决。**

### D2：目标地址用 `lb://BootDemo`，而非固定 URL

走 Nacos 服务发现 + spring-cloud-loadbalancer，与 `Template4Client` 的寻址方式保持一致，实例扩缩容不需要改配置。

- 备选：`uri: http://10.4.100.130:8080`。硬编码实例地址，不满足 spec 中"通过服务发现解析"的要求。**否决。**
- 备选：开启 `discovery.locator.enabled`，自动为每个 Nacos 服务生成 `/{serviceId}/**` 路由。前缀形态是 `/BootDemo/**` 而不是 `/proxy/**`，且会把所有服务都暴露出去，不符合需求。**否决。**

### D3：去前缀用 `StripPrefix=1`，而非 `RewritePath`

`/proxy/**` 是固定的单段前缀，`StripPrefix=1` 语义最直白。

- 备选：`RewritePath=/proxy/?(?<segment>.*), /$\{segment}`。正则更灵活但更易写错（YAML 里还要转义 `$`），本场景没有额外收益。**否决。**
- 边界行为（读 `StripPrefixGatewayFilterFactory` 源码确认）：`/proxy` 与 `/proxy/` 都会被改写成 `/`；路径末尾的 `/` 在结果路径长度大于 1 时才保留。

### D4：保留 Host 用路由级 `PreserveHostHeader` 过滤器

Gateway 默认由 Netty 用下游实例的 `host:port` 重写 `Host` 头。`PreserveHostHeader` 过滤器设置 `PRESERVE_HOST_HEADER_ATTRIBUTE`，`NettyRoutingFilter` 据此保留原始 `Host`。

- 备选：放进 `spring.cloud.gateway.server.webflux.default-filters` 全局生效。会影响将来新增的每一条路由，超出本次范围。**否决——挂在路由上，作用域最小。**

### D5：路由定义先落本地 `application.yaml`

已与用户确认。本地一份既是模板默认值，也保证 Nacos 不可达时该路由仍然生效。

## 目标配置形态

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          routes:
            - id: proxy-boot-demo
              uri: lb://BootDemo
              predicates:
                - Path=/proxy/**
              filters:
                - StripPrefix=1
                - PreserveHostHeader
```

## Risks / Trade-offs

- **Nacos 覆盖本地路由** → YAML List 属性不合并，Nacos 上若也有 `...server.webflux.routes`，本地这条会被整体覆盖。缓解：D5 已记录；上线时把同一段配置同步到 Nacos `CloudGateway.yml`，并在验证时用 actuator / 日志确认实际生效的路由。
- **保留 Host 可能打乱下游的域名相关逻辑** → 下游若按 `Host` 做多租户路由或生成绝对 URL，看到的将是网关域名而不是自身地址。这正是本需求要的效果，但需知悉；若某天要回退，删掉 `PreserveHostHeader` 一行即可。
- **无法写自动化集成测试** → 该路由依赖真实注册的 `BootDemo` 实例，现有测试上下文（`CloudGatewayApplicationTest` 等）不具备。缓解：改为在 tasks 中用"配置能被正确绑定 + 手工验证"两步覆盖，不为凑测试而 mock 掉整条链路。
- **`/proxy` 前缀与未来网关自身接口冲突** → 目前网关自身只有 `/welcome`，无冲突；后续新增本地 Controller 时避开 `/proxy`。

## Migration Plan

纯新增配置，无数据迁移。回滚方式：删除 `spring.cloud.gateway.server.webflux.routes` 下的 `proxy-boot-demo` 条目并重启。
