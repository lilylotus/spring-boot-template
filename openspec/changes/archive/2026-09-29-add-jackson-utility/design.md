## 背景

项目为 Java 21 单模块 Gradle 工程，已依赖 `tools.jackson.core:jackson-databind:3.2.2`（Jackson 3，包名前缀为 `tools.jackson.*`，不是 Jackson 2 的 `com.fasterxml.jackson.*`）。既有用法见 `org.example.simple.rpc.common.JacksonJsonSerializer`：`ObjectMapper` 通过 `new ObjectMapper()` 创建后用 `rebuild()...build()` 叠加定制配置，读写方法（`writeValueAsBytes`/`readValue`）在 Jackson 3 中已是非受检的 `JacksonException`（继承 `RuntimeException`），无需 `try/catch IOException`。

简单工具类（`SnowflakeUtils`、`ExecutorServiceUtils`、`GroovyScriptUtils`）直接放在 `org.example.simple.util` 顶层，不单独分包；只有涉及多个协作类的复杂能力（`util.codec`、`util.excel`、`util.qrcode`）才分子包。本次新增的 `JacksonUtils` 只有一个类、一个内部共享 `ObjectMapper`，属于前一类。

## 目标与非目标

目标：提供无状态静态工具类，覆盖对象序列化为 JSON 字符串、JSON 反序列化为对象（`String`/`byte[]`/`InputStream` 三种输入来源，均支持 `Class` 与 `TypeReference` 两种类型入口）、对象到对象的转换（同样支持 `Class` 与 `TypeReference`）；默认配置需要满足：常用日期时间类型使用固定文本格式、反序列化容忍缺失字段、序列化跳过 `null` 字段、序列化/转换的空对象直接返回 `null`、反序列化不关闭调用方传入的 `InputStream`。

非目标：可注入的自定义 `ObjectMapper`、`JsonNode` 树操作、XML/YAML 等其他格式、专属异常类型、美化输出、流式 API。详见 `proposal.md` 的非目标小节。

## 技术决策

### 复用既有依赖，不新增第三方库

`tools.jackson.core:jackson-databind:3.2.2` 已经是本项目的生产依赖（`JacksonJsonSerializer` 使用）。本变更不新增依赖坐标，只是新增一个使用方；实施时顺带把 `build.gradle` 中重复出现的同一依赖声明（一条在 Netty RPC 注释块下，一条在新加的 `/* jackson */` 注释块下）合并为一条，避免 Gradle 依赖列表里出现两行相同坐标造成阅读困惑（两行坐标完全一致，合并不改变实际解析结果，属于纯清理）。

`TypeReference` 使用 `tools.jackson.core.type.TypeReference`（Jackson 3 的类型，与项目已声明的 `jackson-core`/`jackson-databind` 同源），不是 `com.fasterxml.jackson.core.type.TypeReference`（项目未直接依赖 Jackson 2 的 `TypeReference`，即使传递依赖里存在别的 Jackson 2 构件，也不应该让公开 API 意外依赖到它）。

### 默认 `ObjectMapper` 配置

```java
private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
private static final String DATE_PATTERN = "yyyy-MM-dd";
private static final String TIME_PATTERN = "HH:mm:ss";

private static final ObjectMapper MAPPER = buildMapper();

private static ObjectMapper buildMapper() {
    DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
    DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN);
    SimpleDateFormat legacyDateFormat = new SimpleDateFormat(DATE_TIME_PATTERN);
    legacyDateFormat.setLenient(false);

    SimpleModule dateTimeModule = new SimpleModule("JacksonUtils 日期时间格式模块")
        .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter))
        .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter))
        .addSerializer(LocalDate.class, new LocalDateSerializer(dateFormatter))
        .addDeserializer(LocalDate.class, new LocalDateDeserializer(dateFormatter))
        .addSerializer(LocalTime.class, new LocalTimeSerializer(timeFormatter))
        .addDeserializer(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

    return new ObjectMapper()
        .rebuild()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
        .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
        .defaultDateFormat(legacyDateFormat)
        .addModule(dateTimeModule)
        .build();
}
```

四项配置逐一说明：

**1. 日期时间使用固定文本格式，不依赖 Jackson 3 的默认 ISO-8601 输出。** 已用项目实际依赖的 3.2.2 版本通过反射验证：`DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS.enabledByDefault()` 返回 `false`，也就是说即使完全不配置，`LocalDateTime` 等类型也已经会序列化为 ISO-8601 字符串（如 `2024-01-01T10:30:00`）而不是数值时间戳数组。但 ISO-8601 的 `T` 分隔符不是国内业务系统习惯的日期时间文本格式，直接依赖“库默认行为”也让格式随 Jackson 版本演进而变化、不便固定为对外契约。因此显式注册 `LocalDateTime`/`LocalDate`/`LocalTime` 的序列化器和反序列化器，统一用 `yyyy-MM-dd HH:mm:ss`/`yyyy-MM-dd`/`HH:mm:ss` 三种模式，并对 `java.util.Date` 使用 `defaultDateFormat` 设置相同的日期时间模式——四种类型的写法与 `JacksonJsonSerializer.configure()` 完全同源（`tools.jackson.databind.ext.javatime.ser/deser` 包内建于 `jackson-databind`，不需要额外依赖），只是新增了 `LocalDate`/`LocalTime` 两种此前 RPC 场景未用到的类型，保持项目内日期时间格式的一致约定。

**2. 反序列化时容忍缺失字段，不因此报错。** 用同样的反射探测方式验证过：`DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES` 和 `FAIL_ON_NULL_CREATOR_PROPERTIES` 在 Jackson 3.2.2 中默认值都已经是 `false`（Jackson 2 时代常需要显式关闭），并且用一个 3 字段 record 和一个 3 字段普通 POJO 做过实测：JSON 缺失其中一个字段时，默认 `new ObjectMapper()` 不加任何配置就能成功反序列化，缺失的字段（含 record 的构造参数）取对应类型的默认值（引用类型为 `null`，基本类型为 `0`）。也就是说这一行为其实已经是 Jackson 3 的默认行为，本可以不额外配置；但既然这是被明确提出的功能需求，仍然显式禁用这两个开关，把这份行为从“恰好符合当前版本默认值”变成“工具类自己承诺、不随第三方库升级而改变”的显式契约，并在测试中断言，而不是依赖一个未来版本可能变化的隐式默认值。`FAIL_ON_UNKNOWN_PROPERTIES` 同理：反射验证其默认值同样已是 `false`，显式禁用是出于同样的“锁定契约”考虑，而不是因为默认值不满足需求。（`DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES` 未改动，仍是默认的 `true`：它处理的是 JSON 显式给出 `null` 却映射到基本类型字段的情形，属于数据本身有问题，与“字段缺失”是两回事，本次需求没有涉及，不一并放宽。）

**3. 序列化时跳过值为 `null` 的字段。** 通过 `changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)` 把默认属性包含策略改为 `NON_NULL`（对象属性和集合/`Map` 内容两个维度都生效），值为 `null` 的字段不再出现在输出 JSON 中。这是从“完整还原对象结构（含 null）”转向“只表达确有其值的字段”的常见业务需求（减小体积、避免下游把 `null` 和“未提供该字段”混淆）。

**4. `toJson(null)` 与 `convert(null, type)` 直接返回 `null`。** 与本工具此前草案中“拒绝 `null` 主输入”的统一风格不同，这里按需求明确改为“空进空出”：序列化 `null` 语义上就是没有对象可序列化，返回 `null` 比抛异常更符合调用方在拼装可选字段时的直觉（例如把一个可能不存在的关联对象序列化后塞进另一个结构，源对象为 `null` 时最终结果自然也应该是 `null`，不需要调用方额外做判空）。`convert` 同理。两者仍然只放宽“值参数”的 `null`，目标类型参数（`Class`/`TypeReference`）为 `null` 时依然拒绝，因为类型参数是方法能否工作的前提，不存在“合法的 null 类型”这种情况。

**5. 反序列化不关闭调用方传入的 `InputStream`。** 用反射验证过：Jackson 3.2.2 的 `StreamReadFeature.AUTO_CLOSE_SOURCE.enabledByDefault()` 返回 `true`，不加配置时 `readValue(InputStream, ...)` 会在读取完成后关闭传入的流。这与本项目 `ExcelUtils`/`QrCodeUtils` 对输入流的既有约定（不关闭，由调用方用 try-with-resources 管理）矛盾，因此显式禁用该开关，只影响流式来源的关闭行为，不影响 `String`/`byte[]` 两种无需关闭资源的来源。

`ObjectMapper` 本身及其 `readValue`/`writeValueAsString`/`convertValue` 方法在 Jackson 3 中是线程安全的（不可变配置、每次调用内部创建独立的读写上下文），因此用一个 `private static final` 单例贯穿整个工具类的生命周期，不需要每次调用创建新实例，也不需要加锁。

### 反序列化的三种输入来源与 InputStream 生命周期

`fromJson` 需要同时支持 `String`（文本）、`byte[]`（已读取到内存的字节，如从数据库 BLOB 字段或消息队列取出）、`InputStream`（已打开的输入流，如 HTTP 响应体、文件流）三种常见来源，分别对应 `MAPPER.readValue(String, ...)`、`MAPPER.readValue(byte[], ...)`、`MAPPER.readValue(InputStream, ...)` 三组重载，均已由 Jackson 3 原生提供（`Class` 与 `TypeReference` 两种类型入口各一组），不需要 `JacksonUtils` 自己做格式转换。

`InputStream` 来源需要额外处理生命周期问题：用反射验证过，Jackson 3.2.2 的 `StreamReadFeature.AUTO_CLOSE_SOURCE.enabledByDefault()` 返回 `true`，也就是说 `ObjectMapper.readValue(InputStream, ...)` 默认行为是**读取完成后关闭调用方传入的流**。这与本项目 `ExcelUtils`/`QrCodeUtils` 对输入流“不关闭、由调用方用 try-with-resources 管理”的既有约定不一致，直接使用默认行为会造成同一个流被意外关闭、调用方后续复用该流失败。因此在 `buildMapper()` 中显式追加 `.disable(StreamReadFeature.AUTO_CLOSE_SOURCE)`，只影响流式来源（`InputStream`/`Reader`）的关闭行为，不影响 `String`/`byte[]` 两种来源（这两种来源本身不持有需要关闭的资源）。

`String`/`byte[]`/`InputStream` 三种来源的 `null` 处理方式一致：均视为“遗漏参数”而不是“空进空出”，抛出 `IllegalArgumentException`（原因见下方参数校验小节，与既有 `String` 版本的判断依据相同）。

### 公开 API

```java
public static String toJson(Object value);

public static <T> T fromJson(String json, Class<T> type);
public static <T> T fromJson(String json, TypeReference<T> type);
public static <T> T fromJson(byte[] json, Class<T> type);
public static <T> T fromJson(byte[] json, TypeReference<T> type);
public static <T> T fromJson(InputStream json, Class<T> type);
public static <T> T fromJson(InputStream json, TypeReference<T> type);

public static <T> T convert(Object value, Class<T> type);
public static <T> T convert(Object value, TypeReference<T> type);
```

`toJson`/`fromJson` 直接委托给 `MAPPER.writeValueAsString(value)` 和 `MAPPER.readValue(json, type)` 对应重载；`convert` 委托给 `MAPPER.convertValue(value, type)`。`convertValue` 不经过 JSON 文本序列化往返，底层通过 Jackson 内部的 `TokenBuffer` 完成结构转换，比“先 `toJson` 再 `fromJson`”更高效，这也是选择直接暴露 `convertValue` 而不是让调用方自己拼接两步调用的原因。`convert` 不新增 `byte[]`/`InputStream` 入口：转换的源头是内存中的对象而不是外部字节流，与反序列化的场景不同，维持 `proposal.md` 非目标小节的既有范围。

### 参数校验

- `toJson(null)`：直接返回 `null`，不调用 `MAPPER.writeValueAsString`，也不抛异常（见上一节第 4 点）。
- `fromJson`/`convert` 的 `type`（`Class<T>` 或 `TypeReference<T>`）为 `null`：抛出带中文描述的 `IllegalArgumentException`。类型参数没有“合法的 null”取值，缺少它方法就无法工作，因此不适用“空进空出”，与 `value`/`json` 的处理方式不同。
- `fromJson` 的 `json` 参数（`String`/`byte[]`/`InputStream` 三种来源均如此）为 `null`：抛出 `IllegalArgumentException`；为空字符串、空数组或空白字符串等其他“不是合法 JSON”的输入，不做提前拦截，直接交给 Jackson 处理——Jackson 对空输入本身就会抛出信息明确的 `JacksonException`（"No content to map due to end-of-input" 一类），额外包一层判断反而让错误信息变得含糊。三种来源都不采用“空进空出”：`null` 不是“没有 JSON 内容需要处理”的合法状态，更可能是调用方参数拼接或资源获取时的疏漏，与 `toJson`/`convert` 的主输入是“对象”而不是“待解析的外部数据”这一点不同，继续按参数异常处理。
- `convert(null, type)`：直接返回 `null`，不调用 `MAPPER.convertValue`，也不抛异常（见上一节第 4 点）。

### 异常语义

不定义专属异常类型（对比 `ExcelProcessingException`、`QrCodeException`）。原因：

- Jackson 3 的读写方法本身已经抛出非受检的 `tools.jackson.core.JacksonException`（及其更具体的子类型，如反序列化失败的 `DatabindException`、结构错误的 `StreamReadException`），信息已经足够结构化（携带路径、位置），调用方如果需要区分失败原因，直接 `catch` 这些类型即可，包一层自定义异常反而丢失子类型信息。
- 失败来源单一（只有 Jackson 一个底层库），不像 `ScriptExecutionException` 需要统一 Groovy/JS 两种引擎的异常，也不像 `QrCodeException` 需要合并 ZXing、AWT 字体、文件 I/O 三种不同来源；额外抽象在这里收益有限。
- 与本项目 `util.codec` 包（`AesUtils`、`RsaUtils` 等）的做法一致：那里同样没有为每个工具类定义专属异常，只在必须“把受检异常转成非受检”时才转换（如 `GeneralSecurityException` → `IllegalStateException`）；Jackson 的异常本来就是非受检的，不存在这个转换需求。

`convertValue` 在类型不兼容时抛出的是 `IllegalArgumentException`（Jackson 3 API 的既定行为，见 `ObjectMapper#convertValue` 签名），与本工具类参数校验用的 `IllegalArgumentException` 类型相同；两者的区分方式是看异常消息来源（参数校验的消息由 `JacksonUtils` 自己写，是固定的中文提示；转换失败的消息来自 Jackson，通常包含目标类型和字段路径），Javadoc 中会分别说明，不额外区分成不同的异常类型。

### 示例

```java
OrderDto order = new OrderDto("A-01", 12.5, LocalDateTime.of(2026, 9, 29, 10, 30, 0), null);
String json = JacksonUtils.toJson(order);
// {"code":"A-01","amount":12.5,"createdAt":"2026-09-29 10:30:00"}
// remark 字段为 null，未出现在输出中

OrderDto restored = JacksonUtils.fromJson(json, OrderDto.class);
List<OrderDto> orders = JacksonUtils.fromJson(arrayJson, new TypeReference<List<OrderDto>>() {});

byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
OrderDto fromBytes = JacksonUtils.fromJson(bytes, OrderDto.class);

try (InputStream input = Files.newInputStream(Path.of("order.json"))) {
    OrderDto fromStream = JacksonUtils.fromJson(input, OrderDto.class);   // 工具不关闭该输入流
}

Map<String, Object> asMap = JacksonUtils.convert(order, new TypeReference<Map<String, Object>>() {});
OrderDto fromMap = JacksonUtils.convert(asMap, OrderDto.class);

String nullJson = JacksonUtils.toJson(null);   // 返回 null，不抛异常
OrderDto nullConvert = JacksonUtils.convert(null, OrderDto.class);   // 返回 null，不抛异常
```

## 风险与取舍

- 单例 `ObjectMapper` 不可定制 → 换取工具类零配置、随处可用；确有定制需求的场景（如 RPC）继续使用专门的封装（`JacksonJsonSerializer`）或直接使用 Jackson API，不通过本工具类间接配置。
- 不包装 Jackson 异常 → 调用方需要了解 `tools.jackson.core.JacksonException` 这个第三方类型才能做精细捕获；换取异常信息不失真、不需要为单一失败来源的简单封装维护额外的异常层级。
- 日期时间使用固定文本格式而不是 Jackson 3 的默认 ISO-8601 → 换取与项目内 `JacksonJsonSerializer` 一致、不随 Jackson 版本演进而变化的显式契约；代价是失去时区偏移信息（`yyyy-MM-dd HH:mm:ss` 不携带时区），因此本工具的日期时间支持限定在 `LocalDate`/`LocalDateTime`/`LocalTime`/`java.util.Date` 四种不带时区语义的类型，不含 `ZonedDateTime`/`OffsetDateTime`/`Instant`（这些类型仍可使用，但走 Jackson 3 内置默认序列化方式，不做定制）。
- 反序列化容忍缺失字段和未知字段虽已是 Jackson 3 当前版本的默认行为，仍显式配置固定下来 → 多写三行配置，换取该行为不随第三方库升级而意外改变，并可在测试中直接断言。
- 序列化跳过 `null` 字段是全局默认策略，不提供按字段覆盖的入口 → 与“零配置、开箱即用”的定位一致；确有字段需要保留 `null` 的场景，可以在该字段类型上使用 Jackson 的 `@JsonInclude(Include.ALWAYS)` 注解覆盖全局默认值（Jackson 的属性级注解优先于 `ObjectMapper` 全局配置），不需要 `JacksonUtils` 另开专门入口。
- `toJson(null)`/`convert(null, type)` 返回 `null` 而不是抛异常 → 与本工具类此前草案“主要输入禁止 null”的统一风格不同，改为按明确需求的“空进空出”；`fromJson` 的 `json` 参数因为是文本而不是对象，其 `null` 语义不同（更接近“遗漏参数”而非“没有对象可序列化”），继续保留参数异常，不做同等放宽。
- `build.gradle` 顺带清理重复依赖声明 → 属于本变更范围内新增使用方触发的必要整理（避免新增一处三重声明），不涉及其他无关配置调整。
- 反序列化支持 `String`/`byte[]`/`InputStream` 三种来源，但 `convert` 和序列化方向（`toJson`）不新增等价的字节/流入口 → 序列化的产出物固定是 JSON 字符串，`convert` 的输入固定是内存中的对象，两者都不涉及外部字节流的读取，新增来源仅解决反序列化时数据来源多样的问题，不扩大到不需要的方向。
- 禁用 `StreamReadFeature.AUTO_CLOSE_SOURCE` 只对 `InputStream` 生效，`Reader` 等其他流式来源本次不提供入口 → 与本次明确的输入来源需求（`byte[]`/`InputStream`）范围一致，不顺带扩展未提出的来源类型；确有 `Reader` 需求时可再次走 OpenSpec 流程评估。
