# Log4j2 全局异步日志配置说明

## 1. 背景

Log4j2 支持两种"异步"方式，容易混淆，先说明区别：

| 方式 | 实现原理 | 异步范围 | 配置位置 |
|---|---|---|---|
| **Appender 级异步**（`<Async>`） | 用单独线程写文件，日志事件先进内存队列 | 只有被 `<Async>` 包裹的 Appender 异步，Logger 调用本身仍是同步的 | 仅 XML |
| **全局异步**（本文档）| 基于 LMAX Disruptor 无锁队列，替换整个 LoggerContext 实现 | **所有** Logger（包括框架、三方库日志）全部异步，`log.info()` 这行代码本身几乎不阻塞 | 系统属性/配置文件 + XML |

全局异步性能更高（官方基准测试可达同步模式的 10 倍以上吞吐），但生效开关**不在 XML 里**，这是最容易漏配、也最容易踩坑的地方。

---

## 2. 配置流程（四步）

### 第一步：添加 Disruptor 依赖

全局异步的底层队列基于 LMAX Disruptor 实现，**不加这个依赖，异步选择器不会报错，而是静默降级为同步模式**，非常隐蔽。

**Maven：**
```xml
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>3.4.4</version>
</dependency>
```

**Gradle：**
```groovy
implementation 'com.lmax:disruptor:3.4.4'
```

版本可根据实际 Log4j2 版本兼容性调整，一般 3.4.x 系列均可。

---

### 第二步：开启全局异步选择器

有三种等效方式，作用相同，**任选其一**：

#### 方式 A：`log4j2.component.properties`（推荐）

在 `src/main/resources/` 下新建文件 `log4j2.component.properties`：

```properties
# 开启全局异步：所有 Logger（含 Root）都异步执行
log4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector

# 队列满时的处理策略：Block（阻塞等待，默认，最安全）| Discard | DiscardIfQueueFull
log4j2.asyncQueueFullPolicy=Block

# Disruptor 环形缓冲区大小，必须是 2 的幂，默认 262144（26万条）
log4j2.asyncLoggerRingBufferSize=262144
```

**推荐这种方式**，原因：
- 随项目代码一起打包、纳入版本控制，不依赖启动脚本，团队协作不会漏配。
- 换机器、换容器部署时配置自动跟随，无需额外传参。

#### 方式 B：JVM 启动参数

不改代码，适合容器化部署时通过启动命令控制：

```bash
java -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector -jar app.jar
```

#### 方式 C：环境变量

```bash
export LOG4J_CONTEXT_SELECTOR=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
```

> 三种方式优先级：JVM 参数 > 环境变量 > `log4j2.component.properties`。混用时以优先级高的为准，正常情况下选一种即可，不要混用。

---

### 第三步：编写 `log4j2-spring.xml`

**关键点：全局异步生效后，`<Logger>`/`<Root>` 用普通标签即可，不需要写成 `<AsyncLogger>`/`<AsyncRoot>`，也不需要再用 `<Async>` 包装 Appender。**

框架会在 `AsyncLoggerContextSelector` 生效的前提下，自动让所有日志调用走异步路径，这是"全局"异步和"混合异步"（部分 Logger 异步）最大的区别——全局模式下 XML 里完全看不出"异步"字样，异步与否由第二步的配置文件决定。

核心结构（完整文件另见 `log4j2-spring.xml`）：

```xml
<Configuration status="WARN" monitorInterval="60">
    <Appenders>
        <Console name="Console" .../>
        <RollingFile name="RollingFileAll" ...>
            <!-- 滚动策略、清理策略见下方"日志滚动与保留策略"章节 -->
        </RollingFile>
        <RollingFile name="RollingFileError" ...>
            <ThresholdFilter level="ERROR" onMatch="ACCEPT" onMismatch="DENY"/>
        </RollingFile>
    </Appenders>

    <Loggers>
        <Logger name="com.example" level="INFO" additivity="false">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="RollingFileAll"/>
            <AppenderRef ref="RollingFileError"/>
        </Logger>

        <Root level="INFO">
            <AppenderRef ref="Console"/>
            <AppenderRef ref="RollingFileAll"/>
            <AppenderRef ref="RollingFileError"/>
        </Root>
    </Loggers>
</Configuration>
```

---

### 第四步：验证是否真的生效

因为漏配第一、二步都**不会报错**，必须主动验证，不能只看"能跑起来"就认为配置成功。

**方式 1：代码里打印 Context 类名**

```java
import org.apache.logging.log4j.LogManager;

System.out.println(LogManager.getContext().getClass().getName());
// 全局异步生效时应输出包含 AsyncLoggerContext 的类名
// 例如：org.apache.logging.log4j.core.async.AsyncLoggerContext
// 若输出的是 LoggerContext（不带 Async），说明没有生效，回查第一、二步
```

**方式 2：启动时开启调试模式**

```bash
java -Dlog4j2.debug=true -jar app.jar
```

启动日志中搜索 `contextSelector`，确认加载的是 `AsyncLoggerContextSelector`。

**方式 3：简单压测对比**

写一段循环打印几十万条日志的代码，分别在同步模式和全局异步模式下计时，异步模式下方法调用应明显更快（业务线程不再等待磁盘 IO）。

---

## 3. 日志滚动与保留策略（配套说明）

本次配置同时满足以下要求，记录在此备查：

| 要求 | 实现方式 |
|---|---|
| 按天滚动 | `TimeBasedTriggeringPolicy interval="1" modulate="true"` |
| 单文件最大 500MB | `SizeBasedTriggeringPolicy size="500MB"` |
| 每天最多 10 个文件 | `DefaultRolloverStrategy max="10"`（`filePattern` 中 `%i` 从 1 到 10，超过后最旧序号被覆盖） |
| 保留 15 天 | `Delete` 下 `IfLastModified age="15d"` |
| 所有归档文件总大小不超过 10GB | `Delete` 下 `IfAccumulatedFileSize exceeds="10GB"`，超出后从最旧文件开始删 |

**易踩坑点：`maxDepth` 必须匹配 `filePattern` 的目录层级**

```xml
<Delete basePath="${LOG_HOME}/history" maxDepth="1">
```

`maxDepth="1"` 表示只扫描 `basePath` 目录下的**直接文件**。如果 `filePattern` 里按年月分了子目录（如 `history/%d{yyyy-MM}/xxx.log.gz`），文件实际在第 2 层，此时必须把 `maxDepth` 改成 `2`，否则 `Delete` 动作形同虚设，日志会无限堆积而不自知。

---

## 4. 全局异步的注意事项

### 4.1 性能收益会被 `includeLocation` 抵消

如果日志格式（`PatternLayout`）里用了以下转换符：

```
%C（类名）  %L（行号）  %M（方法名）  %l（完整位置信息）
```

Log4j2 需要通过栈追踪获取这些信息，这个开销**在异步模式下依然存在**，且相对更明显（因为其他部分变快了，栈追踪的固定成本占比变高）。生产环境建议去掉这些转换符，只保留时间、线程、级别、Logger 名、消息本身。

### 4.2 队列满时的行为选择

`log4j2.asyncQueueFullPolicy` 决定队列写满后的处理方式：

| 值 | 行为 | 适用场景 |
|---|---|---|
| `Block`（默认） | 业务线程短暂阻塞等待，不丢日志 | 绝大多数业务系统，推荐保持默认 |
| `Discard` | 丢弃低于 `discardThreshold` 级别的日志 | 对延迟极度敏感、可以接受丢日志的场景（如高频交易） |
| `DiscardIfQueueFull` | 队列满时直接丢弃，不做级别判断 | 类似上面，更激进 |

**除非有明确的低延迟需求，否则保持 `Block`**，避免生产问题排查时发现关键日志丢失。

### 4.3 全局异步 vs 混合异步的取舍

全局异步会让**所有**日志（包括 Spring、MyBatis 等三方库的日志）都异步化。如果有特定场景要求日志必须同步落盘（例如审计日志要求强一致，写入后立即可查），需要额外处理：

- 方案一：改用"混合异步"模式（`<AsyncLogger>` 标签只包裹业务 Logger，其余保持默认同步），而不是全局异步。具体配置见下方"第 5 章 混合异步配置"。
- 方案二：全局异步 + 该 Logger 单独配置 `immediateFlush="true"` 且业务代码在关键节点调用 `LogManager.shutdown()` 前不退出，但这种方式较复杂，一般不推荐，优先考虑方案一。

### 4.4 优雅关闭

异步日志在 JVM 关闭时，队列中尚未落盘的日志需要时间刷完。Log4j2 默认会注册 Shutdown Hook 自动处理，正常 `kill`（非 `kill -9`）或 Spring Boot 正常退出流程下不需要额外处理。如果使用了自定义的进程管理方式强制杀进程，可能导致队列中最后一批日志丢失，需要确认进程退出方式。

---

## 5. 混合异步配置（AsyncLogger / AsyncRoot）

混合异步是全局异步的替代方案：**只让指定的 Logger 异步，其余保持同步**，完全在 XML 内完成配置，不需要 `log4j2.component.properties`，也不依赖 `AsyncLoggerContextSelector`。

### 5.1 适用场景

| 场景 | 建议 |
|---|---|
| 只想业务日志异步，框架/三方库日志保持同步 | 混合异步 |
| 审计日志、合规日志要求写入后立即可查（强一致） | 混合异步，审计 Logger 走普通 `<Logger>` |
| 希望后续按 Logger 粒度精细调整是否异步 | 混合异步，改动范围小、风险低 |
| 希望极致吞吐、日志量巨大、所有日志都能接受最终一致 | 全局异步（见前文第 2 章） |

混合异步的改造成本更低、影响面更小，很多团队会优先选择这种方式做增量优化，而不是一次性切到全局异步。

### 5.2 依赖要求

和全局异步一样，**仍然需要 Disruptor 依赖**，因为 `<AsyncLogger>`/`<AsyncRoot>` 标签底层同样基于 Disruptor 实现：

```xml
<dependency>
    <groupId>com.lmax</groupId>
    <artifactId>disruptor</artifactId>
    <version>3.4.4</version>
</dependency>
```

**不需要**配置 `log4j2.component.properties` 里的 `log4j2.contextSelector`——如果两者都配了，`AsyncLoggerContextSelector` 优先级更高，会让所有 Logger 变成全局异步，`<AsyncLogger>`/普通 `<Logger>` 的区分将失去意义。**混合异步和全局异步是互斥的两种方案，不要同时启用。**

### 5.3 XML 配置示例

在 `<Loggers>` 内，把需要异步的 Logger 写成 `<AsyncLogger>`，需要同步的仍写 `<Logger>`：

```xml
<Loggers>

    <!-- 业务日志：异步，走高性能路径 -->
    <AsyncLogger name="com.example" level="INFO" additivity="false" includeLocation="false">
        <AppenderRef ref="Console"/>
        <AppenderRef ref="RollingFileAll"/>
    </AsyncLogger>

    <!-- 审计日志：保持同步，确保写入后立即落盘、立即可查 -->
    <Logger name="com.example.audit" level="INFO" additivity="false">
        <AppenderRef ref="RollingFileError"/>
    </Logger>

    <!-- Spring / MyBatis 等框架日志：保持同步（默认行为，不用特殊处理） -->
    <Logger name="org.springframework" level="INFO" additivity="false">
        <AppenderRef ref="Console"/>
        <AppenderRef ref="RollingFileAll"/>
    </Logger>

    <!-- 根日志：可以是 AsyncRoot（兜底也异步），也可以是普通 Root（兜底同步） -->
    <AsyncRoot level="INFO" includeLocation="false">
        <AppenderRef ref="Console"/>
        <AppenderRef ref="RollingFileAll"/>
    </AsyncRoot>

</Loggers>
```

**要点说明：**

- `<AsyncLogger>`、`<AsyncRoot>` 是 Log4j2 提供的专用标签，语法和普通 `<Logger>`/`<Root>` 一致，唯一区别是内部实现异步化。
- `includeLocation="false"`：异步 Logger 建议显式关闭位置信息采集（类名、行号等），因为异步场景下获取调用栈的开销占比会更明显，不关闭会抵消异步带来的性能收益（这一点和第 4.1 节的建议一致）。
- 一个 Configuration 里可以同时存在 `<AsyncLogger>` 和 `<Logger>`，互不影响，按需搭配即可。
- 如果 `<Root>` 用普通标签而不是 `<AsyncRoot>`，意味着"没被任何 Logger 匹配到"的日志走同步兜底，这也是一种常见搭配（业务日志异步、未知来源日志同步兜底）。

### 5.4 与全局异步的关键区别（对照表）

| 对比项 | 全局异步 | 混合异步 |
|---|---|---|
| 生效开关 | `log4j2.component.properties`（或等效方式） | XML 内 `<AsyncLogger>`/`<AsyncRoot>` 标签 |
| 影响范围 | 所有 Logger，包括三方库 | 仅显式声明为 `<AsyncLogger>` 的 Logger |
| Disruptor 依赖 | 必需 | 必需 |
| 是否需要改 XML 标签名 | 不需要，普通 `<Logger>`/`<Root>` 即可 | 需要，把目标 Logger 改成 `<AsyncLogger>`/`<AsyncRoot>` |
| 验证方式 | `LogManager.getContext()` 类名 | 无法用这个方式验证（Context 本身仍是同步类型），需要看具体 Logger 的性能表现或源码确认 |
| 改造粒度 | 全局一刀切 | 按 Logger 精细控制 |
| 适合阶段 | 新项目、或已确认所有日志都能接受异步 | 存量项目增量优化、或有强一致性要求的日志并存 |

### 5.5 和全局异步版本的差异点

| 项          | `log4j2-spring.xml`（全局异步）    | `log4j2-spring-async.xml`（混合异步）    |
| ----------- | ---------------------------------- | ---------------------------------------- |
| 配套文件    | 需要 `log4j2.component.properties` | **不需要**                               |
| Logger 标签 | 普通 `<Logger>`/`<Root>`           | `<AsyncLogger>`/`<AsyncRoot>`            |
| 异步范围    | 所有日志                           | 只有 `com.example`（业务）和根日志走异步 |

依赖要求和之前一样，仍需要在 `pom.xml`/`build.gradle` 里加 `com.lmax:disruptor`，但**千万不要**同时配置 `log4j2.component.properties` 里的 `AsyncLoggerContextSelector`，否则这份文件里 `<AsyncLogger>` 和普通 `<Logger>` 的区分就失效了。

### 5.6 常见误区

- **误区一：以为加了 Disruptor 依赖就自动生效。** 混合异步必须显式把 Logger 标签改成 `<AsyncLogger>`，普通 `<Logger>` 即使加了依赖也不会变成异步。
- **误区二：同时配置全局异步选择器和 `<AsyncLogger>`。** 如前所述，`AsyncLoggerContextSelector` 一旦生效，会让所有 Logger 全局异步化，这时候 XML 里写不写 `<AsyncLogger>` 已经没有区别，容易造成"以为在混合模式、实际在全局模式"的误解，排查问题时会很困惑。
- **误区三：`<AsyncRoot>` 里也用了 `%C`/`%L` 等位置转换符，却没设置 `includeLocation="false"`。** 默认情况下 `includeLocation` 为 `true`，异步场景下务必显式关闭。

---

## 6. 配置文件清单

本次涉及的文件：

| 文件 | 作用 | 位置 |
|---|---|---|
| `log4j2-spring.xml` | 日志格式、Appender、滚动与保留策略；混合异步时在此声明 `<AsyncLogger>` | `src/main/resources/` |
| `log4j2.component.properties` | 开启全局异步选择器（**仅全局异步方案需要，混合异步不需要此文件**） | `src/main/resources/` |
| `pom.xml` / `build.gradle` | 添加 `disruptor` 依赖（两种异步方案都需要） | 项目根目录 |

**两种方案二选一**，不要混用：

- 走全局异步：三个文件都要，`log4j2-spring.xml` 里用普通 `<Logger>`/`<Root>`。
- 走混合异步：只需要 `log4j2-spring.xml`（改用 `<AsyncLogger>`/`<AsyncRoot>`）+ Disruptor 依赖，**不需要** `log4j2.component.properties`。
