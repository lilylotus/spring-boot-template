# Spec Delta

## MODIFIED Requirements

### Requirement: XLSX 与对象互转
系统 SHALL 按对象字段上的 `@ExcelColumn` 列定义将标题和列索引匹配到对象字段，并支持对象列表导入导出。
对象导入重载 MUST 接收非空 `Class<T> type`，且 MUST NOT 接收运行时 `columns`。对象导入的标题数量 MUST 与
注解列定义的列数量相同，每个标题 MUST 位于按列顺序规则确定的对应索引。
导入对象 MUST 支持字符串、基本类型及其包装类型、`BigDecimal`、`BigInteger`、布尔值、枚举、`LocalDate`、
`LocalDateTime` 和 `Date`；不支持的类型 MUST 在处理前或首次使用时明确拒绝。

#### Scenario: 将工作表解析为对象列表
- **WHEN** 调用方提供非空对象类型，标题数量、标题名称及其列索引均与该类型的注解列定义一致，且所有单元格均可转换为目标字段类型
- **THEN** 系统按数据行顺序返回对象，并按注解列定义处理空值、必填项和日期格式

#### Scenario: 导出对象数据
- **WHEN** 调用方提供对象数据及有效的列定义
- **THEN** 系统按列顺序写出标题和数据，并保持日期、布尔值、数字和文本的预期单元格类型

#### Scenario: 报告对象字段转换错误
- **WHEN** 单元格值无法转换为目标字段类型或必填值缺失
- **THEN** 系统停止处理并在异常中提供工作表、行、列、标题、字段和原始值摘要

#### Scenario: 必填字段拒绝纯空白文本
- **WHEN** 对象字段声明为必填且对应单元格为 `null`、空字符串或仅包含 Unicode 空白字符
- **THEN** 系统将该值判定为空并抛出包含单元格位置和字段信息的转换异常

#### Scenario: 可空字段接收纯空白文本
- **WHEN** 可空对象字段对应单元格仅包含 Unicode 空白字符
- **THEN** 系统将该字段值映射为 `null`，而不是把纯空白字符串写入对象

#### Scenario: 拒绝对象标题列顺序不一致
- **WHEN** XLSX 标题名称均存在但至少一个标题所在列与注解列定义的排序位置不同
- **THEN** 系统在处理数据行前抛出映射异常，并报告工作表、实际列号、期望标题和实际标题

### Requirement: XLSX 与顺序 Map 互转
系统 SHALL 将通用工作表的每行解析为顺序 Map，并支持调用方通过仅接收
`List<Map<String, Object>> columns` 的导入重载定义标题到业务字段键的映射。配置 Map 导入的每个结果 MUST 为
`LinkedHashMap<String, Object>`，键 MUST 使用对应配置项的 `field`，迭代顺序 MUST 与生效列顺序一致。系统 SHALL
继续支持既有的“标题作为键”的 `readMapList` 行为。常用 Map 导出入口 MUST 接收
`List<? extends Map<String, Object>>`，流式 Map 导出入口 MUST 接收惰性 `Iterable`，两者均 MUST 按稳定列顺序导出为
XLSX；未提供显式列定义时，仅当首行 Map 具有稳定迭代顺序才允许推断列。

#### Scenario: 将工作表解析为顺序 Map
- **WHEN** 工作表包含唯一且非空的标题
- **THEN** 系统为每个数据行产生一个顺序与标题一致的 Map，缺失单元格对应空值

#### Scenario: 从首行推断 Map 导出列
- **WHEN** 调用方未提供显式列定义且列表或流式数据源的首行 Map 具有稳定迭代顺序
- **THEN** 系统使用首行键的迭代顺序作为标题和列顺序，并要求后续行不包含未知键

#### Scenario: 使用 Map 列表导出工作表
- **WHEN** 调用方通过 `writeMaps` 提供 `List<? extends Map<String, Object>>` 数据及可选的显式有序列定义
- **THEN** 系统按稳定列顺序导出全部列表数据，且不要求每个数据行的具体实现类型为 `LinkedHashMap`

#### Scenario: 使用惰性 Map 数据源流式导出
- **WHEN** 调用方通过 `writeMapsStreaming` 提供惰性 `Iterable` 和有效列定义
- **THEN** 系统按需获取并写出每一行，不在工具类中把全部数据累计为列表

#### Scenario: 拒绝从无序 Map 推断导出列
- **WHEN** 调用方未提供显式列定义且首行 Map 不保证稳定迭代顺序
- **THEN** 系统在写出数据前抛出映射异常，并提示提供显式有序列定义

#### Scenario: 使用显式列定义导出空 Map 数据
- **WHEN** 调用方提供显式有序列定义但数据源为空
- **THEN** 系统仍生成包含定义标题的有效 XLSX 工作表

#### Scenario: 拒绝重复或空标题
- **WHEN** 导入工作表的有效标题中存在重复值或空值
- **THEN** 系统在产生数据行前抛出包含标题位置的异常

#### Scenario: 使用运行时列配置导入 Map
- **WHEN** 调用方使用仅接收有效运行时列配置的 `readObjectList` 重载，Excel 标题数量、名称和索引均与配置一致
- **THEN** 系统按行返回 `LinkedHashMap<String, Object>`，以各配置项的 `field` 作为键，以对应单元格文本或 `null` 作为值

#### Scenario: 按配置顺序生成 Map
- **WHEN** 运行时列配置通过 `order` 或配置项出现顺序确定生效列顺序
- **THEN** 返回 Map 的键迭代顺序与该生效列顺序一致，而不是使用 Excel 标题作为键

#### Scenario: 配置 Map 导入执行必填校验
- **WHEN** 某配置项的 `required` 为 `true`，对应单元格为 `null`、空字符串或裁剪后为空的文本
- **THEN** 系统拒绝该行并报告工作表、行、列、标题和 `field`

#### Scenario: 配置 Map 导入遵守文本裁剪选项
- **WHEN** 单元格包含前后空白且读取选项启用文本裁剪
- **THEN** 返回 Map 中保存裁剪后的文本；关闭裁剪时保存原始文本，但必填判空仍按裁剪后的内容判断

#### Scenario: 对象与配置入口相互独立
- **WHEN** 调用方使用对象重载或运行时列配置重载
- **THEN** 系统仅按所选重载的映射来源导入，不执行空对象类型分派，也不在对象映射与配置映射之间进行优先级判断

#### Scenario: 拒绝无效的运行时列配置
- **WHEN** 配置缺少 `field`、`field` 或 `value` 不是非空字符串、标题或字段键重复、显式 `order` 重复、
  `required` 或 `order` 类型不匹配，或包含未知配置键
- **THEN** 系统在读取工作簿前抛出映射异常，并报告出错配置项的位置、键名和具体原因

#### Scenario: 拒绝配置标题列顺序不一致
- **WHEN** XLSX 标题名称均存在但标题数量或至少一个标题索引与运行时列配置不一致
- **THEN** 系统在处理数据行前抛出映射异常，并报告工作表、实际列号、期望标题和实际标题

#### Scenario: 既有通用 Map 导入保持兼容
- **WHEN** 调用方使用既有 `readMapList` 方法导入包含唯一且非空标题的工作表
- **THEN** 系统仍返回以 Excel 标题为键的 `LinkedHashMap<String, String>`，行为不受运行时配置入口影响
