# random-user-info-generation Specification

## Purpose
为 Java 代码提供批量生成模拟用户数据的统一静态工具：唯一随机姓名、唯一且合法可校验的身份证号、唯一且格式合法的手机号、唯一的官方行政区划地址，以及姓名/手机号/身份证号分别唯一、地区与身份证号一致的组合用户数据。

## Requirements

### Requirement: 批量生成唯一的随机姓名
系统 SHALL 提供 `RandomUserInfoUtils.generateNames(int count)`，返回 `count` 个两两不重复的随机姓名。

#### Scenario: 返回数量与唯一性
- **WHEN** 调用 `generateNames(count)`，`count` 为正整数
- **THEN** 返回列表大小等于 `count`，且任意两个元素不相同

#### Scenario: count 非正数
- **WHEN** 调用 `generateNames(count)`，`count` 为 0 或负数
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 批量生成唯一且合法的身份证号
系统 SHALL 提供 `RandomUserInfoUtils.generateIdCardNos(int count)`，返回 `count` 个两两不重复、均能通过 `IdCardNoValidatorUtils.isValid` 校验的 18 位身份证号。

#### Scenario: 返回数量、唯一性与合法性
- **WHEN** 调用 `generateIdCardNos(count)`，`count` 为正整数
- **THEN** 返回列表大小等于 `count`，任意两个元素不相同，且每个元素都能被 `IdCardNoValidatorUtils.isValid` 校验为 `true`

#### Scenario: count 非正数
- **WHEN** 调用 `generateIdCardNos(count)`，`count` 为 0 或负数
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 批量生成唯一且格式合法的手机号
系统 SHALL 提供 `RandomUserInfoUtils.generatePhoneNumbers(int count)`，返回 `count` 个两两不重复、匹配 `^1[3-9]\d{9}$` 且前缀取自内置真实运营商号段清单的手机号。

#### Scenario: 返回数量、唯一性与格式
- **WHEN** 调用 `generatePhoneNumbers(count)`，`count` 为正整数
- **THEN** 返回列表大小等于 `count`，任意两个元素不相同，且每个元素都匹配 `^1[3-9]\d{9}$`

#### Scenario: count 非正数
- **WHEN** 调用 `generatePhoneNumbers(count)`，`count` 为 0 或负数
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 批量生成唯一的官方行政区划地址
系统 SHALL 提供 `RandomUserInfoUtils.generateRegions(int count)`，从内置的官方地区码表（民政部 2023 年 1 月列表，县级粒度，共 2842 条）中无重复地随机选取 `count` 个地区名称。

#### Scenario: 返回数量与唯一性
- **WHEN** 调用 `generateRegions(count)`，`count` 为正整数且不超过地区码表总条数
- **THEN** 返回列表大小等于 `count`，且任意两个元素不相同

#### Scenario: count 非正数
- **WHEN** 调用 `generateRegions(count)`，`count` 为 0 或负数
- **THEN** 方法抛出 `IllegalArgumentException`

#### Scenario: count 超过地区码表总条数
- **WHEN** 调用 `generateRegions(count)`，`count` 大于内置地区码表的总条数
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 批量生成组合用户数据
系统 SHALL 提供 `RandomUserInfoUtils.generateUsers(int count)`，返回 `count` 个 `RandomUserInfo`（字段：`name`、`phoneNumber`、`idCardNo`、`region`）。

#### Scenario: 返回数量符合预期
- **WHEN** 调用 `generateUsers(count)`，`count` 为正整数
- **THEN** 返回的列表大小等于 `count`

#### Scenario: 姓名、手机号、身份证号分别批次内唯一
- **WHEN** 调用 `generateUsers(count)` 且 `count > 1`
- **THEN** 返回列表中任意两个元素的 `name` 不相同，任意两个元素的 `phoneNumber` 不相同，任意两个元素的 `idCardNo` 不相同

#### Scenario: 不要求地区唯一
- **WHEN** 调用 `generateUsers(count)` 且 `count > 1`
- **THEN** 允许返回列表中出现多个元素的 `region` 相同

#### Scenario: 身份证号与手机号本身合法
- **WHEN** 调用 `generateUsers(count)`
- **THEN** 返回列表中每个元素的 `idCardNo` 均能被 `IdCardNoValidatorUtils.isValid` 校验为 `true`，每个元素的 `phoneNumber` 均匹配 `^1[3-9]\d{9}$`

#### Scenario: 地区与身份证号地区码一致
- **WHEN** 调用 `generateUsers(count)`
- **THEN** 返回列表中每个元素的 `region` 都是该元素 `idCardNo` 的地区码在地区码表中对应的地区名称

#### Scenario: count 非正数
- **WHEN** 调用 `generateUsers(count)`，`count` 为 0 或负数
- **THEN** 方法抛出 `IllegalArgumentException`

#### Scenario: count 超出唯一值组合空间
- **WHEN** 调用 `generateNames`/`generateIdCardNos`/`generatePhoneNumbers`/`generateUsers`，`count` 过大导致姓名、身份证号或手机号在限定尝试次数内无法生成足够的唯一值
- **THEN** 方法抛出 `IllegalStateException`，不陷入死循环、不返回数量不足的结果
