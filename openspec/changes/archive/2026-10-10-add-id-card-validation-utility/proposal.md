## Why

项目目前没有任何校验中国大陆居民身份证号（18 位，GB 11643 标准）的工具。身份证号校验是用户注册、实名认证、报表录入等场景的高频需求：既要校验格式（长度、地区码、出生日期、顺序码、校验位是否符合 18 位规则），也要校验最后一位校验码是否与前 17 位计算结果一致，否则只能称为"像身份证号"而不是"准确的身份证号"。各处手写正则和校验码算法容易遗漏边界情况（如 2 月 29 日、校验码为 X 的大小写）。需要一个统一、职责单一的工具类负责身份证号的格式与准确性校验。

## What Changes

- 新增 `org.example.simple.util.IdCardNoValidatorUtils` 静态工具类（`final` + 私有构造器），与 `IpRangeCheckUtils`/`HttpRequestUtils` 等现有工具类同级，仅使用 JDK 内置 API（正则表达式、`java.time.LocalDate`），不引入新的第三方依赖。
- 只支持 18 位居民身份证号（GB 11643-1999 现行标准）；不支持已废止的 15 位老身份证号格式（Non-Goal，见 `design.md`）。
- 提供 `boolean isValid(String idCardNo)`：
  - 第一步用正则表达式校验整体结构：6 位地区码（首位不为 0）+ 8 位出生日期（年份以 `18`/`19`/`20` 开头）+ 3 位顺序码 + 1 位校验位（`0-9` 或 `X`/`x`）。
  - 第二步校验出生日期段是否为真实存在的公历日期（如拒绝 `20230229`、`20231332` 等正则无法识别但日期不存在的值），并限定年份不晚于当前年份。
  - 第三步按 GB 11643 附录 B（`ISO 7064:1983.MOD 11-2`）算法，用前 17 位数字计算校验码，与第 18 位比较（忽略大小写）。
  - 三步均通过才返回 `true`，任何一步未通过（包括入参为 `null`/空白/长度或字符不符）均返回 `false`，不抛出异常——该方法的职责就是回答"这个字符串是不是一个准确的身份证号"，不合法的输入是预期的业务结果而不是调用错误。
- 不提供从身份证号中提取出生日期、性别、地区码等信息的方法（Non-Goal，超出"格式与准确性校验"范围，如后续需要应另行提出变更）。

## Capabilities

### New Capabilities

- `id-card-validation-utilities`：定义 18 位居民身份证号的结构正则校验、出生日期真实性校验、GB 11643 校验码算法校验，并统一通过 `isValid` 返回校验结果的行为。

### Modified Capabilities

无。不修改 `IpRangeCheckUtils` 等既有工具类。

## Impact

- 新增 `src/main/java/org/example/simple/util/IdCardNoValidatorUtils.java`。
- 新增 `src/test/java/org/example/simple/util/IdCardNoValidatorUtilsTest.java`。
- 不新增 Gradle 依赖；不修改既有工具类。
