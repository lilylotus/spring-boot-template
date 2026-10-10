## Why

项目已有 `IdCardNoValidatorUtils` 校验身份证号是否准确，但没有工具能批量生成"看起来真实"的测试用户数据——姓名、身份证号、手机号、行政区划地址，以及把它们组合成一条完整用户记录。单元测试、演示数据、压测脚本等场景经常需要这样一批数据：姓名不重复、身份证号不重复（且本身通过 `IdCardNoValidatorUtils.isValid` 校验）、手机号格式合法且不重复、行政区划地址取自官方真实存在的地区。手写随机数据容易生成不合法的身份证号/手机号或忽略重复，需要一个统一的工具类负责这件事。

## What Changes

- 新增 `org.example.simple.util.RandomUserInfoUtils` 静态工具类（`final` + 私有构造器），与 `IdCardNoValidatorUtils` 同级；新增不可变数据载体 `org.example.simple.util.RandomUserInfo`（`record`，字段：`name`、`phoneNumber`、`idCardNo`、`region`）。
- 提供 5 个公开静态方法：
  1. `List<String> generateNames(int count)`：生成 `count` 个两两不重复的随机姓名（姓氏池 + 1-2 字常见用字池随机组合）。
  2. `List<String> generateIdCardNos(int count)`：生成 `count` 个两两不重复、且每个都能被 `IdCardNoValidatorUtils.isValid` 校验通过的 18 位身份证号。
  3. `List<String> generatePhoneNumbers(int count)`：生成 `count` 个两两不重复、符合中国大陆手机号格式（真实运营商号段前缀 + 8 位随机数字，共 11 位）的手机号。
  4. `List<String> generateRegions(int count)`：生成 `count` 个两两不重复的行政区划地址（从内置的官方地区码表中随机取地区名称，见下方"地区码数据来源"）。
  5. `List<RandomUserInfo> generateUsers(int count)`：生成 `count` 条组合用户数据（`name`、`phoneNumber`、`idCardNo`、`region`）。**姓名、手机号、身份证号分别在本次返回结果内两两不重复**（不要求"姓名+身份证号"组合唯一，也不要求三者联合唯一）；**`region` 必须是该条记录 `idCardNo` 的地区码在地区码表中对应的地区名称**，即地区与身份证号一一对应，不是独立随机生成后拼凑的。
- 以上 5 个方法彼此独立：同一次调用内部保证返回列表满足各自的唯一性要求，但不同方法之间、或同一方法的不同两次调用之间，不保证数据不重复（例如先调用一次 `generateNames(10)` 再调用一次 `generateNames(10)`，两次结果可能出现相同姓名）。
- `count` 必须是正整数，否则抛出 `IllegalArgumentException`；`generateRegions` 的 `count` 超过内置地区码表总条数时，同样抛出 `IllegalArgumentException`（地区池大小是已知有限值，可以直接校验，不需要重试）；`generateNames`/`generateIdCardNos`/`generatePhoneNumbers`/`generateUsers` 中某一类唯一值在限定尝试次数内生成不出来时，抛出 `IllegalStateException`（见 `design.md`）。
- **地区码数据来源**：地区码表以资源文件形式内置在项目中，数据来源为民政部 2023 年 1 月公布的《2023年中华人民共和国县以上行政区划代码》（<https://www.mca.gov.cn/mzsj/xzqh/2023/202301xzqh.html>），取其中全部县级（区/县/县级市）叶子节点共 2842 条，是该官方列表在 2023-01 的一份静态快照，不会随后续行政区划调整自动更新。
- 不保证生成的姓名性别与身份证号校验位前一位编码的性别一致（Non-Goal）。
- 手机号的运营商号段前缀取自内置的真实号段清单（Non-Goal：不做实时携号转网/在网状态校验，详见 `design.md`）。
- 不支持自定义出生日期范围、地区码范围、手机号号段范围等参数（Non-Goal，本次只实现无参数的批量生成）。

## Capabilities

### New Capabilities

- `random-user-info-generation`：定义批量生成唯一随机姓名、唯一且合法的身份证号、唯一且格式合法的手机号、唯一的官方行政区划地址，以及批量生成姓名/手机号/身份证号分别唯一且地区与身份证号一致的组合用户数据的行为。

### Modified Capabilities

无。不修改 `IdCardNoValidatorUtils` 等既有工具类（只调用其 `isValid` 作为生成结果的内部自校验，不改动其实现）。

## Impact

- 新增 `src/main/java/org/example/simple/util/RandomUserInfoUtils.java`。
- 新增 `src/main/java/org/example/simple/util/RandomUserInfo.java`。
- 新增 `src/main/resources/area-codes.csv`：民政部 2023 年 1 月行政区划代码列表中全部 2842 条县级地区码与完整地区名称（格式 `code,regionName`，每行一条），作为 `RandomUserInfoUtils` 的内置数据源随 classpath 打包。
- 新增 `src/test/java/org/example/simple/util/RandomUserInfoUtilsTest.java`。
- 不新增 Gradle 依赖；不修改既有工具类。
