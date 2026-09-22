# Tasks

## 1. 落地路由配置

- [x] 1.1 在 `src/main/resources/application.yaml` 的 `spring.cloud` 下新增 `gateway.server.webflux.routes`，按 design.md「目标配置形态」写入 `proxy-boot-demo` 路由（`uri: lb://BootDemo`、`predicates: Path=/proxy/**`、`filters: StripPrefix=1` 与 `PreserveHostHeader`）；验证：`./gradlew bootRun` 启动无 `Unknown property` / deprecation 警告，且日志中出现该路由的注册信息
- [x] 1.2 给新增配置块补中文行内注释（说明前缀剥离、保留 Host、以及"Nacos 同名配置会整体覆盖本地 routes"），并确认缩进为 2 空格、文件以 LF + 末尾换行结束，符合 `.editorconfig`；验证：`git diff` 中无 CRLF、无行尾空格

## 2. 配置绑定校验

- [x] 2.1 新增测试 `src/test/java/cn/nihility/gw/route/ProxyRouteConfigTest.java`，以 `@SpringBootTest`（`webEnvironment = NONE` 或复用现有上下文配置）注入 `GatewayProperties`，断言存在 id 为 `proxy-boot-demo` 的 `RouteDefinition`，其 `uri` 为 `lb://BootDemo`、谓词含 `Path=/proxy/**`、过滤器依次为 `StripPrefix=1` 和 `PreserveHostHeader`；验证：`./gradlew test --tests "cn.nihility.gw.route.ProxyRouteConfigTest"` 通过
- [x] 2.2 确认该测试上下文不会因 Nacos 不可达而失败（`spring.config.import` 用的是 `optional:` 前缀），必要时在测试上显式关闭 nacos config/discovery；验证：断网或 Nacos 不可达时该测试仍通过

## 3. 回归与手工验证

- [x] 3.1 跑全量测试确认未影响既有链路追踪与 Feign 用例；验证：`./gradlew test` 全绿
- [x] 3.2 在能连通 Nacos 且 `BootDemo` 已注册的环境手工验证 spec 的关键场景：`curl -H "Host: gateway.example.com" -H "X-Trace-Id: <32位十六进制>" http://localhost:13579/proxy/welcome` 返回下游 `/welcome` 的响应，下游日志显示路径为 `/welcome`、`Host` 为 `gateway.example.com`、traceId 一致；验证：记录一次实际请求与响应/日志片段
- [x] 3.3 验证下游不可用时的降级行为：停掉 `BootDemo` 实例后请求 `/proxy/welcome` 返回 503 且响应体不含内部堆栈；验证：记录状态码与响应体
- [ ] 3.4 把同一段 routes 配置同步到 Nacos `CloudGateway.yml`（或确认 Nacos 侧未定义 routes，本地配置即生效），避免线上被整体覆盖；验证：线上环境请求 `/proxy/welcome` 行为与本地一致

## 4. 文档同步

- [x] 4.1 在项目 `CLAUDE.md` 的 Architecture 小节补一句 `/proxy/**` → `BootDemo` 的本地路由说明，并顺带修正已过期的"依赖是 `spring-cloud-starter-gateway:3.1.10`"描述（实际为 `spring-cloud-starter-gateway-server-webflux`，BOM 解析到 4.3.5）；验证：`git diff CLAUDE.md` 内容与实际 `build.gradle`、`application.yaml` 一致
