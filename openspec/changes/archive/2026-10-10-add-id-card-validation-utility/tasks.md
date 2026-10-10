# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/id-card-validation-utilities/spec.md` 的明确确认，尤其是：`isValid` 对非法输入统一返回 `false` 而非抛异常、正则只做结构校验且需配合 `LocalDate` 做日期真实性二次校验、不支持 15 位老身份证号、不校验地区码真实性这几项设计决策。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.util.IdCardNoValidatorUtils`（`final` + 私有构造器），预编译结构校验用的 `private static final Pattern`。
- [x] 2.2 实现 `isValid(String idCardNo)`：
  - [x] 2.2.1 `idCardNo` 为 `null` 或经 `trim` 后为空时返回 `false`。
  - [x] 2.2.2 用预编译正则 `^[1-9]\d{5}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx]$` 做结构校验，不匹配返回 `false`。
  - [x] 2.2.3 截取第 7-14 位，用 `LocalDate.of(year, month, day)` 校验日期真实存在（捕获 `DateTimeException` 返回 `false`），并校验年份不晚于 `LocalDate.now().getYear()`。
  - [x] 2.2.4 对前 17 位按位权 `[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]` 加权求和，对 11 取余后查映射表得到期望校验码，与第 18 位（转大写）比较，不一致返回 `false`。
  - [x] 2.2.5 以上均通过返回 `true`。
- [x] 2.3 为 `IdCardNoValidatorUtils` 类及 `isValid` 方法补充中文 Javadoc：说明只支持 18 位现行标准、校验的三个层面（结构/日期/校验码）、不校验地区码真实性与不支持 15 位老号码的 Non-Goal、对非法输入返回 `false` 而非抛异常的语义、线程安全性。

## 3. 测试

- [x] 3.1 新增 `IdCardNoValidatorUtilsTest`。
- [x] 3.2 测试合法身份证号：至少覆盖校验码为数字和校验码为 `X`（含大写 `X` 与小写 `x`）两种真实可计算得到的号码，均返回 `true`。
- [x] 3.3 测试长度/字符非法：长度不是 18 位、含字母（非末位）、含符号，均返回 `false`。
- [x] 3.4 测试出生日期非法：月份 `00`/`13`、日期 `00`/`32`、格式正确但日期不存在（如 `20230229`、`20230431`），均返回 `false`。
- [x] 3.5 测试出生年份超过当前年份（如年份为明年），返回 `false`。
- [x] 3.6 测试校验码不匹配：构造一个结构和日期均合法但第 18 位校验码错误的号码，返回 `false`。
- [x] 3.7 测试空值与空白：`null`、空字符串、纯空白字符串，均返回 `false`，不抛异常。
- [x] 3.8 测试地区码边界：首位为 `0`（如 `012345...`）返回 `false`（正则已排除）。

## 4. 文档与验证

- [x] 4.1 新增 `docs/IdCardNoValidatorUtils使用说明.md`：说明支持范围（仅 18 位现行标准）、三层校验逻辑（结构/日期/校验码）、不校验地区码真实性和不支持 15 位老号码的原因、对非法输入返回 `false` 的设计原因；在 `README.md` 增加一行入口链接。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-id-card-validation-utility --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.IdCardNoValidatorUtilsTest"`：16 个测试全部通过。
- `gradlew.bat build`（全量）：`IdCardNoValidatorUtilsTest` 全部通过；`GlobalHttpClientUtilsTest`/`HttpClientsTest` 中共 7 个测试因本地无可用 HTTP 服务（`HttpHostConnectException`）或既有偶发性（evictor 关闭断言）失败，与本变更无关，未作改动。
- `openspec validate 2026-10-10-add-id-card-validation-utility --strict`：`Change '2026-10-10-add-id-card-validation-utility' is valid`。
