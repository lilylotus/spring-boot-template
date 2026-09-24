# Proposal

## Why

Excel 导入目前分为对象导入和通用 Map 导入：对象导入必须提供 `Class<T>` 并依赖字段上的
`@ExcelColumn`，通用 Map 导入则固定使用 Excel 标题作为键。调用方在没有 DTO 类型，或希望由外部配置决定
标题与业务字段键时，无法把工作表按运行时列配置解析为 `Map<String, Object>`。

导入入口需要通过参数类型明确区分两种模式：对象重载接收 `Class<T>`，运行时 Map 重载仅接收
`List<Map<String, Object>> columns`，由配置描述标题与返回 Map 键的对应关系。

## What Changes

- 为 `readObjectList` 和流式 `readObjects` 增加仅接收 `columns` 的 Map 导入重载；对象导入继续使用仅接收
  `Class<T> type` 的既有重载。公共 API 不允许同时传入两者，内部使用一个同时兼容 `type` 与 `columns` 的统一方法
  完成读取和分派，减少列表容量检查、SAX 调用及回调包装的重复实现。
- 运行时列配置使用 `field` 作为返回 Map 的键，使用 `value` 作为 Excel 标题；按 `order` 确定列顺序，并支持
  `required` 必填校验。Map 模式没有目标 Java 字段类型，因此不执行对象字段反射或类型转换。
- Map 模式返回保持配置顺序的 `LinkedHashMap<String, Object>`，单元格值采用读取器提供的文本值；空白文本沿用
  读取选项的裁剪规则，空单元格映射为 `null`。
- 运行时配置是 Map 导入重载的完整列清单，不覆盖、不合并对象类上的 `@ExcelColumn`；两个重载在编译期即确定
  映射来源。内部统一方法收到 `type` 时优先使用对象映射，否则使用 `columns`；该规则不会暴露为要求调用方同时
  传参的公共 API。
- 保留现有 `readObjectList`、`readObjects`、`readMapList` 与导出 API 的兼容行为；本变更不增加对象导出的运行时
  注解覆盖能力。Map 导出的常用入口改为接收 `List<? extends Map<String, Object>>`，并将原有惰性 `Iterable`
  能力迁移到名称明确的 `writeMapsStreaming`，避免大数据场景失去流式出口。
- 补充两个重载独立工作、配置 Map 导入、严格标题索引、必填校验及配置非法的测试。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `xlsx-processing`：通过独立重载支持对象类型映射与运行时 Map 列配置，并明确 Map 键语义。

## Impact

- 影响 `org.example.simple.util.excel.ExcelUtils` 的公共导入 API（增加仅接收 `columns` 的列表与流式重载）。
- 影响 `ExcelUtils.writeMaps` 的公共数据参数：由 `Iterable` 调整为 `List<? extends Map<String, Object>>`；新增
  `writeMapsStreaming` 保留惰性 Map 数据源能力。
- 新增或调整运行时配置解析组件，用于校验 `field`、`value`、`order`、`required` 等配置并生成 Map 行。
- 对象导入继续使用 `ExcelObjectMapper` 的注解缓存，不允许运行时配置改变对象字段映射。
- 需要撤销当前工作区中与新约束冲突的“运行时配置覆盖对象注解”和对象配置导出实现及相应测试。
- 不改变 XLSX-only 限制、读取资源限制、底层流式写出模型、现有对象导入和通用 Map 导入行为，不新增第三方依赖。
