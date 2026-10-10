# Spec Delta

## ADDED Requirements

### Requirement: 地区码表内置于源码，不依赖外部资源
随机用户信息生成能力 SHALL 把官方地区码表作为源码内置数据随工具类一起分发，加载地区码表的过程 MUST NOT 读取 classpath 资源、文件系统或网络；工具类在缺少任何伴生资源文件的环境中 SHALL 可正常加载并生成数据。

#### Scenario: 不存在伴生资源文件时仍可工作
- **WHEN** 运行环境的 classpath 中不存在 `area-codes.csv`（或任何其他伴生数据文件）
- **THEN** 工具类正常完成类加载，`generateRegions`、`generateIdCardNos`、`generateUsers` 均正常返回结果，不抛出资源缺失相关异常

#### Scenario: 内置地区码表与官方快照一致
- **WHEN** 把内置地区码表与仓库中保留的官方地区码表快照 `area-codes.csv` 逐条比对
- **THEN** 两者条数相同，且同一地区码对应的地区名称完全相同

## MODIFIED Requirements

### Requirement: 批量生成唯一的随机姓名
系统 SHALL 提供 `RandomUserInfoUtils.generateNames(int count)`，返回 `count` 个两两不重复的随机姓名。

每个姓名 SHALL 满足以下约束：

- 长度为 2 到 4 个字符（含两端）；
- 取自以下三类姓名之一：汉族单姓姓名、汉族复姓姓名、少数民族姓名；
- 仅由汉字组成，不含空格、分隔符、数字或拉丁字母。

在足够大的样本量下，三类姓名 SHALL 都有可能被生成，即生成器不得退化为只产出其中某一类。

#### Scenario: 返回数量与唯一性
- **WHEN** 调用 `generateNames(count)`，`count` 为正整数
- **THEN** 返回列表大小等于 `count`，且任意两个元素不相同

#### Scenario: 姓名长度恒在 2 到 4 个字符之间
- **WHEN** 调用 `generateNames(count)` 或 `generateUsers(count)`，`count` 为正整数
- **THEN** 返回结果中每个姓名的字符长度都大于等于 2 且小于等于 4

#### Scenario: 姓名只包含汉字
- **WHEN** 调用 `generateNames(count)` 或 `generateUsers(count)`，`count` 为正整数
- **THEN** 返回结果中每个姓名都完全由汉字构成，不含空格、`·`、数字或拉丁字母

#### Scenario: 覆盖汉族与少数民族姓名
- **WHEN** 调用 `generateNames(count)`，`count` 足够大（例如 2000）
- **THEN** 返回结果中既出现至少一个少数民族姓名，也出现至少一个汉族姓名

#### Scenario: count 非正数
- **WHEN** 调用 `generateNames(count)`，`count` 为 0 或负数
- **THEN** 方法抛出 `IllegalArgumentException`
