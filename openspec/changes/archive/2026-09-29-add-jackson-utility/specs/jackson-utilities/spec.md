# Spec Delta

## Purpose

为 Java 应用提供统一、零配置的 JSON 工具：对象与 JSON 字符串互转，以及对象到对象的结构转换，均支持按简单类型或带泛型信息的类型接收结果，明确日期时间格式、字段容错、`null` 处理策略、参数校验与异常语义。

## ADDED Requirements

### Requirement: 对象序列化为 JSON 字符串
系统 SHALL 提供静态入口，把对象序列化为 JSON 字符串。系统 MUST 在入参为 `null` 时直接返回 `null`，不抛出异常。序列化 MUST 跳过值为 `null` 的属性，不将其写入输出 JSON。

#### Scenario: 序列化任意对象
- **WHEN** 调用方传入非空的 POJO、集合或 Map
- **THEN** 系统返回该对象对应的合法 JSON 字符串

#### Scenario: 序列化时跳过 null 字段
- **WHEN** 待序列化对象的部分属性值为 `null`，其余属性有值
- **THEN** 输出的 JSON 字符串中不包含值为 `null` 的属性，其余属性正常输出

#### Scenario: 序列化空对象直接返回 null
- **WHEN** 调用方传入 `null`
- **THEN** 系统直接返回 `null`，不抛出异常，也不调用底层序列化逻辑

### Requirement: JSON 反序列化为对象
系统 SHALL 提供静态入口，把 JSON 反序列化为目标类型的对象，且 MUST 同时支持三种输入来源：`String` 文本、`byte[]` 字节数组、`InputStream` 输入流；每种来源 MUST 同时支持两种类型声明方式：不带泛型参数的 `Class`，以及携带完整泛型信息的类型引用。相同 JSON 内容通过不同输入来源、不同类型声明方式反序列化 MUST 产生结果相等的对象。系统 MUST 在 JSON 输入或目标类型参数为 `null` 时拒绝执行。系统 MUST NOT 关闭调用方传入的 `InputStream`。

#### Scenario: 按 Class 反序列化
- **WHEN** 调用方传入 JSON 字符串和一个不带泛型参数的目标类型
- **THEN** 系统返回该类型的对象，字段值与 JSON 内容一致

#### Scenario: 按带泛型信息的类型反序列化
- **WHEN** 调用方传入 JSON 数组字符串和描述该数组元素类型的类型引用（如列表、Map）
- **THEN** 系统返回对应的参数化类型对象，元素类型正确还原，不发生泛型擦除导致的类型丢失

#### Scenario: 从字节数组反序列化
- **WHEN** 调用方传入 JSON 内容对应的 `byte[]` 和目标类型（`Class` 或类型引用）
- **THEN** 系统返回与等价 `String` 输入反序列化结果相等的对象

#### Scenario: 从输入流反序列化且不关闭该流
- **WHEN** 调用方传入一个已打开、包含 JSON 内容的 `InputStream` 和目标类型（`Class` 或类型引用）
- **THEN** 系统返回与等价 `String` 输入反序列化结果相等的对象，且读取完成后该输入流仍处于打开状态，可被调用方继续操作或显式关闭

#### Scenario: 拒绝空输入
- **WHEN** `String`/`byte[]`/`InputStream` 任一形式的 JSON 输入参数为 `null`，或目标类型参数为 `null`
- **THEN** 系统抛出带中文描述的参数异常，不返回任何结果

#### Scenario: 反序列化容忍未知字段
- **WHEN** JSON 内容包含目标类型未声明的多余字段
- **THEN** 系统正常完成反序列化，忽略多余字段，不因此失败

#### Scenario: 反序列化容忍缺失字段
- **WHEN** JSON 内容缺少目标类型声明的部分属性，包括依赖全部构造参数的记录类型
- **THEN** 系统正常完成反序列化，缺失属性取该类型的默认值（引用类型为 `null`，基本类型为对应零值），不因此失败

### Requirement: 对象到对象的转换
系统 SHALL 提供静态入口，把一个对象直接转换为另一个目标类型的对象，且 MUST 同时支持不带泛型参数的 `Class` 和携带完整泛型信息的类型引用两种目标类型声明方式，转换过程 MUST NOT 要求调用方自行先序列化为 JSON 文本再反序列化。系统 MUST 在目标类型参数为 `null` 时拒绝执行；源对象为 `null` 时系统 MUST 直接返回 `null`，不抛出异常。

#### Scenario: 按 Class 转换对象结构
- **WHEN** 调用方提供一个对象和另一个结构兼容的目标类型（例如 `Map` 转 POJO，或反过来）
- **THEN** 系统返回目标类型的对象，字段值与源对象一致

#### Scenario: 按带泛型信息的类型转换
- **WHEN** 调用方提供一个对象和描述参数化目标类型的类型引用
- **THEN** 系统返回对应的参数化类型对象，泛型信息被正确保留

#### Scenario: 转换空源对象直接返回 null
- **WHEN** 源对象为 `null`
- **THEN** 系统直接返回 `null`，不抛出异常，也不调用底层转换逻辑

#### Scenario: 拒绝空目标类型
- **WHEN** 目标类型参数为 `null`
- **THEN** 系统抛出带中文描述的参数异常，不返回任何结果

### Requirement: 日期时间字段的固定文本格式
系统 SHALL 为 `LocalDateTime`、`LocalDate`、`LocalTime` 和 `java.util.Date` 类型的字段使用固定文本格式序列化和反序列化，且 MUST 与项目既有 RPC 组件的日期时间文本格式约定一致：`LocalDateTime`/`Date` 使用 `yyyy-MM-dd HH:mm:ss`，`LocalDate` 使用 `yyyy-MM-dd`，`LocalTime` 使用 `HH:mm:ss`。序列化和反序列化 MUST 使用相同格式，按该格式序列化后的文本 MUST 能反序列化还原为数值相等的原始日期时间对象。

#### Scenario: 序列化日期时间字段为固定格式文本
- **WHEN** 待序列化对象包含 `LocalDateTime`、`LocalDate`、`LocalTime` 或 `java.util.Date` 类型字段
- **THEN** 系统按各自类型对应的固定文本格式输出字段值，而不是数值时间戳或默认 ISO-8601 格式

#### Scenario: 日期时间字段往返一致
- **WHEN** 调用方把包含日期时间字段的对象序列化为 JSON 字符串，再反序列化回同一类型
- **THEN** 还原后的日期时间字段与原始值相等

### Requirement: 默认配置与异常语义
系统 SHALL 使用单个共享、线程安全的默认配置实例完成全部序列化、反序列化和转换操作，且 MUST NOT 提供调用方注入或替换该配置的入口。系统 MUST NOT 为读写失败定义专属异常类型；底层 JSON 处理失败时，系统 MUST 让处理失败产生的非受检异常直接向调用方传播，且 MUST NOT 丢失其原始类型信息。反序列化和转换入口的目标类型参数为 `null`，以及反序列化入口的 JSON 文本参数为 `null` 时，系统 MUST 抛出带中文描述的 `IllegalArgumentException`；这类参数校验异常与处理失败产生的异常 MUST 在来源上可区分（不得混用同一固定消息掩盖两类不同失败）。

#### Scenario: 拒绝空的目标类型和 JSON 输入
- **WHEN** 调用方对反序列化或转换入口传入 `null` 的目标类型，或对反序列化入口传入 `null` 的 JSON 输入（`String`/`byte[]`/`InputStream` 任一形式）
- **THEN** 系统抛出带中文描述的参数异常，不返回任何结果

#### Scenario: 处理失败保留原始异常类型
- **WHEN** 调用方传入的 JSON 字符串不是合法的 JSON 结构
- **THEN** 系统抛出的异常保留 JSON 处理库产生的具体类型和诊断信息，而不是被替换为本工具定义的通用异常

#### Scenario: 参数异常与处理异常来源不同
- **WHEN** 调用方分别触发一次入参为 `null` 的调用和一次输入内容不合法但入参非 `null` 的调用
- **THEN** 前者抛出本工具校验产生的参数异常，后者抛出底层 JSON 处理库产生的异常，调用方能够区分两者来源

#### Scenario: 共享配置线程安全
- **WHEN** 多个线程并发调用序列化、反序列化和转换入口
- **THEN** 每次调用相互独立返回正确结果，不出现结果交叉污染或异常的竞态失败
