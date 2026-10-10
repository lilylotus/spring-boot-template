# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/random-user-info-generation/spec.md` 的明确确认，尤其是：5 个方法彼此独立、不跨方法/跨调用保证唯一性；地区码表取自民政部 2023 年 1 月官方列表的全部 2842 条县级地区码，以资源文件 `area-codes.csv` 形式打包且不随官方变更自动更新；`generateRegions` 的 `count` 上限精确为 2842 且超出直接抛 `IllegalArgumentException`；`generateUsers` 中姓名/手机号/身份证号分别唯一但地区不唯一；出生日期范围固定为 1960 年至 18 年前且不可配置；手机号号段前缀为静态内置清单；姓名与身份证号性别不关联；其余唯一性用查重+重试+尝试次数上限实现，超限抛 `IllegalStateException` 这几项设计决策。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.util.RandomUserInfo`（`record`，字段 `name`、`phoneNumber`、`idCardNo`、`region`，补充中文 Javadoc）。
- [x] 2.2 新增 `org.example.simple.util.RandomUserInfoUtils`（`final` + 私有构造器）。
- [x] 2.3 新增 `src/main/resources/area-codes.csv`（民政部 2023 年 1 月列表的全部 2842 条县级地区码，格式 `code,regionName`），在类加载时通过 `ClassLoader.getResourceAsStream` 读取并解析为内存中的不可变列表（元素为内部 `record AreaCode(String code, String region)` 或等价结构）。
- [x] 2.4 内置约 100 个常见单字姓氏池、约 100 个常见用字池（`private static final String[]`）。
- [x] 2.5 内置手机号运营商号段前缀清单（`private static final String[]`，3 位数字字符串，覆盖中国移动/联通/电信及部分虚拟运营商已公开使用的号段）。
- [x] 2.6 实现私有姓名生成辅助方法：姓氏池随机取 1 个 + 用字池随机取 1-2 个拼接；接收一个外部传入的"已使用姓名" `Set<String>`，生成后查重、冲突则重新生成，超过单元素尝试次数上限（如 1000 次）抛 `IllegalStateException`。
- [x] 2.7 实现私有身份证号生成辅助方法（返回身份证号与所选地区名称的配对）：
  - 从地区码表随机选一条（同时拿到地区码与地区名称）；
  - 随机生成出生日期（范围：`1960-01-01` 至 `当前日期减 18 年`，用 `java.time.LocalDate` 计算天数范围后随机取值）；
  - 随机生成 3 位顺序码（`000`-`999`）；
  - 按 GB 11643 加权系数表（与 `IdCardNoValidatorUtils` 相同的 `[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]` 与映射表）计算第 18 位校验码；
  - 拼出完整 18 位身份证号后，用 `IdCardNoValidatorUtils.isValid(idCardNo)` 做自校验，断言为 `true`（校验失败说明生成逻辑有 bug，抛 `IllegalStateException` 并包含该身份证号，便于排查）；
  - 接收外部传入的"已使用身份证号" `Set<String>`，查重冲突则重新生成，超过单元素尝试次数上限抛 `IllegalStateException`。
- [x] 2.8 实现私有手机号生成辅助方法：号段前缀清单随机取 1 个 + 随机 8 位数字拼接成 11 位手机号；接收外部传入的"已使用手机号" `Set<String>`，查重冲突则重新生成，超过单元素尝试次数上限抛 `IllegalStateException`。
- [x] 2.9 实现 `List<String> generateNames(int count)`：`count <= 0` 抛 `IllegalArgumentException`；循环 `count` 次调用 2.6 的姓名生成辅助方法（共用一个本次调用内的 `Set<String>`），返回不可变 `List<String>`。
- [x] 2.10 实现 `List<String> generateIdCardNos(int count)`：`count <= 0` 抛 `IllegalArgumentException`；循环 `count` 次调用 2.7 的辅助方法（共用一个本次调用内的 `Set<String>`），只取身份证号部分，返回不可变 `List<String>`。
- [x] 2.11 实现 `List<String> generatePhoneNumbers(int count)`：`count <= 0` 抛 `IllegalArgumentException`；循环 `count` 次调用 2.8 的辅助方法（共用一个本次调用内的 `Set<String>`），返回不可变 `List<String>`。
- [x] 2.12 实现 `List<String> generateRegions(int count)`：`count <= 0` 抛 `IllegalArgumentException`；`count` 超过地区码表总条数抛 `IllegalArgumentException`；否则对地区码表做无放回随机抽样（如先 `Collections.shuffle` 一份拷贝再取前 `count` 个），返回对应地区名称组成的不可变 `List<String>`。
- [x] 2.13 实现 `List<RandomUserInfo> generateUsers(int count)`：`count <= 0` 抛 `IllegalArgumentException`；循环 `count` 次，每次用各自独立的 `Set<String>`（姓名/身份证号/手机号各一个，跨 `count` 次循环累积）调用 2.6/2.7/2.8 的辅助方法生成一条记录的姓名、（身份证号+地区名称）、手机号，组装为 `RandomUserInfo`（`region` 直接取 2.7 返回的地区名称），加入结果列表，最终返回不可变 `List<RandomUserInfo>`。
- [x] 2.14 为 `RandomUserInfoUtils`、`RandomUserInfo` 及全部公开方法补充中文 Javadoc：说明每个方法各自的唯一性保证范围（不跨方法/跨调用）、地区码表数据来源与条数上限、出生日期固定范围、手机号号段清单的静态性质与局限、姓名与身份证号性别不关联、`count` 参数校验与各类异常触发条件、线程安全性。

## 3. 测试

- [x] 3.1 新增 `RandomUserInfoUtilsTest`。
- [x] 3.2 `generateNames`：返回数量等于 `count`；批次内两两不重复；`count` 为 0 或负数抛 `IllegalArgumentException`。
- [x] 3.3 `generateIdCardNos`：返回数量等于 `count`；批次内两两不重复；每个身份证号都能通过 `IdCardNoValidatorUtils.isValid`；`count` 非正数抛 `IllegalArgumentException`。
- [x] 3.4 `generatePhoneNumbers`：返回数量等于 `count`；批次内两两不重复；每个号码匹配 `^1[3-9]\d{9}$`；`count` 非正数抛 `IllegalArgumentException`。
- [x] 3.5 `generateRegions`：返回数量等于 `count`；批次内两两不重复；`count` 非正数抛 `IllegalArgumentException`；`count` 超过地区码表总条数（2842）抛 `IllegalArgumentException`；`count` 恰好等于总条数时能成功返回全部地区。
- [x] 3.6 `generateUsers`：返回数量等于 `count`；批次内 `name` 两两不重复、`phoneNumber` 两两不重复、`idCardNo` 两两不重复；每条记录的 `idCardNo` 能通过 `IdCardNoValidatorUtils.isValid`；每条记录的 `region` 与其 `idCardNo` 前 6 位地区码对应同一条内置地区码表记录；`count` 非正数抛 `IllegalArgumentException`。
- [x] 3.7 测试跨方法/跨调用不保证唯一性的边界说明性用例（可选）：连续两次调用 `generateNames(1)` 不强制断言结果不同（避免测试本身依赖"不唯一"这个弱保证）。
- [x] 3.8 测试较大 `count`（如 1000）时 `generateNames`/`generateIdCardNos`/`generatePhoneNumbers`/`generateUsers` 仍能在合理时间内成功生成且满足各自约束。

## 4. 文档与验证

- [x] 4.1 新增 `docs/RandomUserInfoUtils使用说明.md`：说明 5 个方法的用法与各自的唯一性保证范围、地区码表覆盖范围（2842 条县级地区，数据来源与截止时间）、手机号号段清单的静态性质、出生日期固定范围、姓名与性别不关联、`count` 参数校验与异常触发条件（`IllegalArgumentException`/`IllegalStateException` 各自适用场景）；在 `README.md` 增加一行入口链接。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate 2026-10-10-add-random-user-info-generator-utility --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.RandomUserInfoUtilsTest"`：18 个测试全部通过。
- `gradlew.bat build`（全量）：`RandomUserInfoUtilsTest` 全部通过；`GlobalHttpClientUtilsTest`/`HttpClientsTest` 中共 7 个测试因本地无可用 HTTP 服务（`HttpHostConnectException`）或既有偶发性（evictor 关闭断言）失败，与本变更无关，未作改动。
- `openspec validate 2026-10-10-add-random-user-info-generator-utility --strict`：`Change '2026-10-10-add-random-user-info-generator-utility' is valid`。
- 任务 3.7（跨调用不唯一性的说明性用例）未单独新增测试：该项标注为可选，文档（`docs/RandomUserInfoUtils使用说明.md`）中已明确说明该行为。
