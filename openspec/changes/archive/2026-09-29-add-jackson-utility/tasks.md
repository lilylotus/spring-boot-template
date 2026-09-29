# Tasks

## 1. 审阅与依赖确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/jackson-utilities/spec.md` 的明确确认。
- [x] 1.2 核对 `build.gradle` 中 `tools.jackson.core:jackson-databind:3.2.2` 的两处重复声明，合并为一条（保留在原 Netty RPC 依赖块，删除新增的 `/* jackson */` 重复行），运行 `gradlew.bat dependencies --configuration runtimeClasspath` 确认依赖解析结果不变。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.util.JacksonUtils`（`final` + 私有构造器），声明 `DATE_TIME_PATTERN`/`DATE_PATTERN`/`TIME_PATTERN` 三个格式常量（`yyyy-MM-dd HH:mm:ss`/`yyyy-MM-dd`/`HH:mm:ss`）和 `private static final ObjectMapper MAPPER`。
- [x] 2.2 实现私有 `buildMapper()`：构建 `SimpleModule` 注册 `LocalDateTime`/`LocalDate`/`LocalTime` 的序列化器和反序列化器（`tools.jackson.databind.ext.javatime.ser/deser` 包下对应类，按 2.1 的三个格式常量构造 `DateTimeFormatter`），并用 `defaultDateFormat` 为 `java.util.Date` 设置 `DATE_TIME_PATTERN` 对应的 `SimpleDateFormat`（`setLenient(false)`）；`MAPPER` 通过 `new ObjectMapper().rebuild()` 依次 `disable(FAIL_ON_UNKNOWN_PROPERTIES)`、`disable(FAIL_ON_MISSING_CREATOR_PROPERTIES)`、`disable(FAIL_ON_NULL_CREATOR_PROPERTIES)`、`changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)`、`defaultDateFormat(...)`、`addModule(dateTimeModule)` 后 `build()`。
- [x] 2.3 实现 `toJson(Object value)`：`value` 为 `null` 时直接返回 `null`；否则委托 `MAPPER.writeValueAsString(value)`，异常直接透传。
- [x] 2.4 实现 `fromJson(String json, Class<T> type)` 与 `fromJson(String json, TypeReference<T> type)`：`json`/`type` 为 `null` 时抛出带中文描述的 `IllegalArgumentException`；否则分别委托 `MAPPER.readValue(json, type)` 对应重载，异常直接透传。
- [x] 2.5 实现 `convert(Object value, Class<T> type)` 与 `convert(Object value, TypeReference<T> type)`：`type` 为 `null` 时抛出 `IllegalArgumentException`；`value` 为 `null` 时直接返回 `null`；否则分别委托 `MAPPER.convertValue(value, type)` 对应重载，异常直接透传。
- [x] 2.6 为 `JacksonUtils` 及全部公开方法补充中文 Javadoc：说明默认配置（四种日期时间类型的固定格式、忽略未知/缺失字段、序列化跳过 `null` 属性、不可定制）、`toJson`/`convert` 的空输入直接返回 `null`、`fromJson`/`convert` 的类型参数与 `fromJson` 的 JSON 文本参数为 `null` 时抛参数异常、异常透传语义（不包装 Jackson 异常）、线程安全性。

## 3. 测试

- [x] 3.1 新增 `JacksonUtilsTest`，测试 `toJson`：POJO、`List`、`Map` 正常序列化；`null` 入参直接返回 `null`（不抛异常）。
- [x] 3.2 测试 `toJson` 的日期时间与 `null` 字段处理：含 `LocalDateTime`/`LocalDate`/`LocalTime`/`java.util.Date` 字段的对象序列化为对应固定格式文本（非 ISO-8601、非时间戳）；对象中值为 `null` 的字段不出现在输出 JSON 中，其余字段正常输出。
- [x] 3.3 测试 `fromJson(String, Class)`：JSON 对象正确还原为 POJO；日期时间字段按固定格式解析并与序列化前的值相等（往返一致）；`json`/`type` 为 `null` 分别抛 `IllegalArgumentException`；JSON 含多余字段时正常反序列化并忽略；JSON 缺少目标类型的部分字段（含一个 Java `record` 缺字段的场景）时正常反序列化，缺失字段取默认值；非法 JSON 文本抛出 Jackson 自身的异常（断言异常类型属于 Jackson 异常层级，不是本工具自定义类型）。
- [x] 3.4 测试 `fromJson(String, TypeReference)`：反序列化 `List<Dto>`、`Map<String, Object>` 等带泛型信息的类型，元素类型正确保留；与等价的 `fromJson(String, Class)`（针对无泛型场景）结果一致。
- [x] 3.5 测试 `convert(Object, Class)`：`Map` 转 POJO、POJO 转 `Map` 双向转换；`value` 为 `null` 时直接返回 `null`（不抛异常）；`type` 为 `null` 时抛 `IllegalArgumentException`；转换结果中日期时间字段与 `null` 字段的处理与 `toJson`/`fromJson` 一致。
- [x] 3.6 测试 `convert(Object, TypeReference)`：转换为 `List<Dto>`、`Map<String, Object>` 等参数化类型，泛型信息正确保留；与 `toJson` + `fromJson` 两步走的结果做一致性比对，验证 `convertValue` 路径产生相同结果。
- [x] 3.7 测试并发安全：多线程并发调用 `toJson`/`fromJson`/`convert`，每个线程使用不同数据，验证互不干扰。

## 4. 文档与验证

- [x] 4.1 新增 `docs/JacksonUtils使用说明.md`：覆盖三组 API 的示例（含 `TypeReference` 用法）、默认配置说明（四种日期时间类型的固定格式、忽略未知/缺失字段、序列化跳过 `null` 属性、`toJson`/`convert` 空输入直接返回 `null`）、异常处理说明（`IllegalArgumentException` 的触发范围 vs Jackson 原生异常，不做包装）；在 `README.md` 增加一行入口链接。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-jackson-utility --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 5. 新增反序列化输入来源（byte[]/InputStream）

- [x] 5.1 获得用户对 `proposal.md`、`design.md`、`specs/jackson-utilities/spec.md` 中新增的 `byte[]`/`InputStream` 反序列化能力的明确确认。
- [x] 5.2 在 `buildMapper()` 中追加 `.disable(StreamReadFeature.AUTO_CLOSE_SOURCE)`，确保 `fromJson(InputStream, ...)` 不关闭调用方传入的流。
- [x] 5.3 实现 `fromJson(byte[] json, Class<T> type)` 与 `fromJson(byte[] json, TypeReference<T> type)`：`json`/`type` 为 `null` 时抛出 `IllegalArgumentException`；否则分别委托 `MAPPER.readValue(json, type)` 对应重载，异常直接透传。
- [x] 5.4 实现 `fromJson(InputStream json, Class<T> type)` 与 `fromJson(InputStream json, TypeReference<T> type)`：`json`/`type` 为 `null` 时抛出 `IllegalArgumentException`；否则分别委托 `MAPPER.readValue(json, type)` 对应重载，不关闭 `json`，异常直接透传。
- [x] 5.5 为新增的四个 `fromJson` 重载补充中文 Javadoc：说明输入来源、`null` 抛参数异常、`InputStream` 版本不关闭调用方传入的流、异常透传语义。
- [x] 5.6 新增测试：`fromJson(byte[], Class)`/`fromJson(byte[], TypeReference)` 与等价 `String` 输入结果一致；`json`/`type` 为 `null` 抛 `IllegalArgumentException`。
- [x] 5.7 新增测试：`fromJson(InputStream, Class)`/`fromJson(InputStream, TypeReference)` 与等价 `String` 输入结果一致；用记录 `close()` 调用的包装流断言读取完成后未关闭；`json`/`type` 为 `null` 抛 `IllegalArgumentException`。
- [x] 5.8 更新 `docs/JacksonUtils使用说明.md`：补充 `byte[]`/`InputStream` 两种来源的示例、“不关闭调用方传入的 InputStream”的说明，以及 `fromJson(null, ...)` 字面量因三路重载产生编译歧义的提示。
- [x] 5.9 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告；运行 `openspec validate add-jackson-utility --strict`，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat dependencies --configuration runtimeClasspath`：合并重复声明后 `tools.jackson.core:jackson-databind:3.2.2` 仍只解析出一条记录，结果不变。
- `gradlew.bat test`：全量测试通过，含 `JacksonUtilsTest`（17 个用例，覆盖序列化/反序列化三种来源/转换/并发）。
- `gradlew.bat build`：构建成功（含 `xlsxMemoryTest`），无新增编译或弃用警告；构建过程中出现的 `Log4j API could not find a logging provider` 为既有第三方依赖的日志噪音，与本变更无关。
- `openspec validate add-jackson-utility --strict`：`Change 'add-jackson-utility' is valid`。
- 实测发现并记录一个 API 易用性注意点：`fromJson(null, XxxClass.class)` 这类直接传 `null` 字面量的调用，因 `String`/`byte[]`/`InputStream` 三个重载同时适用会导致编译期重载歧义（不是运行时问题），已在 `JacksonUtils使用说明.md` 中提示调用方改用显式类型的 `null` 变量或强转。
