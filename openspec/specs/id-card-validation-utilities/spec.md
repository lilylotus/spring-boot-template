# id-card-validation-utilities Specification

## Purpose
为 Java 代码提供校验中国大陆 18 位居民身份证号格式与准确性的统一静态工具：结合正则表达式做结构校验、`LocalDate` 做出生日期真实性校验、GB 11643 算法做校验码校验，纯本地计算不依赖外部数据源。

## Requirements

### Requirement: 校验 18 位居民身份证号的准确性
系统 SHALL 提供 `IdCardNoValidatorUtils.isValid(String idCardNo)`，综合结构格式、出生日期真实性、校验码正确性三个层面判断 `idCardNo` 是否为一个准确的 18 位居民身份证号。

#### Scenario: 合法号码（数字校验码）
- **WHEN** `idCardNo` 是结构、出生日期、校验码均正确且第 18 位为数字的 18 位身份证号
- **THEN** 方法返回 `true`

#### Scenario: 合法号码（校验码为 X）
- **WHEN** `idCardNo` 是结构、出生日期均正确，且按 GB 11643 算法计算出的校验码为 `X`（输入为大写 `X` 或小写 `x`）
- **THEN** 方法返回 `true`

#### Scenario: 长度或字符非法
- **WHEN** `idCardNo` 长度不是 18 位，或包含除末位以外的非数字字符，或地区码首位为 `0`
- **THEN** 方法返回 `false`

#### Scenario: 出生日期不是真实存在的日期
- **WHEN** `idCardNo` 第 7-14 位格式上像日期（年月日数字范围均在正则允许区间内），但实际不是真实存在的公历日期（如 `20230229`、`20230431`）
- **THEN** 方法返回 `false`

#### Scenario: 出生年份晚于当前年份
- **WHEN** `idCardNo` 第 7-10 位表示的出生年份晚于方法执行时的当前年份
- **THEN** 方法返回 `false`

#### Scenario: 校验码不匹配
- **WHEN** `idCardNo` 前 17 位格式、日期均合法，但第 18 位与按 GB 11643 算法计算出的校验码不一致
- **THEN** 方法返回 `false`

#### Scenario: 空值或空白输入
- **WHEN** `idCardNo` 为 `null`、空字符串或仅包含空白字符
- **THEN** 方法返回 `false`，不抛出异常

#### Scenario: 15 位老身份证号不支持
- **WHEN** `idCardNo` 是 15 位（1999 年前的老身份证号格式）
- **THEN** 方法返回 `false`（视为长度不合法，不做老号码兼容转换）
