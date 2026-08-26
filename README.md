# SpringBoot项目初始化

[SpringBoot 更新版本列表](https://spring.io/projects/spring-boot#learn)

[SpringBoot docs 各个版本官方文档链接](https://docs.spring.io/spring-boot/docs/)

[SpringCloud docs 各个版本官方文档链接](https://docs.spring.io/spring-cloud/docs/)

## SpringBoot 3.5.x版本初始化

整理 SpringBoot 项目创建初始化时对应的 SpringBoot 版本为 `3.5.16`

### 创建SpringBoot项目

[SpringBoot官方项目创建链接](https://start.spring.io/)

#### 项目依赖选择

| 名称            |                             依赖                             |
| --------------- | :----------------------------------------------------------: |
| web             |       org.springframework.boot:spring-boot-starter-web       |
| 服务监控        |    org.springframework.boot:spring-boot-starter-actuator     |
| redis           |   org.springframework.boot:spring-boot-starter-data-redis    |
| 数据库          |    com.baomidou:mybatis-plus-spring-boot3-starter:3.5.16     |
| springdoc       |   org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17   |
| 参数校验        |   org.springframework.boot:spring-boot-starter-validation    |
| dto/vo转换      |                org.mapstruct:mapstruct:1.6.3                 |
| feign           |   org.springframework.cloud:spring-cloud-starter-openfeign   |
| nacos-discovery | com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery:2025.0.0.0 |
| nacos-config    | com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config:2025.0.0.0 |

#### 项目基础版本选择

- SpringBoot ： 3.5.16
- SpringCloud：2025.0.3
- Jdk：21
- Gradle：8.14.4

### 基础配置文件

#### gradle.properties

配置 JDK 目录后面 `gradlew` 执行命令，若是全局安装的 JDK 也是 21 就可以不用单独配置。

```properties
org.gradle.jvmargs=-Dfile.encoding=UTF-8
org.gradle.warning.mode=summary
org.gradle.java.home=D:/kits/java/jdk-21.0.5

```

#### .editorconfig

全局文件编码规范

```properties
# EditorConfig 帮助多人协作时保持一致的编码风格
# 官网: https://editorconfig.org
# IntelliJ IDEA 原生支持；VS Code需装 EditorConfig for VS Code 插件

# 表示这是根配置文件，工具会停止向上级目录查找
root = true

# ========================================
# 所有文件通用的默认规则
# ========================================
[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

# ========================================
# Java 源码文件
# ========================================
[*.java]
max_line_length = 120

# ========================================
# XML 配置文件 (Maven pom.xml、旧式Spring xml配置、MyBatis mapper等)
# ========================================
[*.xml]
indent_style = space
indent_size = 4

# ========================================
# YAML 配置文件 (application.yml 等，Spring Boot主流配置格式)
# ========================================
[*.{yml,yaml}]
indent_style = space
indent_size = 2

# ========================================
# Properties 配置文件 (application.properties)
# ========================================
[*.properties]
indent_style = space
indent_size = 4

# ========================================
# JSON 文件
# ========================================
[*.json]
indent_style = space
indent_size = 2

# ========================================
# SQL 文件 (数据库迁移脚本、建表语句等)
# ========================================
[*.sql]
indent_style = space
indent_size = 4

# ========================================
# Markdown 文档：不清理行尾空格
# 因为Markdown语法里"行尾两个空格"表示强制换行，是有意义的语法，不能当垃圾清掉
# ========================================
[*.md]
indent_style = tab
trim_trailing_whitespace = false
max_line_length = off

# ========================================
# Shell 脚本
# ========================================
[*.sh]
indent_style = space
indent_size = 2
end_of_line = lf

# ========================================
# Makefile: 必须用Tab缩进，这是Makefile语法的硬性要求，不能用空格
# ========================================
[Makefile]
indent_style = tab

# ========================================
# Windows批处理文件：保留CRLF换行符(Windows原生习惯)
# ========================================
[*.{bat,cmd}]
end_of_line = crlf

```

### 中间件配置

#### nacos

##### gradle依赖

```groovy
    implementation 'org.springframework.cloud:spring-cloud-starter'
    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
    implementation "com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-config:2025.0.0.0"
    implementation "com.alibaba.cloud:spring-cloud-starter-alibaba-nacos-discovery:2025.0.0.0"
```

##### Nacos Spring Cloud 配置

[Spring Boot 3x 使用 Nacos 官方说明文档](https://nacos.io/docs/next/ecology/use-nacos-with-spring-boot3/)

```yaml
spring:
  application:
    name: SpringBootTemplate
  profiles:
    active: prod
  config:
    import:
      - optional:nacos:${spring.application.name}.yml
      - optional:nacos:${spring.application.name}-${spring.profiles.active}.yml
  cloud:
    inetutils:
      preferredNetworks:
        - 127.0
    nacos:
      server-addr: 127.0.0.1:8848
#      username: nacos
#      password: nacos
      config:
        enabled: true
        file-extension: yml
      discovery:
        enabled: true
```

##### SpringBoot启动类注解

```java
@EnableDiscoveryClient
@SpringBootApplication
public class SprintBootTemplateApplication {}
```

##### `spring.config.import` 配置说明

`spring.config.import` 是 Spring Boot 2.4+ 引入的**统一外部配置导入机制**，用来替代老版本 `bootstrap.yml` 那套加载方式，为什么会有这个机制，为了解决 `bootstrap.yml` 的历史包袱。Spring Boot 2.4 之前，Spring Cloud 生态（Nacos/Consul/Config Server）想要"在应用启动早期就从远程拉配置"，只能靠 `bootstrap.yml` 这套独立的、优先级更高的引导上下文机制，这套机制**不是Spring Boot原生支持的**，而是 Spring Cloud Context 额外加的一层，长期以来被社区诟病"概念复杂、两套配置文件容易搞混、启动流程不透明"。

**Spring Boot 2.4 引入的 `spring.config.import`，让"导入外部配置源"变成了Spring Boot原生统一支持的能力**，不再需要额外的bootstrap 阶段，逻辑更清晰，官方也逐步在推动生态往这个方向迁移。

**关键区别**：老版本 `bootstrap.yml` 是**隐式**的（只要类路径下有 `spring-cloud-starter-alibaba-nacos-config` 依赖，Spring Cloud就自动去连Nacos拉配置）；新版本用 `spring.config.import` 是**显式**的（必须明确写出"要导入哪个配置文件"），这样的好处是配置来源一目了然，不用去翻文档才知道"这个应用到底从哪读的配置"。

`spring.config.import` 支持的几种常见配置源，`optional:` 前缀——避免配置源不存在时启动失败（有则用，没有也不影响启动）

```yaml
spring:
  config:
    import:
      # 导入类路径下的另一个配置文件
      - "optional:classpath:extra-config.yml"
      # 导入文件系统路径下的配置文件
      - "optional:file:/etc/myapp/config.yml"
      # 导入Nacos配置
      - "optional:nacos:my-service.yaml"
      # 导入Spring Cloud Config Server配置
      - "optional:configserver:http://config-server:8888"
      # 导入Consul配置
      - "optional:consul:my-service"
      # 导入Vault配置(密钥管理场景)
      - "optional:vault://secret/my-service"
```

这几个配置源会按顺序合并，**后面的会覆盖前面的同名配置项**（数组是有顺序意义的），跟之前bootstrap.yml里 `extension-configs` 列表的合并逻辑思路一致，只是写法更统一。

#### Actuator 监控

[Actuator 说明文档链接](https://docs.spring.io/spring-boot/3.5/how-to/actuator.html#page-title)

##### gradle依赖

```groovy
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

##### 配置

```yaml
# actuator 监控
management:
#  server:
#    # actuator独立端口，防火墙层面隔离，不对外网暴露
#    port: 34568
  endpoints:
    web:
      base-path: /actuator
      exposure:
        # 只暴露必要的端点，其他一律不开放
        include: health,info,prometheus,metrics
    access:
      # 白名单模式，未单独配置的端点一律禁止
      default: none
      # 全局硬上限，即使某端点单独配了unrestricted也不会超出这个限制
      max-permitted: read-only
  endpoint:
    health:
      access: unrestricted
      # 显示健康检查详情的权限控制: never/when-authorized/always
      # 生产环境不建议用always(会暴露数据库/磁盘等内部组件详情给未授权访问者)
      show-details: never
      show-components: never
      # K8s专用liveness/readiness探针端点
      probes:
        enabled: true
    info:
      access: unrestricted
    prometheus:
      access: unrestricted
    metrics:
      access: unrestricted
  metrics:
    tags:
      # 给所有指标打上应用名标签，多应用场景下Grafana筛选方便(对应nodename/application变量联动)
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http:
          server:
            # 开启HTTP请求耗时的P50/P95/P99分位数统计
            requests: true
    enable:
      hikaricp: true        # 数据库连接池指标(HikariCP场景)
      lettuce: true         # Redis连接池指标(Lettuce场景)
      jvm: true
      process: true
      system: true

```

#### log4j2

Spring Boot 3.5.16 切换成 Log4j2，涉及排除默认日志实现、配置文件、异步日志、MDC跨线程传递几个部分。

##### gradle依赖

```groovy
// Gradle 全局排除
configurations.configureEach {
    exclude group: 'ch.qos.logback', module: 'logback-core'
    exclude group: 'ch.qos.logback', module: 'logback-classic'
    exclude group: 'org.apache.logging.log4j', module: 'log4j-to-slf4j'
    exclude group: 'org.springframework.boot', module: 'spring-boot-starter-logging'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    // 异步日志需要的Disruptor高性能无锁队列
    implementation 'com.lmax:disruptor:4.0.0'
    // 换成Log4j2
    implementation 'org.springframework.boot:spring-boot-starter-log4j2'
}
```

这个排除步骤是必须的，Spring Boot默认依赖树里带的是Logback，如果不排除，classpath上会同时存在两套日志实现冲突，Spring Boot会在启动阶段报错或者行为异常。

确认最终classpath上log4j2相关的桥接包是"单向"的，没有循环，正确的组合应该是**只有** `log4j-slf4j2-impl`（负责把"别人调用SLF4J API"路由到"Log4j2的实际实现"），**绝不能同时有** `log4j-to-slf4j`（这个包的作用方向正好相反，是把"Log4j2 API调用"路由回"SLF4J"，两个方向同时存在就会死循环，这正是会报错的直接原因）

验证排除是否彻底干净

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -iE "logback|log4j-to-slf4j"
```

##### log4j2-spring.xml 配置

`log4j2-spring.xml` 完整配置（放在 `src/main/resources` 下）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- monitorInterval: 每30秒检测一次配置文件是否变更，支持不重启应用热更新日志级别 -->
<Configuration status="WARN" monitorInterval="30">

    <Properties>
        <Property name="LOG_PATTERN">
            %d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{traceId}] - %msg%n
        </Property>
        <Property name="LOG_HOME">./logs</Property>
    </Properties>

    <Appenders>
        <!-- 控制台输出，本地开发方便查看，生产环境可以按需关闭 -->
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="${LOG_PATTERN}"/>
        </Console>

        <!-- 应用日志: 按天滚动，每天最多10个文件，保留7天 -->
        <RollingFile name="AppLogFile"
                     fileName="${LOG_HOME}/app.log"
                     filePattern="${LOG_HOME}/app-%d{yyyy-MM-dd}-%i.log.gz">
            <PatternLayout pattern="${LOG_PATTERN}"/>
            <Policies>
                <!-- 按天触发滚动 -->
                <TimeBasedTriggeringPolicy interval="1" modulate="true"/>
                <!-- 单个文件超过200MB也触发滚动，配合下面的max="10"控制每天最多10个文件 -->
                <SizeBasedTriggeringPolicy size="200MB"/>
            </Policies>
            <!-- max="10": 每天最多10个文件(超过后最旧的会被覆盖，配合下面Delete策略做真正的7天清理) -->
            <DefaultRolloverStrategy max="10">
                <Delete basePath="${LOG_HOME}" maxDepth="1">
                    <IfFileName glob="app-*.log.gz"/>
                    <!-- 保留7天，超过的自动删除 -->
                    <IfLastModified age="7d"/>
                </Delete>
            </DefaultRolloverStrategy>
        </RollingFile>

        <!-- 错误日志单独分离一份，方便快速定位问题，不用在海量INFO日志里翻找 -->
        <RollingFile name="ErrorLogFile"
                     fileName="${LOG_HOME}/error.log"
                     filePattern="${LOG_HOME}/error-%d{yyyy-MM-dd}-%i.log.gz">
            <PatternLayout pattern="${LOG_PATTERN}"/>
            <Filters>
                <ThresholdFilter level="ERROR" onMatch="ACCEPT" onMismatch="DENY"/>
            </Filters>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1" modulate="true"/>
                <SizeBasedTriggeringPolicy size="200MB"/>
            </Policies>
            <DefaultRolloverStrategy max="10">
                <Delete basePath="${LOG_HOME}" maxDepth="1">
                    <IfFileName glob="error-*.log.gz"/>
                    <IfLastModified age="7d"/>
                </Delete>
            </DefaultRolloverStrategy>
        </RollingFile>

        <!-- 异步Appender包装上面的RollingFile，实现异步写日志(减少I/O阻塞主线程) -->
        <Async name="AsyncAppLogFile" bufferSize="8192">
            <AppenderRef ref="AppLogFile"/>
            <!-- 队列满时的处理策略: Discard丢弃低优先级日志(比如INFO)，避免日志把应用拖垮 -->
            <BlockingQueueFactory/>
        </Async>

        <Async name="AsyncErrorLogFile" bufferSize="8192">
            <AppenderRef ref="ErrorLogFile"/>
        </Async>
    </Appenders>

    <Loggers>
        <!-- 业务代码包路径的日志级别 -->
        <Logger name="com.example" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="AsyncAppLogFile"/>
            <AppenderRef ref="AsyncErrorLogFile"/>
        </Logger>

        <!-- 降低第三方框架日志噪音 -->
        <Logger name="org.springframework" level="WARN"/>
        <Logger name="com.alibaba.nacos" level="WARN"/>
        <Logger name="com.netflix.discovery" level="WARN"/>

        <Root level="INFO">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="AsyncAppLogFile"/>
            <AppenderRef ref="AsyncErrorLogFile"/>
        </Root>
    </Loggers>

</Configuration>
```

##### 全局异步日志

全局异步日志（比上面的AsyncAppender更彻底，推荐生产环境用这个）

上面配置的是"部分异步"（单个Appender异步），**更推荐的做法是让所有Logger都异步**，这需要额外配置一个系统属性，新建 `log4j2.component.properties`（放在 `resources` 根目录）

```properties
log4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
```

配置了这个之后，**整个应用的日志记录都会走Disruptor无锁队列异步处理**，比单独包一层AsyncAppender性能更好，这是Log4j2官方推荐的全异步模式（区别于"混合模式"，全异步模式下日志I/O完全不会阻塞业务线程）。

##### MDC 配置（单线程内自动生效）

```java
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        // ThreadContext是Log4j2的MDC实现，对应Logback里的MDC类
        ThreadContext.put("traceId", traceId);

        try {
            response.setHeader("X-Trace-Id", traceId);
            filterChain.doFilter(request, response);
        } finally {
            // 请求结束务必清理，避免线程池复用线程时traceId串号(上一个请求的traceId污染下一个请求)
            ThreadContext.clearAll();
        }
    }
}
```

##### MDC跨线程传递

**问题所在**：`ThreadContext`(MDC)本质是**ThreadLocal**，只在**当前线程**里生效。一旦你的业务代码用了线程池（`@Async`、`ExecutorService`、`CompletableFuture`等），子线程里是拿不到父线程设置的 `traceId` 的，日志会显示成空白，导致链路追踪断掉。

解决方案：自定义线程池装饰器，手动在任务提交时"拷贝"MDC上下文到子线程

```java
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在主线程提交任务的这一刻，捕获当前MDC上下文快照
        Map<String, String> contextMap = ThreadContext.getImmutableContext();
        return () -> {
            try {
                // 子线程执行前，把父线程的MDC内容设置进来
                if (contextMap != null) {
                    ThreadContext.putAll(contextMap);
                }
                runnable.run();
            } finally {
                // 子线程执行完清理，避免线程池复用导致MDC污染下一个任务
                ThreadContext.clearAll();
            }
        };
    }
}
```

如果是原生 `ExecutorService`（不是Spring的`ThreadPoolTaskExecutor`），需要手动包装`Runnable`

```java
public class MdcExecutorWrapper {

    public static Runnable wrap(Runnable task) {
        Map<String, String> contextMap = ThreadContext.getImmutableContext();
        return () -> {
            try {
                if (contextMap != null) {
                    ThreadContext.putAll(contextMap);
                }
                task.run();
            } finally {
                ThreadContext.clearAll();
            }
        };
    }
}

// 使用方式
ExecutorService executor = Executors.newFixedThreadPool(10);
executor.submit(MdcExecutorWrapper.wrap(() -> {
    log.info("这条日志能正确带上traceId了");
}));
```

#### springdoc

##### gradle依赖

```groovy
    /* springdoc 3.x 目标 Spring Boot 4，与本项目的 Spring Boot 3.5 不兼容，固定使用 2.x 系列的最新版 */
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17'
```

##### application.yml 基础配置

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
    enabled: true

  swagger-ui:
    path: /swagger-ui.html
    enabled: true
    operations-sorter: method     # 接口按HTTP方法排序，而不是默认的声明顺序，阅读体验更好
    tags-sorter: alpha
    try-it-out-enabled: false     # 生产环境建议关闭"直接在页面上发起真实请求"这个功能
    disable-swagger-default-url: true

  # 只扫描指定包路径，避免扫描到不必要的类拖慢启动、暴露不该暴露的接口
  packages-to-scan: com.example.controller
  # 只扫描指定 URL 路径，避免扫描到不必要的类拖慢启动、暴露不该暴露的接口
  paths-to-match: /api/**

  # 生产环境关闭默认的"try it out"发起真实请求，只保留文档展示功能
  default-produces-media-type: application/json
```

核心：生产环境彻底禁用（最推荐的安全实践）

```yaml
# application-prod.yml
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

##### OpenApiDoc配置

```java
@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("My Service API")
                .version("1.0.0")
                .description("生产环境接口文档")
                .contact(new Contact()
                    .name("运维团队")
                    .email("ops@example.com")))
            .servers(List.of(
                new Server().url("https://api.example.com").description("生产环境"),
                new Server().url("https://staging-api.example.com").description("预发环境")
            ));
    }
}
```

#### Redis

##### 连接池配置

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:0}

      # 连接建立超时时间
      connect-timeout: 3000ms
      # 单条命令执行超时时间
      timeout: 3000ms

      # Lettuce连接池配置(默认客户端就是lettuce，不用额外指定client-type)
      lettuce:
        pool:
          enabled: true
          # 连接池最大连接数，负数表示不限制(不建议用负数，容易在异常场景下把Redis连接打爆)
          # max-active = (应用实例数 * 最大并发线程数) * 1.2
          # 例如：2个实例，每个实例最大线程数（如 tomcat.threads.max）为 50
          # 则可设置为 (2 * 50) * 1.2 ≈ 120
          # 但最终值绝不能超过 Redis 服务器端的 maxclients 配置，并要为其预留一部分。
          max-active: 20
          # 连接池最大空闲连接数，建议是 max-active 的 1/2 到 2/3
          max-idle: 10
          # 连接池最小空闲连接数(保持一定数量的热连接，避免每次现建连接的开销)，用于维持 warm-up，避免突发流量
          min-idle: 5
          # 连接池最大阻塞等待时间，超过这个时间还拿不到连接就抛异常，而不是无限等待
          max-wait: 3000ms
          # 连接空闲多久后被回收判定的检测周期
          time-between-eviction-runs: 30000ms
        # 关闭连接池时的超时时间(优雅关闭用，配合你之前问的容器优雅关闭场景)
        shutdown-timeout: 200ms

        # 读写分离场景才需要(比如Redis主从/Cluster模式，只有查询操作允许读从节点)
        # read-from: replica-preferred
```

关键参数说明

| 参数              | 说明                                                         |
| :---------------- | :----------------------------------------------------------- |
| `max-active`      | 连接池最大连接总数，这是最核心的容量控制参数，需要结合你的实际并发量压测调整，不是越大越好——过大的连接池反而会给Redis服务端造成压力 |
| `min-idle`        | 保持的最小空闲连接数，避免高并发瞬间大量"现建连接"导致延迟毛刺，相当于预热了一批连接常驻 |
| `max-wait`        | **生产环境务必设置一个合理的正数值，不要用默认的`-1ms`(无限等待)**——如果Redis服务端故障或连接池耗尽，无限等待会导致你的应用线程被大量卡死拖垮，设置超时能让请求快速失败，配合熔断/降级机制处理，比无限期挂起更可控 |
| `connect-timeout` | 建连阶段的超时，网络异常/Redis服务不可达时能快速失败         |
| `timeout`         | 单条命令执行的超时(不是建连而是命令本身)，防止某条慢查询/网络抖动拖死调用方线程 |

##### 序列化

核心问题：为什么不能用默认序列化

`RedisTemplate` 默认用的是JDK原生序列化（`JdkSerializationRedisSerializer`），存进Redis的数据是**不可读的二进制内容**，用 `redis-cli` 查看时是乱码，而且**类结构一旦变了（比如加了个新字段），历史序列化的数据可能反序列化失败**，生产环境几乎不会用默认配置，都是自己配置 Jackson 做 JSON 序列化。

```java
package com.example.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key用String序列化(可读性好，方便redis-cli直接查看key)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // value用Jackson JSON序列化(可读性好，跨语言兼容，且能处理泛型/多态类型)
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(buildObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    private ObjectMapper buildObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 支持所有字段(包括private)的序列化，不要求必须有getter/setter
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 反序列化时忽略未知字段，避免"实体类改了字段但Redis里还是老结构"导致反序列化直接报错
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 支持Java 8时间类型(LocalDateTime/LocalDate等)，不加这个会序列化报错
        objectMapper.registerModule(new JavaTimeModule());

        // 关键: 启用类型信息，让反序列化时能还原出正确的具体类型(而不是变成LinkedHashMap)
        // 这里用PolymorphicTypeValidator限制允许反序列化的包路径，是2.10+版本后的安全推荐写法
        // 避免使用已废弃且有反序列化安全风险的 enableDefaultTyping() 老方法
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.example")   // 只允许你自己项目包路径下的类参与多态反序列化，收紧安全边界
            .allowIfSubType("java.util")
            .build();

        objectMapper.activateDefaultTyping(
            typeValidator,
            ObjectMapper.DefaultTyping.NON_FINAL
        );

        return objectMapper;
    }
}
```

**1. Key 用 String 序列化，Value 用 Jackson JSON 序列化——这是业界标准搭配**

Key如果也用JSON序列化，会带上多余的引号和转义字符，`redis-cli keys '*'` 看到的key会很难看；用 `StringRedisSerializer` 能让key保持纯字符串形态，方便排查问题时直接肉眼阅读。

**2. `activateDefaultTyping` 替代已废弃的 `enableDefaultTyping()`**

老版本Jackson教程里常见的 `objectMapper.enableDefaultTyping()` 已经被标记废弃，且存在反序列化安全风险（不受限的多态类型解析，理论上可能被利用做反序列化攻击）。新写法必须配合 `PolymorphicTypeValidator` 显式声明"只允许哪些包路径的类参与多态反序列化"，这是Jackson团队为了安全考量做的收紧，生产环境务必用新写法，不要图省事继续用老方法。

**3. `FAIL_ON_UNKNOWN_PROPERTIES: false` 是生产环境的重要容错配置**

如果你的实体类字段以后要新增/删减，历史缓存数据里还残留着老字段，不设这个的话反序列化会直接抛异常导致业务报错；设成false后，遇到不认识的字段直接忽略，不影响正常反序列化，是应对"缓存数据结构演进"的标准做法。

##### 手动序列化

如果更倾向用 `RedisTemplate<String, String>` + 手动序列化(更轻量的替代方案)

有些团队不喜欢`@class`这种带类型元信息的JSON格式（觉得不够干净，或者要跨语言给非Java服务消费），会选择更简单直接的方式：

```java
@Bean
public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
    RedisTemplate<String, String> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);
    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    return template;
}
```

配合业务代码手动用 `ObjectMapper` 转换：

```java
@Autowired
private ObjectMapper objectMapper;

public void cacheUser(String userId, User user) throws JsonProcessingException {
    String json = objectMapper.writeValueAsString(user);
    redisTemplate.opsForValue().set("user:" + userId, json, Duration.ofMinutes(30));
}

public User getUser(String userId) throws JsonProcessingException {
    String json = redisTemplate.opsForValue().get("user:" + userId);
    return json != null ? objectMapper.readValue(json, User.class) : null;
}
```

**两种方案怎么选**：

- 存储对象类型多样、需要自动还原具体类型 → 用 `GenericJackson2JsonRedisSerializer`(带类型信息)
- 存储的JSON需要给非Java服务消费/追求最干净的JSON格式(不带`@class`元数据) → 用String序列化+手动转换

##### Actuator连接池监控

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    enable:
      lettuce: true
```

开启后能通过 `/actuator/prometheus` 采集到Lettuce连接池的实时指标（活跃连接数、空闲连接数等），配合搭建的Prometheus + Grafana监控体系，可以对连接池使用情况做可视化观察，及时发现连接池耗尽等异常。

#### 数据库

##### 连接池配置

```yaml
spring:
  datasource:
    type: com.zaxxer.hikari.HikariDataSource
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/test?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: mysql
    hikari:
      # 连接池名称，多数据源场景下方便区分日志/监控指标
      pool-name: HikariPool-TemplateService
      # 最大连接数：核心参数，需要结合数据库最大连接数、应用实例数一起算
      # 经验公式: ((core_count * 2) + effective_spindle_count)，简单场景可以从10开始压测调整
      maximum-pool-size: 20
      # 最小空闲连接数：保持的常驻连接，避免高峰期现建连接的延迟开销
      # HikariCP官方建议：如果没有明确理由，minimum-idle应该跟maximum-pool-size保持一致
      # 固定大小的连接池在生产环境表现更稳定、更可预测，波动连接池反而容易引发性能抖动
      minimum-idle: 20
      # 从连接池获取连接的最大等待时间，超时抛异常而不是无限期卡住
      connection-timeout: 30000
      # 连接在池中最大空闲时间，超过这个时间空闲连接会被回收(前提是连接数依然大于minimum-idle)
      idle-timeout: 600000
      # 连接的最大生命周期，到期后即使正在使用也会被安全地关闭重建
      # 这个值必须比MySQL自身的wait_timeout(默认8小时)小，
      # 否则MySQL端会先把"看起来还在池子里"的连接断开，
      # 应用这边却不知道，下次用到时才发现连接已经失效，抛异常
      max-lifetime: 1800000
      # 连接泄漏检测：超过这个时间连接还没被归还，会打印警告日志(排查连接泄漏问题的利器)
      # 生产环境建议开启，设为比正常业务SQL执行时间稍长的值
      leak-detection-threshold: 60000
      # 连接有效性检测查询(MySQL 5.6+建议用JDBC4的isValid()方法，不需要手动写这个)
      # connection-test-query: SELECT 1
      # 是否在连接归还池中之前自动提交，一般保持默认true即可(除非你手动管理事务)
      auto-commit: true
```

核心参数说明

| 参数                       | 推荐值                      | 说明                                                         |
| -------------------------- | --------------------------- | ------------------------------------------------------------ |
| `maximum-pool-size`        | 根据压测调整，起点10-20     | **不是越大越好**，连接数过多反而会因为数据库端上下文切换开销导致性能下降，HikariCP官方文档明确指出这一点，连接池不是越大吞吐量越高 |
| `minimum-idle`             | 建议等于`maximum-pool-size` | 官方推荐固定大小的连接池，而不是动态伸缩，生产环境表现更稳定 |
| `connection-timeout`       | 30000ms(30秒)               | 获取连接的超时时间，太短容易在瞬时高峰误杀正常请求，太长会导致请求堆积 |
| `max-lifetime`             | 1800000ms(30分钟)           | **必须小于数据库的`wait_timeout`**，这是最容易被忽视但很关键的一点 |
| `leak-detection-threshold` | 60000ms(60秒)               | 生产环境建议开启，能帮你快速定位"连接用完忘记关闭"这类代码bug |

确认MySQL的 `wait_timeout` 配置（避免连接失效问题）

```sql
SHOW VARIABLES LIKE 'wait_timeout'; // 28800
```

如果MySQL的 `wait_timeout` 是默认的28800秒（8小时），而你的HikariCP `max-lifetime` 设置的比这个值大，就可能出现"连接池认为连接还活着，但MySQL端已经主动断开了"的情况，导致业务代码偶发遇到 `Communications link failure` 这类异常。**保证 `max-lifetime` 略小于MySQL的 `wait_timeout`（官方建议至少短30秒）**，让连接在MySQL主动断开之前，由连接池主动、优雅地完成重建。

##### Actuator监控连接池状态

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  metrics:
    enable:
      hikaricp: true
```

开启后能通过 `/actuator/prometheus` 采集到HikariCP连接池的实时指标（活跃连接数、等待连接的线程数、连接获取耗时等），是排查"连接池耗尽导致接口变慢"这类问题最直接的数据来源，配合搭建的Prometheus + Grafana监控体系可以做可视化告警。

##### 容器化场景补充

如果这个应用部署在K8s里、多副本运行，注意 `maximum-pool-size` 要考虑**总连接数**是否超过数据库的 `max_connections` 限制：

```
应用副本数 × maximum-pool-size ≤ 数据库max_connections × 安全系数(建议0.8左右，留给其他客户端/管理连接)
```

比如数据库 `max_connections=500`，你有5个应用副本，每个 `maximum-pool-size=20`，总共占用100个连接，还比较安全；但如果副本数扩容到20个，同样的单实例配置就会占用400个连接，逼近数据库上限，这种场景需要重新压测调整单实例的连接池大小，而不是保持"每个实例都用同一份配置"不做区分。

#### mybatis

##### gradle依赖

```groovy
    implementation 'org.mybatis:mybatis-spring:3.0.6'
    /* MyBatis-Plus 3.5.x 把分页拦截器依赖的 JSQLParser 拆到了独立模块，PaginationInnerInterceptor 需要它才能生效 */
    implementation 'com.baomidou:mybatis-plus-jsqlparser:3.5.16'
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'com.baomidou:mybatis-plus-spring-boot3-starter:3.5.16'
```

##### mapper定义扫描定义

```java
@MapperScan(basePackages = "com.example.template", annotationClass = Mapper.class)
@SpringBootApplication
public class SprintBootTemplateApplication {}
```

##### 分页配置定义

```java
/**
 * MyBatis-Plus 全局配置。若不注册分页插件，{@code BaseMapper#selectPage} 不会在 SQL 层追加
 * LIMIT/OFFSET，也不会计算 total，只会原样返回未分页的结果——这里统一注册好，各模块的
 * 分页查询无需重复配置。
 */
@Configuration
public class MybatisPlusConfig {
    /**
     * 注册 MySQL 分页插件，使 {@code IPage}/{@code Page} 参数的查询真正生效。
     *
     * @return MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

##### application.yml 配置

```yaml
# com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties
mybatis-plus:
  config-location: classpath:mybatis/mybatis.conf
  mapper-locations: classpath*:mybatis/mapper/*.xml
  type-aliases-package: com.example.template.mybatis.entity
  global-config:
    db-config:
      id-type: auto

# 本地开发时打印 MyBatis-Plus 执行的 SQL，便于联调排查；生产环境按需调低。
logging:
  level:
    com.example.template: debug
```

##### mybatis.conf

`mybatis/mybatis.conf` 全局配置文件

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE configuration
        PUBLIC "-//mybatis.org//DTD Config 3.0//EN"
        "https://mybatis.org/dtd/mybatis-3-config.dtd">
<!-- 原生 MyBatis 全局配置，通过 mybatis-plus.config-location 加载，
     取代 application.yml 中 mybatis-plus.configuration 的内联写法。
     MyBatis-Plus 专属配置（如 global-config.db-config.id-type）不在此文件的能力范围内，
     仍保留在 application.yml 里。 -->
<configuration>
    <settings>
        <!-- 数据库列名（下划线）与实体属性名（驼峰）自动映射，tab_org 等表均为下划线命名 -->
        <setting name="mapUnderscoreToCamelCase" value="true"/>
        <!-- 查询结果为 null 的列也调用对应 setter，避免遗漏字段初始化 -->
        <setting name="callSettersOnNulls" value="true"/>
        <setting name="jdbcTypeForNull" value="NULL"/>
    </settings>
    <plugins>
        <plugin interceptor="com.example.template.mybatis.interceptor.ExecutorDurationInterceptor"/>
    </plugins>
</configuration>

```

##### mapper.xml

```xml
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.example.template.mybatis.mapper.BatchTestMapper">
</mapper>
```

#### openfeign

##### gradle依赖

```groovy
    /* Feign底层用OkHttp替代默认的HttpURLConnection，支持连接池，性能更好 */
    implementation 'io.github.openfeign:feign-hc5:13.9.2'
    implementation 'org.springframework.cloud:spring-cloud-starter-openfeign'
```

##### application.yml配置

```yaml
spring:
  cloud:
    openfeign:
      # 底层HTTP客户端换成HttpClient(默认是JDK自带的HttpURLConnection，性能较差，不支持连接池)
      httpclient:
        # 关闭SSL证书校验
        disable-ssl-validation: true
        # 开启hc5，关闭其他实现，避免多个HTTP客户端实现同时存在classpath导致行为不确定
        hc5:
          enabled: true

      # 压缩请求/响应，减少网络传输开销
      compression:
        request:
          enabled: true
          mime-types: text/xml,application/xml,application/json
          min-request-size: 2048
        response:
          enabled: true

      # 开启日志(排查问题用，生产环境建议basic级别，避免日志量过大)
      client:
        config:
          default:
            logger-level: basic
            connect-timeout: 3000       # 建连超时
            read-timeout: 5000           # 读取超时，需要结合下游接口实际响应时间调整
            # 重试配置(默认Feign不重试，需要显式开启)
```

##### 启用OpenFeign

```java
@EnableFeignClients
@SpringBootApplication
public class SprintBootTemplateApplication {}
```

##### 请求拦截器

```java
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            // 透传链路追踪ID，方便跨服务日志关联排查
            String traceId = MDC.get("traceId");
            if (traceId != null) {
                requestTemplate.header("X-Trace-Id", traceId);
            }
            // 透传认证信息(比如从当前请求上下文里取Token，往下游服务传递)
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String authHeader = attrs.getRequest().getHeader("Authorization");
                if (authHeader != null) {
                    requestTemplate.header("Authorization", authHeader);
                }
            }
        };
    }
}
```



## SpringBoot 3.x核心特性

### 拓展配置

#### 默认源配置加载顺序

Spring Boot 使用了一种非常特殊的 `PropertySource` 顺序，旨在允许合理的值覆盖。后面的属性源可以覆盖前面属性源中定义的值。

归纳的常用配置加载顺序：

**命令行参数 > Java系统属性(`System.getProperties()`) > 系统环境变量 > 配置数据(`application.properties`)**

1. Default properties (specified by setting [`SpringApplication.setDefaultProperties(Map)`](https://docs.spring.io/spring-boot/3.5.16/api/java/org/springframework/boot/SpringApplication.html#setDefaultProperties(java.util.Map))).
2. [`@PropertySource`](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/context/annotation/PropertySource.html) annotations on your [`@Configuration`](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/context/annotation/Configuration.html) classes. Please note that such property sources are not added to the [`Environment`](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/env/Environment.html) until the application context is being refreshed. This is too late to configure certain properties such as `logging.*` and `spring.main.*` which are read before refresh begins.
3. Config data (such as `application.properties` files).
4. A [`RandomValuePropertySource`](https://docs.spring.io/spring-boot/3.5.16/api/java/org/springframework/boot/env/RandomValuePropertySource.html) that has properties only in `random.*`.
5. OS environment variables.
6. Java System properties (`System.getProperties()`).
7. JNDI attributes from `java:comp/env`.
8. [`ServletContext`](https://jakarta.ee/specifications/servlet/6.0/apidocs/jakarta.servlet/jakarta/servlet/ServletContext.html) init parameters.
9. [`ServletConfig`](https://jakarta.ee/specifications/servlet/6.0/apidocs/jakarta.servlet/jakarta/servlet/ServletConfig.html) init parameters.
10. Properties from `SPRING_APPLICATION_JSON` (inline JSON embedded in an environment variable or system property).
11. Command line arguments.
12. `properties` attribute on your tests. Available on [`@SpringBootTest`](https://docs.spring.io/spring-boot/3.5.16/api/java/org/springframework/boot/test/context/SpringBootTest.html) and the [test annotations for testing a particular slice of your application](https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html#testing.spring-boot-applications.autoconfigured-tests).
13. [`@DynamicPropertySource`](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/test/context/DynamicPropertySource.html) annotations in your tests.
14. [`@TestPropertySource`](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/test/context/TestPropertySource.html) annotations on your tests.
15. [Devtools global settings properties](https://docs.spring.io/spring-boot/3.5/reference/using/devtools.html#using.devtools.globalsettings) in the `$HOME/.config/spring-boot` directory when devtools is active.

Config data files are considered in the following order:

1. [Application properties](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files) packaged inside your jar (`application.properties` and YAML variants).
2. [Profile-specific application properties](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files.profile-specific) packaged inside your jar (`application-{profile}.properties` and YAML variants).
3. [Application properties](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files) outside of your packaged jar (`application.properties` and YAML variants).
4. [Profile-specific application properties](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files.profile-specific) outside of your packaged jar (`application-{profile}.properties` and YAML variants).

> **注意**： 最好在整个项目中使用同一格式的配置文件，若是 `.properties` 和 `.yaml/.yml` 同时存在相同的位置 `.properties` 配置文件优先级高。

> 注意：如果使用环境变量而不是系统属性，大多数操作系统不允许使用句点分隔的键名，但可以使用下划线代替。 (示例：`SPRING_CONFIG_NAME` 而不是 `spring.config.name`).

#### 外部应用程序属性

Spring Boot 会自动从下位置查找并加载 `application.properties` 和 `application.yaml` 文件：

1. From the classpath
   1. The classpath root
   2. The classpath `/config` package
2. 从当前目录
   1. 从当前目录
   2. 当前目录中 `config/` 子目录
   3. Immediate child directories of the `config/` subdirectory

列表按优先级排序（优先级较低的项会覆盖优先级较高的项）。已加载文件中的文档将作为 `PropertySource` 实例添加到 Spring [`Environment`](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/core/env/Environment.html) 中。

最后的配置文件优先级：当前目录下 `config/` 子目录 > 当前目录 > 类路径下 `/config` 包  >  类路径根


### 自定义自动配置类

[创建自己的自定义配置类说明文档链接](https://docs.spring.io/spring-boot/3.5/reference/features/developing-auto-configuration.html)

1. 在自动配置类上添加  [`@AutoConfiguration`](https://docs.spring.io/spring-boot/3.5.16/api/java/org/springframework/boot/autoconfigure/AutoConfiguration.html) 注解

```java
@AutoConfiguration
// Some conditions ...
public class MyAutoConfiguration {}
```

2. 配置自动配置对象 

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.mycorp.libx.autoconfigure.LibXAutoConfiguration
com.mycorp.libx.autoconfigure.LibXWebAutoConfiguration
```

> 导入文件可以使用 `#` 作为注释
>
> 不常见的内部自动配置类应该使用 `$` 分隔，`com.example.at.MyAutoConfiguration$InnerClass`

若需要定制自动配置类的加载顺序，使用  [`@AutoConfigureOrder`](https://docs.spring.io/spring-boot/3.5.16/api/java/org/springframework/boot/autoconfigure/AutoConfigureOrder.html) 注解，功能语法和 `Order` 注解一致，只是前者为自定配置类提供专门的定制顺序。

## SpringBoot常见问题

### Mockito测试用例运行警告

#### 警告提示

```
Mockito is currently self-attaching to enable the inline-mock-maker. This will no longer work in future releases of the JDK. Please add Mockito as an agent to your build as described in Mockito's documentation: https://javadoc.io/doc/org.mockito/mockito-core/latest/org.mockito/org/mockito/Mockito.html#0.3
WARNING: A Java agent has been loaded dynamically (C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\net.bytebuddy\byte-buddy-agent\1.17.8\f09415827a71be7ed621c7bd02550678f28bc81c\byte-buddy-agent-1.17.8.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
Java HotSpot(TM) 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
```

**翻译成人话**：Mockito 为了实现"mock final类、static方法"这类高级功能（这套机制叫 `inline-mock-maker`），需要在你的程序运行过程中，**偷偷地把自己作为一个"外挂工具"（Java Agent）挂载到JVM上**。这个"运行时偷偷挂载"的动作，就是所谓的 **self-attaching**（自我附加）。

JDK官方现在明确表态："以后的JDK版本，不再允许这种'运行时偷偷挂载'的行为了"——这是出于安全考虑，因为"程序运行过程中随意给自己加载外挂工具"这种能力，如果被恶意代码利用，可以做很多危险的事情（比如动态篡改已经加载的类、注入恶意代码），所以JDK要收紧这个口子。

##### WARNING（警告）具体表现

```
WARNING: A Java agent has been loaded dynamically (...)
```

这行就是在告诉你："看，Mockito现在就是用了这种'动态加载agent'的方式，这是被记录在案的"。

```
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

这行是最关键的一句：**"未来的JDK版本，这种动态加载方式会被默认禁止"**——也就是说，如果你什么都不改，等你以后升级JDK到某个新版本，Mockito可能会直接报错崩溃，因为它赖以工作的这个"偷偷挂载"机制被JDK彻底堵死了。

##### 用一个类比理解

想象JDK是一栋大楼，Mockito是个维修工。以前维修工可以**没有任何登记手续，直接从任意一扇门溜进大楼**去干活（self-attaching）。现在大楼保安（JDK）说："以后不允许这种没有登记的溜门行为了，你必须提前在门卫室登记（作为javaagent显式声明），走正门进来。"

**目前这只是"警告"阶段**（保安还睁一只眼闭一只眼，允许你先溜进去，但会提醒你"以后不行了"）；但未来某个JDK版本开始，保安会**真的把没登记的人拦在门外**（Mockito直接失效，测试跑不起来）。

#### 推荐解决配置

##### build.gradle配置

```groovy
configurations {
    mockitoAgent {
        canBeResolved = true
        canBeConsumed = false
    }
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // 官方标准测试依赖，一次引入全部常用测试库
    testImplementation 'org.springframework.boot:spring-boot-starter-test'

    // 之前讨论过的Mockito javaagent修复
    mockitoAgent('org.mockito:mockito-core') {
        transitive = false
    }
}

tasks.named('test') {
    useJUnitPlatform()
    jvmArgs "-javaagent:${configurations.mockitoAgent.singleFile}"
}
```

##### IDEA中Gradle运行和IDEA自己运行区别

IntelliJ 里"用Gradle运行"和"用IDEA自己的运行器运行"，本质是**两套完全不同的执行链路**：

核心区别：谁来负责启动 JVM、加载 classpath、跑测试

|                                           | IntelliJ 原生运行器                                          | Gradle 运行                                                  |
| ----------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------ |
| **执行方式**                              | IntelliJ自己内置的测试引擎(JUnit Runner)直接启动JVM跑测试    | IntelliJ在后台唤起一个真正的Gradle进程(`gradle test`)，由Gradle来编排整个流程 |
| **速度**                                  | 快，尤其是重复跑同一个测试类(IntelliJ有自己的增量编译缓存)   | 相对慢，Gradle daemon启动、依赖解析、task图构建都有开销      |
| **配置来源**                              | 主要读取IntelliJ自己维护的模块/依赖信息(基于.idea项目文件)   | 完整读取`build.gradle`里的每一个task配置                     |
| **`build.gradle` 里的自定义配置是否生效** | **不一定生效**，尤其是`tasks.test`里写的`jvmArgs`、`systemProperty`等自定义参数 | **完全生效**，因为就是Gradle自己在执行这个task               |
| **依赖解析**                              | IntelliJ自己缓存的一份依赖信息(跟Gradle同步过来的，但不是实时) | 每次都严格按Gradle实际解析结果来                             |
| **多模块/复杂构建逻辑**                   | 可能有偏差，尤其项目用了自定义Gradle插件/task依赖链          | 100%还原真实构建行为                                         |

一个折中方案混合使用，IntelliJ 支持**分别设置**"运行测试"和"构建项目"用哪种方式，路径同样在：
 `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle`

```
Build and run using: [IntelliJ IDEA ▼]   ← 编译速度更快
Run tests using:     [Gradle ▼]          ← 保证测试配置(比如javaagent)完整生效
```

这样"日常写代码、编译"走IntelliJ自己的编译器（速度快），但"跑测试"这个动作走Gradle（保证`build.gradle`里的所有配置都真实生效），是很多团队实际采用的折中配置，兼顾了速度和正确性。

## 中间件部署

### nacos/mysql/redis容器批量部署

采用 `docker-compose` 批量启动，`docker-compose.yaml`

```yaml
networks:
  middleware-net:
    driver: bridge

services:
  mysql:
    image: mysql:8.0.46
    container_name: middleware-mysql
    restart: unless-stopped
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_general_ci
      - --max_connections=1000
      - --lower_case_table_names=1
    environment:
      MYSQL_ROOT_PASSWORD: mysql
      MYSQL_DATABASE: test
    volumes:
      - ./mysql-data:/var/lib/mysql
      # 容器首次启动(数据目录为空)时会自动按文件名顺序执行这个目录下的sql，完成建库建表
      # - ./mysql-init:/docker-entrypoint-initdb.d
    ports:
      - "3306:3306"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-uroot", "-pmysql"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - middleware-net

  redis:
    image: redis:7.4.10
    container_name: middleware-redis
    restart: unless-stopped
    command: redis-server --requirepass redis --appendonly yes
    volumes:
      - ./redis-data:/data
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "-a", "redis", "ping"]
      interval: 10s
      timeout: 5s
      retries: 10
    networks:
      - middleware-net

  nacos:
    image: nacos/nacos-server:v2.5.2
    container_name: nacos-server
    restart: unless-stopped
    environment:
      MODE: standalone
      PREFER_HOST_MODE: hostname

      # 关键: 必须显式声明用mysql，否则默认走内嵌derby，MySQL白配了
      # SPRING_DATASOURCE_PLATFORM: mysql
      # MYSQL_SERVICE_HOST: mysql
      # MYSQL_SERVICE_PORT: 3306
      # MYSQL_SERVICE_DB_NAME: ${MYSQL_DATABASE}
      # MYSQL_SERVICE_USER: root
      # MYSQL_SERVICE_PASSWORD: ${MYSQL_ROOT_PASSWORD}

      # 鉴权(2.5.1起强烈建议开启，不开的话默认无认证任何人可读写配置)
      NACOS_AUTH_ENABLE: "true"
      # abcdef1234567890abcdef1234567890
      NACOS_AUTH_TOKEN: "YWJjZGVmMTIzNDU2Nzg5MGFiY2RlZjEyMzQ1Njc4OTAK"
      NACOS_AUTH_IDENTITY_KEY: "nacos"
      NACOS_AUTH_IDENTITY_VALUE: "nacos"

      # JVM内存参数(单机测试/小规模场景，生产大规模需要按实际调大)
      JVM_XMS: 512m
      JVM_XMX: 512m
      JVM_XMN: 256m
    volumes:
      - ./nacos-logs:/home/nacos/logs
    ports:
      - "8848:8848"    # 主端口: 控制台 + OpenAPI
      - "9848:9848"    # gRPC端口(客户端SDK使用，2.x起必须开放，否则服务注册/配置监听会失败)
      - "9849:9849"    # gRPC端口(集群间通信用，单机模式也建议开放以防后续扩集群)
    depends_on:
      mysql:
        condition: service_healthy
    networks:
      - middleware-net
```

