# Tasks

## 1. 清理旧设计实现

- [x] 1.1 移除 `ExcelObjectMapper` 中接收运行时配置覆盖对象注解的构建路径，保证对象映射只来自
  `ExcelObjectMapper.of(type)` 和 `@ExcelColumn`。
- [x] 1.2 移除对象导出接收 `List<Map<String, Object>> columns` 的重载及对应写出路径，恢复本变更范围外的
  Map 导出行为。
- [x] 1.3 更新 `ExcelColumn`、`ExcelUtils` 和配置常量 Javadoc，删除“运行时配置覆盖对象注解”的旧说明。

## 2. 运行时 Map 列配置

- [x] 2.1 定义配置 Map 导入使用的键常量与中文 Javadoc：`field` 为必填返回键，`value` 为标题，`order` 为顺序，
  `required` 为必填标记；不支持的未知键明确拒绝。
- [x] 2.2 实现独立于反射字段的不可变列绑定解析，校验配置非空、键和值类型、标题与 field 非空且唯一、显式
  `order` 不重复，并在异常中包含配置项下标和键名。
- [x] 2.3 实现稳定排序：先按显式 `order` 升序，再按配置项出现顺序；复用严格标题数量、名称和索引校验。
- [x] 2.4 实现 Map 行转换，以配置 `field` 为 `LinkedHashMap<String, Object>` 的键，按读取选项处理文本裁剪，
  空单元格映射为 `null`，`required` 对裁剪后文本判空。

## 3. 导入 API 重载

- [x] 3.1 移除同时接收 `Class<T> type` 与 `columns` 的列表和流式导入入口，以及仅为动态分派服务的泛型强制转换。
- [x] 3.2 增加仅接收 `columns` 的 `readObjectList` 列表重载及读取选项版本，返回
  `List<LinkedHashMap<String, Object>>`。
- [x] 3.3 增加仅接收 `columns` 的 `readObjects` 流式重载及读取选项版本，移除功能重复的
  `readConfiguredMapList`/`readConfiguredMaps` 公共别名，并更新中文 Javadoc 和示例。
- [x] 3.4 新增同时接收可空 `type` 与 `columns` 的私有统一列表及流式方法；公共对象重载仅传 `type`，公共 Map 重载
  仅传 `columns`，内部按 `type` 优先规则分派，并确保列表只累计一次、不复制整批结果。

## 4. 测试与验证

- [x] 4.1 更新 API 测试，分别通过 `Class<T>` 重载导入对象、通过 `columns` 重载导入 Map，确认统一底层分派结果正确。
- [x] 4.2 更新配置 Map 列表与流式导入测试，断言返回键使用 `field`、顺序符合 `order`/配置顺序、值和空值符合约定。
- [x] 4.3 保留严格标题数量及索引、必填纯空白、文本裁剪开关和各类非法配置测试，并删除空 `type` 动态分派测试。
- [x] 4.4 运行 `gradlew.bat test` 与 `gradlew.bat javadoc`，确认全部测试通过且修改范围内无新增告警。
- [x] 4.5 运行 `openspec validate add-excel-runtime-column-config --strict`，确认 OpenSpec 严格校验通过。

## 5. Map 导出 List API

- [x] 5.1 将 `ExcelUtils.writeMaps` 数据参数调整为 `List<? extends Map<String, Object>>`，更新中文 Javadoc 和类级示例，
  并确保实现不复制整份列表。
- [x] 5.2 新增 `writeMapsStreaming` 接收 `Iterable<? extends Map<String, ?>>`，把原有大数据 Map 导出和内存探针迁移到
  该入口，保持惰性消费、工作表拆分和资源限制行为。
- [x] 5.3 将 `XlsxStreamWriter.writeMaps` 统一为 `Map` 数据行；提供显式列定义时支持无序 Map，未提供时仅允许从
  `SequencedMap` 首行推断列，否则抛出 `MAPPING` 异常。
- [x] 5.4 使用预构建键集合校验未知键，并补充 List Map 导出、HashMap 配显式列定义、HashMap 无列定义被拒绝、
  空列表配显式列定义及流式惰性消费测试。
