# Design

## Context

现有 `ExcelUtils` 提供两类导入路径：对象路径通过 `Class<T>` 取得 `ExcelObjectMapper`，按 `@ExcelColumn` 完成标题
校验、对象实例化与字段转换；通用 Map 路径直接按 Excel 标题生成 `LinkedHashMap<String, String>`。运行时配置 Map
导入应作为 `readObjectList`/`readObjects` 的独立公共重载，只接收 `columns`。为避免两套重载复制列表累计、容量检查、
SAX 读取和回调包装逻辑，内部方法同时接收可空 `type` 与 `columns` 并集中分派。

运行时 Map 配置没有 Java 字段类型，因此不能复用依赖反射字段的对象映射器。它需要一份轻量列绑定，只描述
`field`、标题、顺序和必填规则，然后复用 SAX 读取器、资源限制、标题索引校验和行处理机制。

## Goals / Non-Goals

**Goals:**

- 对象导入重载仅接收 `Class<T>`，配置 Map 导入重载仅接收非空 `columns`。
- 配置 Map 重载返回以配置 `field` 为键的顺序 Map。
- 列表与流式公共 API 使用一致的重载边界，内部通过统一方法选择对象或配置 Map 映射。
- 配置 Map 路径保持严格标题数量和索引校验、必填校验、默认文本裁剪与流式处理能力。

**Non-Goals:**

- 不允许运行时配置覆盖或合并对象类型的 `@ExcelColumn`。
- 不通过运行时配置导入无注解 DTO；无 `type` 时结果是 Map，不实例化对象。
- 不为 Map 值增加目标 Java 类型声明或日期格式转换；值采用单元格文本或 `null`。
- 不新增对象导出的运行时列覆盖能力。
- 不改变既有 `readMapList` 和对象导出契约。
- 不改变 XLSX-only、资源限制和流式内存模型。

## Decisions

### 1. 通过参数类型区分对象与配置 Map 导入

列表入口采用两组不歧义的重载：

1. `readObjectList(input, Class<T> type)` 及其读取选项版本继续返回 `List<T>`。
2. `readObjectList(input, List<Map<String, Object>> columns)` 及其读取选项版本返回
   `List<LinkedHashMap<String, Object>>`。

流式入口采用相同边界：对象版本接收 `type`，配置 Map 版本只接收 `columns` 并把
`LinkedHashMap<String, Object>` 交给处理器。不保留同时接收 `type` 与 `columns` 的方法，也不保留功能重复的
`readConfiguredMapList`/`readConfiguredMaps` 公共别名。

公共重载在编译期确定返回类型。两个公共列表方法分别把 `(type, null)` 或 `(null, columns)` 传给私有统一方法；流式方法
使用相同方式委托给私有统一读取方法。内部规则为：`type != null` 时使用 `ExcelObjectMapper`，否则要求非空 `columns`
并使用 `ExcelConfiguredMapMapper`。即使内部未来同时收到两者，也固定以 `type` 为优先，防止出现两个生效映射来源。

私有统一方法使用泛型承接两个公共重载的静态返回类型。Map 分支所需的受控类型转换只允许存在于这个内部边界，并通过
分支条件保证实际值必为 `LinkedHashMap<String, Object>`；不得把 `type + columns` 方法公开，也不得为了转换额外复制整批
列表，以保持列表接口原有的内存占用。

### 2. 配置 Map 使用独立的不可变列绑定

配置解析后生成不包含反射 `Field` 的内部不可变绑定，至少保存：

| 键 | 类型 | 必填 | 默认 | 含义 |
| --- | --- | --- | --- | --- |
| `field` | `String` | 是 | 无 | 返回 Map 的业务键 |
| `value` | `String` | 否 | `field` | Excel 列标题 |
| `order` | `Integer` | 否 | 配置项出现顺序 | 列顺序，数值越小越靠前 |
| `required` | `Boolean` | 否 | `false` | 单元格是否必须有有效文本 |

`dateFormat` 属于对象字段类型转换语义，Map 模式不执行类型转换，因此不纳入本次配置；若出现则按未知键拒绝，避免调用方
误以为它会生效。键名常量集中在 `ExcelColumnConfigKeys` 并以中文 Javadoc 说明适用范围。

配置是完整列清单。标题和 `field` 均必须非空且唯一；`order` 显式值不得重复；未知键或值类型不匹配立即失败，异常包含
配置项下标、键名和原因。

### 3. 列排序和标题校验保持确定性

有显式 `order` 时先按其升序排列；没有显式 `order` 或排序值相同时按配置项出现顺序稳定排序。为避免部分显式顺序产生
难以解释的结果，显式顺序重复直接拒绝。

读取标题后，先比较标题数量，再按生效绑定逐列比较 `value`。数量、名称或索引不一致均在首个数据行之前失败，错误上下文
与对象导入保持一致。

### 4. Map 键和值语义

每个结果使用 `LinkedHashMap<String, Object>`，按生效列顺序插入。键固定为配置项 `field`，而不是 Excel 标题 `value`。
值使用 SAX 单元格模型提供的文本值：缺失或空单元格为 `null`；非空文本按 `ExcelReadOptions` 的默认裁剪规则处理。
由于没有目标类型，数字、布尔值和日期也不做 Java 字段类型转换，保持与通用文本读取一致的可预测语义。

必填判断对文本执行 Unicode 空白裁剪后再判空，与对象导入一致；即使关闭返回文本裁剪，纯空白文本仍不能通过
`required = true`。

### 5. 保持流式内存模型

列表便捷入口仍会把所有结果保存在内存中；生产环境大数据导入使用流式处理器入口，配置 Map 行在回调返回后不由工具类
持有。运行时列绑定只与列数成正比，不缓存调用方配置，也不改变既有 ZIP 防护、行列数和字符数限制。

### 6. 统一公共重载的底层实现

保留两组已经确定的公共重载，但将它们的重复实现下沉：

- 默认选项重载只负责补充 `ExcelReadOptions.defaults()`。
- 带选项的对象重载将 `type` 和空 `columns` 传给私有统一方法。
- 带选项的 Map 重载将空 `type` 和 `columns` 传给同一私有方法。
- 私有列表方法负责一次性创建结果列表、执行容量检查，并调用私有统一流式方法。
- 私有流式方法按 `type` 优先规则选择 `ExcelObjectMapper` 或 `ExcelConfiguredMapMapper`，共用 SAX 读取骨架。

### 7. `writeMaps` 使用 List，惰性数据源迁移到明确的流式入口

常用 Map 导出方法调整为：

```java
writeMaps(OutputStream output, List<? extends Map<String, Object>> data)
writeMaps(
    OutputStream output,
    List<? extends Map<String, Object>> data,
    LinkedHashMap<String, String> columns,
    ExcelWriteOptions options)
```

使用 `? extends Map` 允许 `List<LinkedHashMap<String, Object>>` 直接调用，同时每个 Map 的值仍以 `Object` 表示。
公共方法不复制列表，直接把它作为 `Iterable` 交给底层流式写出器，因此工具类不会再创建一份全量数据副本。

为保留大数据生产能力，新增 `writeMapsStreaming`，接收 `Iterable<? extends Map<String, ?>>` 及可选的显式有序列定义。
旧的 Map `Iterable` 调用迁移到该方法；对象导出的 `Iterable` API 不变。

底层 `XlsxStreamWriter.writeMaps` 统一接收 `Iterable<? extends Map<String, ?>>`。提供显式列定义时允许普通 `HashMap`；
未提供列定义时，首行必须实现 `SequencedMap`，否则明确拒绝，避免把无序 Map 的偶然迭代顺序固化为 Excel 列顺序。
未知键检查继续使用预构建 `Set`，避免逐行逐键线性扫描。

## Risks / Trade-offs

- [`readObjectList` 同名重载较多] → `Class` 与 `List` 参数类型明确不同，读取选项的位置保持与既有方法一致，并通过编译测试固定调用方式。
- [内部 Map 分支需要受控泛型转换] → 转换限制在私有统一方法且由分支创建的映射器保证实际类型，不向调用方暴露；
  测试分别覆盖两个公共重载，避免分派错误。
- [Map 模式无法按 Java 类型转换日期或数字] → 本次配置不含目标类型，保持文本语义；未来如需强类型 Map，应单独设计类型键。
- [标题与 field 分离后调用方可能混淆] → 常量 Javadoc 和异常同时报告标题及 field，示例明确 `value` 是标题、`field` 是键。
- [`writeMaps` 从 `Iterable` 改为 `List` 会影响现有惰性调用] → 提供语义明确的 `writeMapsStreaming` 作为迁移目标，
  并保留相同底层写出、工作表拆分和资源限制行为。
- [普通 Map 无法安全推断列顺序] → 只有 `SequencedMap` 可自动推断；其他实现必须提供显式有序列定义。

## Migration Plan

1. 既有对象导入、通用 Map 导入和所有导出调用无需修改。
2. 没有 DTO 类型的动态模板导入直接调用仅接收 `columns` 的 `readObjectList` 或 `readObjects` 重载。
3. 已使用未发布的 `type + columns` 或 `readConfiguredMapList` 调用需要改为新的 `columns` 重载。
4. 原 `writeMaps` 的惰性 `Iterable` 调用改用 `writeMapsStreaming`；已有 List 数据直接调用新的 `writeMaps`。
5. 回滚方式：移除新增的配置 Map 重载和列绑定解析器，并恢复 Map 导出参数；不涉及数据迁移或持久化格式变化。
