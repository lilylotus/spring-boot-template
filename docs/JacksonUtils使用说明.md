# JacksonUtils 使用说明

`JacksonUtils` 位于 `org.example.simple.util`，是面向通用业务场景的无状态 JSON 工具类：对象与 JSON 字符串互转，以及对象到对象的结构转换（如 `Map`↔POJO），均支持按 `Class` 或带泛型信息的 `TypeReference` 两种方式声明目标类型。

- 内部使用单个共享、不可变的默认 `ObjectMapper`，不提供自定义或注入配置的入口。
- 不与 RPC 专用的 `JacksonJsonSerializer` 共享映射器实例，两者配置目的不同，互不影响。
- 不定义专属异常类型：Jackson 读写失败产生的异常直接透传，不做二次包装。

## API 速查

| 场景 | 方法 | 说明 |
| --- | --- | --- |
| 序列化 | `toJson(Object value)` | 对象转 JSON 字符串；`value` 为 `null` 时返回 `null` |
| 反序列化（文本，简单类型） | `fromJson(String json, Class<T> type)` | JSON 文本转指定 `Class` 的对象 |
| 反序列化（文本，泛型类型） | `fromJson(String json, TypeReference<T> type)` | JSON 文本转带泛型信息的对象，如 `List<Dto>` |
| 反序列化（字节数组，简单类型） | `fromJson(byte[] json, Class<T> type)` | JSON 字节数组转指定 `Class` 的对象 |
| 反序列化（字节数组，泛型类型） | `fromJson(byte[] json, TypeReference<T> type)` | 同上，目标类型带泛型信息 |
| 反序列化（输入流，简单类型） | `fromJson(InputStream json, Class<T> type)` | JSON 输入流转指定 `Class` 的对象，不关闭该流 |
| 反序列化（输入流，泛型类型） | `fromJson(InputStream json, TypeReference<T> type)` | 同上，目标类型带泛型信息 |
| 对象转换（简单类型） | `convert(Object value, Class<T> type)` | 对象直接转另一对象，不经过 JSON 文本 |
| 对象转换（泛型类型） | `convert(Object value, TypeReference<T> type)` | 同上，目标类型带泛型信息 |

`fromJson` 支持 `String`/`byte[]`/`InputStream` 三种输入来源，分别覆盖文本、已读取到内存的字节、已打开的输入流三类常见场景；三种来源均支持 `Class`/`TypeReference` 两种目标类型声明方式，对同一 JSON 内容产生结果相等的对象。`toJson`/`convert` 不提供等价的字节/流入口：`toJson` 的产出固定是 JSON 字符串，`convert` 的输入固定是内存中的对象，均不涉及外部字节流读取。

## 基本用法

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

String nullJson = JacksonUtils.toJson(null);                        // 返回 null，不抛异常
OrderDto nullConvert = JacksonUtils.convert(null, OrderDto.class);  // 返回 null，不抛异常
```

## 默认配置

- **日期时间使用固定文本格式**，不依赖 Jackson 默认的 ISO-8601 输出：

  | 类型 | 格式 |
  | --- | --- |
  | `LocalDateTime` / `java.util.Date` | `yyyy-MM-dd HH:mm:ss` |
  | `LocalDate` | `yyyy-MM-dd` |
  | `LocalTime` | `HH:mm:ss` |

  序列化和反序列化使用相同格式，按该格式序列化后的文本能反序列化还原为数值相等的原始对象。`ZonedDateTime`/`OffsetDateTime`/`Instant` 等带时区语义的类型不做定制，仍可使用，走 Jackson 内置默认方式。
- **反序列化容忍未知字段和缺失字段**：JSON 含目标类型未声明的多余字段时忽略；JSON 缺少目标类型声明的部分字段（包括依赖全部构造参数的 `record`）时，缺失字段取该类型的默认值（引用类型为 `null`，基本类型为对应零值），不因此报错。
- **序列化跳过值为 `null` 的属性**，不写入输出 JSON。确有字段需要保留 `null` 的场景，可以在该字段类型上使用 Jackson 的 `@JsonInclude(Include.ALWAYS)` 注解覆盖全局默认值。
- 映射器本身及其读写方法线程安全，所有方法均可被多线程并发调用，无需额外同步。

## 空值与参数校验

| 方法 | 输入为 `null` 时的行为 |
| --- | --- |
| `toJson(value)` | `value` 为 `null` 时直接返回 `null`，不抛异常 |
| `convert(value, type)` | `value` 为 `null` 时直接返回 `null`，不抛异常；`type` 为 `null` 时抛 `IllegalArgumentException` |
| `fromJson(json, type)` | `json`（`String`/`byte[]`/`InputStream` 任一来源）或 `type` 为 `null` 时均抛 `IllegalArgumentException` |

`fromJson` 的 JSON 输入参数不采用“空进空出”：一个 `null` 值不是“没有 JSON 内容需要处理”的合法状态，更可能是调用方参数拼接或资源获取时的疏漏，因此按参数异常处理，与 `toJson`/`convert` 的主输入（对象）不同。目标类型参数（`Class`/`TypeReference`）在任何入口都没有“合法的 null”，为 `null` 时一律拒绝。

`fromJson(InputStream json, ...)` **不关闭**调用方传入的输入流，由调用方用 try-with-resources 管理生命周期，与 `ExcelUtils`/`QrCodeUtils` 对输入流的既有约定一致。

> 调用 `fromJson` 时避免直接传入 `null` 字面量（如 `JacksonUtils.fromJson(null, OrderDto.class)`）：`String`/`byte[]`/`InputStream` 三个重载对编译器而言同样匹配，会导致重载调用歧义、编译失败。需要传 `null` 触发参数校验时，显式声明变量类型（如 `String json = null;`）或做类型强转。

## 异常处理

`JacksonUtils` 不定义专属异常类型，失败来源分两类，来源不同、可区分：

- **参数校验异常**：由 `JacksonUtils` 自身抛出的 `IllegalArgumentException`，消息为固定的中文提示，触发条件见上一节。
- **处理失败异常**：底层 Jackson 读写失败时抛出的非受检异常（`tools.jackson.core.JacksonException` 及其子类型，如反序列化失败的 `DatabindException`），原样透传，不做包装，调用方可按需捕获具体子类型获取路径、位置等诊断信息。

```java
try {
    OrderDto restored = JacksonUtils.fromJson(json, OrderDto.class);
} catch (IllegalArgumentException exception) {
    log.warn("参数非法：{}", exception.getMessage());
} catch (JacksonException exception) {
    log.warn("JSON 处理失败：{}", exception.getMessage());
}
```

`convert` 在类型不兼容时抛出的是 Jackson `convertValue` 既定行为产生的 `IllegalArgumentException`，与参数校验异常的类型相同，区分方式看消息来源：参数校验的消息固定、由 `JacksonUtils` 自己写；转换失败的消息来自 Jackson，通常包含目标类型和字段路径。

## 非目标

- 不提供可自定义/注入 `ObjectMapper` 的构造方式；需要定制配置时直接使用 Jackson API，或参考 `JacksonJsonSerializer`（RPC 专用封装）的做法。
- 不提供 JSON 树（`JsonNode`）层面的操作入口；`JacksonJsonSerializer` 已有 `toTree`/`fromTree`，需要该能力时使用它。
- 不提供 XML/YAML 等其他数据格式的读写，也不提供美化输出（pretty print）、流式解析（`JsonParser`/`JsonGenerator`）等高级入口。
