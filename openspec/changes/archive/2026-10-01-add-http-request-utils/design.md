## Context

项目目前没有任何代码直接使用 `jakarta.servlet.http.HttpServletRequest`：`org.example.simple.http` 包是出站 HTTP 客户端封装（`HttpClients`/`HttpRequestData` 等），`ip-echo-page` 的真实 IP 回显逻辑用 Node.js 实现、不在本 Java 工程内。本次新增的 `HttpRequestUtils` 是工程内第一次使用 Servlet API 的工具类。

`jakarta.servlet.http.HttpServletRequest` 的类已随 `tomcat-embed-core:10.1.60`（已在 `build.gradle` 中声明）打包提供，无需新增 Gradle 依赖；但 `build.gradle` 中目前没有任何代码主动引用这些类，`tomcat-embed-core` 处于“已声明但未直接使用”的状态——本变更会让它第一次被 `main` 源码直接依赖。

`ip-echo-page` 的 OpenSpec 规范（`openspec/specs/ip-echo-page/spec.md`）已约定“IPv4-映射 IPv6 地址仅返回 `::ffff:` 前缀之后的 IPv4 部分”的规范化规则，本变更在 Java 侧复用同一规则，保持两处实现行为一致。

## Goals / Non-Goals

**Goals:**
- 提供一个无状态静态工具类，统一获取访问者真实 IP、指定请求头、指定 Cookie 值。
- 真实 IP 解析覆盖常见反向代理场景（单级/多级 `X-Forwarded-For`、未知代理追加 `unknown`），并复用项目已有的 IPv4-映射 IPv6 规范化规则。
- 不引入新的第三方依赖。

**Non-Goals:**
- 不提供 Spring MVC 集成（如 `HandlerInterceptor`、参数解析器、`ThreadLocal` 当前请求上下文），本变更只提供以 `HttpServletRequest` 为显式入参的纯函数式方法。
- 不做 IP 地址合法性校验、地理位置解析、黑白名单等增值能力。
- 不处理 Cookie 的写入/删除（`HttpServletResponse` 相关操作），只读取请求中已有的 Cookie。
- 不改造 `ip-echo-page` 的 Node.js 实现使其调用本工具类（两者运行时环境不同，没有共享收益）。

## Decisions

- **请求类型选用 `jakarta.servlet.http.HttpServletRequest`**：这是 Java 生态读取入站 HTTP 请求（IP/Header/Cookie）的标准接口，且已通过 `tomcat-embed-core` 可用，无需新增依赖。替代方案是自定义一个最小请求抽象（类似 `org.example.simple.http.HttpRequestData`，但那是为出站客户端调用设计的"请求描述"，字段语义是"要发送什么"，而不是"收到了什么"，直接复用会混淆两种语义，故不采用。
- **真实 IP 请求头优先级固定、不可配置**：按 `X-Forwarded-For` → `X-Real-IP` → `Proxy-Client-IP` → `WL-Proxy-Client-IP` → `HTTP_CLIENT_IP` → `HTTP_X_FORWARDED_FOR` → `request.getRemoteAddr()` 的固定顺序解析，与业界常见做法（Nginx/Apache 反向代理场景）一致。考虑过做成可配置的头名称列表，但当前没有多反向代理厂商共存的实际需求，先按固定顺序实现，YAGNI。
- **`X-Forwarded-For` 多值取第一个非 `unknown` 的 IP**：该头可能是逗号分隔的地址链（`client, proxy1, proxy2`），约定首个有效地址即访问者真实地址；取值前对每段 `trim()` 并忽略大小写判断是否为字面量 `unknown`。
- **IPv4-映射 IPv6 规范化**：解析结果若以 `::ffff:`（大小写不敏感）开头，仅返回该前缀之后的内容，与 `openspec/specs/ip-echo-page/spec.md` 中“规范化 IPv4-映射 IPv6 地址”场景保持一致的用户可见行为。
- **参数校验与异常语义**：`request` 为 `null`，或 `getHeader`/`getCookieValue` 的 `name` 为 `null`/空白，均直接抛出 `IllegalArgumentException` 并说明具体参数；不做"空进空出"，因为调用方传入 `null` 请求通常意味着编码错误而非合法的空结果场景（与仓库里 `JacksonUtils.fromJson` 对必需参数的处理方式一致）。
- **未命中结果返回 `null` 而非 `Optional`**：`getHeader`/`getCookieValue`/`getClientIp` 在找不到有效值时返回 `null`（`getClientIp` 兜底 `request.getRemoteAddr()`，理论上不会为 `null`），与仓库现有工具类（`JacksonUtils` 等）均未使用 `Optional` 作为返回类型的既有风格保持一致。

## Risks / Trade-offs

- **`X-Forwarded-For` 等请求头可被客户端伪造**：工具类本身不做可信代理校验（如校验 `getRemoteAddr()` 是否属于可信网段才采信转发头），存在 IP 伪造风险。该校验依赖具体部署环境（是否存在反向代理、可信网段是多少），不适合在通用工具类中硬编码，调用方如需防伪造应在业务层增加可信代理白名单校验；本变更的 Javadoc 会明确说明这一点。
- **`tomcat-embed-core` 从"已声明未使用"变为"被 main 源码直接依赖"**：如果后续该依赖版本调整或被移除，会直接影响编译，需要在变更评审时确认这一点可接受。
