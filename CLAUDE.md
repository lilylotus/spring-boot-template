# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库中工作时提供指引。

## 项目

Spring Cloud Gateway 模板服务（包名 `cn.nihility.gw`，构件名 `CloudGateway`）。Java 21、Spring Boot 3.5.16、Spring Cloud 2025.0.3、Gradle（Groovy DSL）。单模块，无子模块。

## 常用命令

本机 shell 是 PowerShell；`.\gradlew.bat` 和 `./gradlew` 都可以用。

```powershell
./gradlew build                 # 编译 + 测试 + 打包
./gradlew bootRun               # 启动网关（端口 13579）
./gradlew test                  # 跑全量测试
./gradlew test --tests "cn.nihility.gw.CloudGatewayApplicationTest"          # 跑单个测试类
./gradlew test --tests "cn.nihility.gw.CloudGatewayApplicationTest.main"     # 跑单个测试方法
./gradlew bootJar               # 产物：build/libs/CloudGateway-0.0.1-SNAPSHOT.jar
./gradlew dependencies --configuration runtimeClasspath   # 查看依赖树
```

项目没有配置 linter，代码风格只靠 `.editorconfig` 约束。

## 日志（Log4j2）

日志走的是 **Log4j2，不是 Logback**。有两处配置在支撑这件事，缺一不可：

1. `build.gradle` 通过 `configurations.configureEach` 全局排除了 `spring-boot-starter-logging`。不排除的话，`spring-boot-starter-web`/`webflux`/nacos 会传递引入 Logback 和 `log4j-to-slf4j`，启动时直接报 `log4j-slf4j2-impl cannot be present with log4j-to-slf4j` 而挂掉。**不要删掉那段排除配置。**
2. `src/main/resources/log4j2-spring.xml` —— 文件名带 `-spring` 后缀，这样 Spring Boot 的 `LoggingSystem` 才会接管加载，`logging.level.*` / `logging.file.path` 才继续有效。

异步日志用 `<AsyncLogger>`/`<AsyncRoot>`，底层依赖 `com.lmax:disruptor:4.0.0`。因为开了异步，配置里设了 `includeLocation="false"`：**如果你在 pattern 里加了 `%L`、`%M`、`%C` 或 `%l`，必须把它改成 `true`**，否则位置信息会打印成 `?`。

日志文件落在 `logs/` 目录（可用 `logging.file.path` 覆盖；XML 里读的是 Spring Boot 注入的 `LOG_PATH` 系统属性）：

- `logs/CloudGateway.log` —— root 级别的全部日志，归档到 `logs/archive/app/`
- `logs/CloudGateway-error.log` —— 只有 ERROR，归档到 `logs/archive/error/`

保留策略的开关都在 XML 顶部的 `<Properties>` 块里：单文件 200MB、按天或按大小滚动、每天最多 5 个文件（`DefaultRolloverStrategy max`）、保留 7 天、总数上限 35 个（由 `<Delete>` 动作控制）。两个 appender **特意**归档到不同子目录 —— 每个 `<Delete>` 都按 `basePath` 限定作用范围，这样 35 个文件的上限是按 appender 各算各的，glob 匹配也不会有歧义。

`logs/` 已被 gitignore，任何一次测试运行都会重新生成。`%15.15t` 会从左侧截断线程名，所以 `boundedElastic-1` 会打印成 `oundedElastic-1` —— 这是 pattern 的效果，不是 bug。pattern 里带了 `%notEmpty{[%X{traceId}] }`，所以启动阶段那些没有 traceId 的日志行不会渲染出一对空方括号。

## 请求链路追踪（traceId + 接口耗时）

`cn.nihility.gw.trace` 包实现了 correlation-id 式的链路追踪。`TraceFilter` 是一个 `WebFilter`，order 为 `HIGHEST_PRECEDENCE`，因此 traceId 在任何其他过滤器和网关自身的路由过滤器之前就已经存在。

每个请求它会复用入站的 `X-Trace-Id`（没有就生成一个 32 位十六进制的），改写*请求*头让网关把它透传给下游，同时回写到响应头，并在 `doFinally` 里打印耗时日志：

```
2026-09-22 00:24:51.735  INFO [flux-http-nio-2] [testtraceid0123456789abcdef01234] c.n.g.t.TraceFilter : GET /welcome cost [6]ms
```

这里用 `doFinally` 而不是 `then(...)`，是因为它在正常完成、异常**以及取消**三种情况下都会触发 —— 客户端中途断开连接时，耗时日志照样能打出来。

### 为什么光靠 MDC 不够

WebFlux 会把一个请求在多个线程之间搬来搬去，所以 MDC（本质是 ThreadLocal）单靠自己扛不住 traceId。真正的载体是 **Reactor Context**（`contextWrite(Context.of(TraceContext.TRACE_ID, traceId))`），再通过下面两样东西桥接回 MDC：

- `TraceIdThreadLocalAccessor` —— 一个以 `traceId` 为 key 的 `io.micrometer.context.ThreadLocalAccessor`，负责读写 MDC。它依赖 `io.micrometer:context-propagation`，这个依赖**不会**被传递引入，必须显式声明。
- `TraceConfiguration` 里的 `Hooks.enableAutomaticContextPropagation()` —— 让 Reactor 在操作符执行前后，在实际运行的那个线程上还原 MDC。这两件事都用 `AtomicBoolean` 兜底，每个 JVM 只做一次，因为这个 hook 是全局的，而测试会创建多个上下文。

少了任何一样，traceId 就只待在 Reactor Context 里，业务代码里普通的 `log.info` 打出来 `%X{traceId}` 是空的。

`TraceFilter.logCost` 仍然通过 `TraceContext.runWith(...)` 显式绑定了一次 traceId，因为 `doFinally` 回调并不保证 ThreadLocal 已被还原（尤其是取消的场景）。

### Reactor 之外的跨线程传递

对于普通线程池，Reactor 那套机制不起作用：

- **线程池** —— 用 `TraceTaskDecorator`（一个 `TaskDecorator` bean，会被自动装配到 `applicationTaskExecutor`，所以 `@Async` 能正常带上 traceId），自己手搓的线程池则用 `TraceExecutors.wrap(...)`。两者都会在提交线程上快照 MDC，并且**在 `finally` 里还原工作线程原有的上下文** —— 池化线程是复用的，不还原就会把上一个请求的 traceId 泄漏给下一个任务。
- **临时创建的子线程** —— `log4j2.component.properties` 里设了 `log4j2.isThreadContextMapInheritable=true`。它只对请求期间*新建*的线程有效，对线程池是用错了工具，所以上面那两个包装器才有存在的必要。

`TraceFilterTest` 会直接读真实的日志文件来端到端验证以上全部行为，顺带也证明了异步 appender 确实把日志刷盘了。

## OpenFeign

`FeignConfiguration` 上有 `@EnableFeignClients(basePackages = "cn.nihility.gw")`，以及追踪用的拦截器。所有调优参数都在 `application.yaml` 的 `spring.cloud.openfeign` 下：

- `httpclient.hc5.enabled: true` —— 用 Apache HttpClient 5（`feign-hc5`）替代 Feign 默认的 `HttpURLConnection`，后者没有连接池。连接池大小由 `max-connections` / `max-connections-per-route` 控制。
- `client.config.default.connect-timeout: 3000` 与 `read-timeout: 5000` —— `default` 是所有 client 的兜底配置，可以按 `@FeignClient` 的 name 单独覆盖。

因为这是一个**响应式**应用，`FeignConfiguration` 还必须额外声明一个 `HttpMessageConverters` bean：`HttpMessageConvertersAutoConfiguration` 上带着 `NotReactiveWebApplicationCondition`，在 WebFlux 应用里会自动退避，导致 Feign 的 `SpringDecoder` 在实际调用时抛 `No qualifying bean of type 'HttpMessageConverters'`。这个 bean 标了 `@ConditionalOnMissingBean`，所以哪天真加回 servlet 栈，它会自动让位。

**Feign 是阻塞的，而这是一个响应式应用。** 绝对不要在 handler 里直接调用 `@FeignClient` —— 那会卡住一个 Netty 事件循环线程。照抄 `FeignService` 的写法：

```java
return Mono.fromCallable(() -> { log.info(message); return call.get(); })
        .subscribeOn(Schedulers.boundedElastic());
```

注意是 `fromCallable` 而不是 `just`：`Mono.just(client.welcome())` 会在装配期（assembly time）、也就是在事件循环线程上，就把这个阻塞调用执行掉，那整件事就白做了。产生的线程切换在日志里看得见，traceId 也会跟着过去：

```
INFO [flux-http-nio-2] [welcometraceid...] c.n.g.c.WelcomeController : Controller feign /welcome call
INFO [oundedElastic-1] [welcometraceid...] c.n.g.s.FeignService      : feign /welcome call
```

traceId 能扛过这次线程切换，靠的是 `Hooks.enableAutomaticContextPropagation()`（见上面链路追踪一节）—— 它覆盖了 `subscribeOn`/`publishOn` 这类边界。`WelcomeControllerTest` 是在打桩的 client 内部断言 `boundedElastic` 线程和 traceId 的，而不是去解析日志。

Controller 一律返回 `Mono<...>`；打日志这类副作用要放进 `Mono.fromSupplier(...)` 或 `.doFirst(...)`，让它们在订阅时执行，而不是在装配期执行。

`traceIdRequestInterceptor` 会把当前 MDC 里的 traceId 复制到每一个出站的 Feign 请求上。有两个细节要注意：一是当 client 方法自己已经声明了这个请求头时它会跳过（大小写不敏感，所以显式写的 `x-trace-id` 不会被重复添加）；二是它读的是*调用方*线程的 MDC —— Feign 是同步的，所以从线程池里发起的调用必须先用 `TraceExecutors.wrap(...)` 包一层，否则根本没有 traceId 可复制。在主上下文里注册一个普通的 `RequestInterceptor` `@Bean` 就会对所有 client 生效，不需要再写进某个 client 的专属配置类里。

有了这些，traceId 现在能在两种跳转中都存活下来：网关代理转发的请求（靠 `TraceFilter` 的请求包装），以及网关自己发起的 Feign 调用。

### 新增 Feign client 时的坑

`@EnableFeignClients` 扫描的是 `cn.nihility.gw`，这**包括测试类**。定义在测试源码里的 `@FeignClient` 会被注册进每一个应用上下文，所以一定要给它的 `url` 一个可解析的默认值（`url = "${trace.probe.url:http://localhost:1}"`），否则非 web 的测试上下文会启动失败。

不要把 `@FeignClient` 的 `url` 指向 `${local.server.port}`。`FeignClientFactoryBean` 是在 bean 创建期间解析 URL 的，而 `local.server.port` 要到 `finishRefresh()` 才发布，所以占位符会原样传进去，上下文报 `... is malformed` 直接挂掉。`FeignTraceTest` 的规避办法是在静态初始化块里预占一个空闲端口，然后用 `DEFINED_PORT` 模式运行。

`FeignTraceTest` 断言了这几件事：client 解包后是 `ApacheHttp5Client`（因为 classpath 上有 spring-cloud-loadbalancer，它外面套了一层 `FeignBlockingLoadBalancerClient`）、traceId 确实到达了下游端点、YAML 里的超时配置真的绑定生效了，以及下游响应慢时会在约 5 秒处失败。

## 构建配置

版本号和 JDK 路径都在 `gradle.properties` 里，**不在** `build.gradle` 里 —— 要改去那边改：

- `SpringBootVersion`、`SpringCloudVersion`、`CloudAliNacosVersion`、`JacksonVersion`
- `org.gradle.java.home=C:/kits/Java/jdk-21.0.1` —— 一个硬编码的绝对路径。机器上没有这个 JDK 时，用命令行参数或本地配置覆盖它，不要改了这个文件再提交上去。

仓库地址针对国内网络做了镜像：`build.gradle` 里阿里云排在 Maven Central 前面，`gradle/wrapper/gradle-wrapper.properties` 里的 Gradle 发行包走的是腾讯镜像。编辑时保持镜像在前。

`compileJava`/`compileTestJava`/`javadoc` 都固定了 UTF-8 编码，否则中文注释在 Windows 默认代码页下会导致编译失败。**不要删掉这些设置。**

## 架构

**跑在 Netty 上的响应式网关。** 依赖是 `spring-cloud-starter-gateway-server-webflux`（响应式/WebFlux 版网关）加上 `spring-boot-starter-webflux`；classpath 上根本没有 servlet API。过滤器要写成返回 `Mono` 的 `WebFilter`/`GlobalFilter`，绝不能写 `jakarta.servlet.Filter` —— servlet 相关的类型在这里压根编译不过。

注意 `build.gradle` 里声明的 `spring-cloud-starter-gateway-server-webflux:4.3.4` **不是**实际生效的版本：`spring-cloud-dependencies:2025.0.3` 这个 BOM 会覆盖显式声明的版本，最终解析到 **4.3.5**。Gateway 4.3 还搬了配置前缀：`spring.cloud.gateway.*` 自 4.3.0 起已废弃，取而代之的是 **`spring.cloud.gateway.server.webflux.*`**（jar 包里的 `spring-configuration-metadata.json` 带着对应的 `deprecation.replacement` 条目）。新写的配置一律放在 `server.webflux` 前缀下。老的 `spring-cloud-starter-gateway:3.1.10` 那行还注释在 `build.gradle` 里 —— 3.1.x 面向的是 Boot 2.6/`javax`，在 Boot 3.5.16 上跑不起来，不要把它恢复回去。

**配置在 Nacos 上，不在本仓库里。** `src/main/resources/application.yaml` 只放启动必需的东西（端口、应用名、Nacos 地址 `10.10.88.31:8848`、激活的 profile `prod`），然后靠 import 拉取：

```yaml
spring.config.import:
  - optional:nacos:CloudGateway.yml
  - optional:nacos:CloudGateway-prod.yml
```

列表里靠后的优先级更高，所以带 profile 的 data-id 会覆盖共享的那份，两者又都覆盖 `application.yaml`。**路由定义预期来自 Nacos**，只有一个例外（见下）。`optional:` 前缀意味着 Nacos 连不上时启动依然成功，所以本地跑起来的网关如果一条路由都没有，通常是没连上 Nacos，而不是配置缺失。Nacos 服务发现是开启的，`spring.cloud.inetutils.preferredNetworks: 10.` 强制用 `10.x` 网段的网卡注册。

**唯一的本地路由：`/proxy/**` → `BootDemo`。** `application.yaml` 里定义了 `spring.cloud.gateway.server.webflux.routes[proxy-boot-demo]`：谓词 `Path=/proxy/**`，目标 `lb://BootDemo`（Nacos 服务发现 + spring-cloud-loadbalancer，服务名和 `Template4Client` 用的是同一个），过滤器依次是 `StripPrefix=1` 和 `PreserveHostHeader`。所以 `GET /proxy/welcome` 到达 `BootDemo` 时是 `GET /welcome`，并且带的是调用方原始的 `Host` 头，而不是被选中实例的 `host:port` —— `PreserveHostHeader` 设置了 `PRESERVE_HOST_HEADER_ATTRIBUTE`，`NettyRoutingFilter` 会遵守它。

**注意：Nacos 不会跟这份配置合并。** YAML 的 list 属性会被高优先级来源整体替换，所以 `CloudGateway.yml` 里只要出现 `...server.webflux.routes` 这个 key，本地这份列表就会被完全覆盖，而不是追加。新增路由时要把两边同步。`ProxyRouteConfigTest` 只断言本地 YAML 能被正确绑定（id、uri、谓词、过滤器顺序）—— 真正跑通转发需要有已注册的 `BootDemo` 实例，所以端到端行为靠手工验证。

`CloudGatewayApplication` 就是一个光秃秃的 `@SpringBootApplication`，还没有自定义 bean；对应的测试是一个空的 `@SpringBootTest` 上下文加载检查。

## 约定

- 以 `.editorconfig` 为准：UTF-8、LF、4 空格缩进、文件末尾留空行、清除行尾空格；Java 行宽上限 120；YAML 用 2 空格；`.bat`/`.cmd` 保持 CRLF；Markdown 用 tab 缩进且保留行尾空格。注意 `build.gradle` 自身是 tab 缩进的 —— 编辑时跟随所在文件的风格。
- 提交信息用带 scope 的 Conventional Commits，例如 `feat(gateway): gateway init`。
- 从 `develop` 拉工作分支（PR 的目标分支也是它）。当前分支是 `gateway-3.1.x`；同级的 `3.5.x`、`java-template`、`flowable-template` 是彼此独立的模板变体，不是特性分支。

## OpenSpec 工作流

本仓库使用 OpenSpec（`openspec/` 目录，`schema: spec-driven`），配套命令 `/opsx:explore`、`/opsx:propose`、`/opsx:apply`、`/opsx:sync`、`/opsx:archive` 定义在 `.claude/commands/opsx/` 下。提案放在 `openspec/changes/`，通过评审的规格落到 `openspec/specs/`，完成的变更移到 `openspec/changes/archive/`。目前 `openspec/specs/` 还是空的，`openspec/changes/` 下有 `add-proxy-route-forwarding`。项目上下文和按产物划分的规则可以填进 `openspec/config.yaml`，那个文件目前还全是注释掉的脚手架。
