## 背景与原因

项目已依赖 `tools.jackson.core:jackson-databind:3.2.2`（用于 Netty RPC 的 `JacksonJsonSerializer`），但没有面向普通业务代码的通用 JSON 工具。日常场景（日志打印、缓存序列化、DTO 互转、读取第三方接口响应）目前只能各自创建 `ObjectMapper` 或直接依赖 `JacksonJsonSerializer`（其配置和异常语义是为 RPC 帧编解码定制的，不适合复用）。需要一个轻量、无状态的通用封装。

## 变更内容

- 新增 `org.example.simple.util.JacksonUtils`，与 `SnowflakeUtils`、`GroovyScriptUtils`、`ExecutorServiceUtils` 同级（简单工具类不单独分包）。
- 提供三组能力：
  - `String toJson(Object value)`：对象序列化为 JSON 字符串。
  - `<T> T fromJson(String json, Class<T> type)`/`<T> T fromJson(String json, TypeReference<T> type)`、
    `<T> T fromJson(byte[] json, Class<T> type)`/`<T> T fromJson(byte[] json, TypeReference<T> type)`、
    `<T> T fromJson(InputStream json, Class<T> type)`/`<T> T fromJson(InputStream json, TypeReference<T> type)`：
    JSON 反序列化为对象，支持 `String`/`byte[]`/`InputStream` 三种输入来源（分别覆盖文本、网络/文件读取到的字节、已打开的输入流三类常见场景），每种来源都分别支持不带泛型的 `Class` 和带泛型信息的 `TypeReference` 两种目标类型声明方式。
  - `<T> T convert(Object value, Class<T> type)` 与 `<T> T convert(Object value, TypeReference<T> type)`：对象到对象的转换（如 `Map`↔POJO、宽松类型的字段拷贝），底层复用 Jackson 的 `convertValue`，不经过 JSON 文本。
- 内部使用单个共享、不可变的默认 `ObjectMapper`：
  - `LocalDateTime`/`LocalDate`/`LocalTime`/`java.util.Date` 使用固定文本格式（`yyyy-MM-dd HH:mm:ss`/`yyyy-MM-dd`/`HH:mm:ss`），与 `JacksonJsonSerializer` 的既有约定保持一致，不依赖 Jackson 3 的默认 ISO-8601 输出。
  - 反序列化时容忍多余字段（`FAIL_ON_UNKNOWN_PROPERTIES`）和缺失字段（`FAIL_ON_MISSING_CREATOR_PROPERTIES`/`FAIL_ON_NULL_CREATOR_PROPERTIES`），JSON 缺少目标类型声明的字段时不报错，该字段取默认值。
  - 序列化时跳过值为 `null` 的字段，不写入输出 JSON（`NON_NULL` 属性包含策略）。
- `toJson(null)` 与 `convert(null, type)` 直接返回 `null`，不调用底层序列化/转换逻辑，也不抛出异常。`fromJson` 的三种输入来源参数（`json` 为 `null` 的 `String`/`byte[]`/`InputStream`）均不采用“空进空出”，与 `String` 版本一致抛出 `IllegalArgumentException`。
- `fromJson(InputStream json, ...)` 不关闭调用方传入的 `InputStream`，由调用方用 try-with-resources 管理，与 `ExcelUtils`/`QrCodeUtils` 对输入流的既有约定一致。
- `TypeReference` 使用项目已依赖的 `tools.jackson.core.type.TypeReference`，不引入 Jackson 2（`com.fasterxml.jackson.core.type.TypeReference`）。
- `build.gradle` 无需新增依赖：`tools.jackson.core:jackson-databind:3.2.2` 已声明（原本注释为 Netty RPC 用途；`docs/` 会说明其现在被两处复用）。工作区当前已有一行重复声明（`/* jackson */` 注释下的同一依赖坐标），本变更实施时一并整理为一行，不新增第二条坐标。
- 补充 `docs/JacksonUtils使用说明.md`，并在 `README.md` 增加入口链接，与既有文档组织方式一致。

## 能力

### 新增能力

- `jackson-utilities`：通用 JSON 序列化、反序列化和对象转换契约，包含默认 `ObjectMapper` 配置、`Class`/`TypeReference` 两种类型入口、参数校验与失败语义。

### 修改能力

无。`JacksonJsonSerializer`（RPC 专用）不受影响，不复用本工具的内部 `ObjectMapper` 实例（两者配置目的不同：RPC 需要固定日期文本格式和输入结构校验，本工具面向通用场景）。

## 非目标

- 不提供可自定义/注入 `ObjectMapper` 的构造方式；需要定制配置时直接使用 Jackson API 或参考 `JacksonJsonSerializer` 的做法。
- 不提供 JSON 树（`JsonNode`）层面的操作入口（`JacksonJsonSerializer` 已有 `toTree`/`fromTree`，不重复建设）。
- 不提供 XML/YAML 等其他数据格式的读写。
- 不定义专属异常类型：Jackson 3 的读写异常（`JacksonException` 及其子类）本身已是非受检异常且携带足够诊断信息，直接透传，不做二次包装；仅对无法用“返回 `null`”表达的非法入参（`fromJson`/`convert` 的目标类型为 `null`、`fromJson` 的 JSON 文本为 `null`）抛出 `IllegalArgumentException`。
- 不提供美化输出（pretty print）、流式解析（`JsonParser`/`JsonGenerator`）等高级入口。

## 影响范围

- 新增文件集中在 `src/main/java/org/example/simple/util/JacksonUtils.java` 与对应测试类，不改动任何既有生产代码（含 `JacksonJsonSerializer`）。
- `build.gradle` 仅整理重复的 Jackson 依赖声明为一行，不调整版本。
- 新增文档 `docs/JacksonUtils使用说明.md` 与 `README.md` 的一行链接。
- 工作区当前存在与本变更无关的未提交内容（前一轮已确认的 jcstress 相关改动已被移除；如工作区仍有其他未提交内容，本变更不涉及也不代为提交）。
