# RandomUserInfoUtils 使用说明

`RandomUserInfoUtils` 位于 `org.example.simple.util`，是无状态的随机用户信息生成工具类：批量生成用于测试/演示的随机姓名、身份证号、手机号、行政区划地址，以及把它们组合成一条完整用户记录。

## API 速查

| 场景 | 方法 | 唯一性范围 |
| --- | --- | --- |
| 批量生成随机姓名 | `generateNames(int count)` | 批次内两两不重复 |
| 批量生成合法身份证号 | `generateIdCardNos(int count)` | 批次内两两不重复，且每个都能通过 `IdCardNoValidatorUtils.isValid` |
| 批量生成合法手机号 | `generatePhoneNumbers(int count)` | 批次内两两不重复，格式 `^1[3-9]\d{9}$` |
| 批量生成行政区划地址 | `generateRegions(int count)` | 批次内两两不重复，从内置地区码表无放回抽样 |
| 批量生成组合用户数据 | `generateUsers(int count)` | 姓名/手机号/身份证号分别唯一（不要求联合唯一），地区不要求唯一 |

```java
import org.example.simple.util.RandomUserInfoUtils;
import org.example.simple.util.RandomUserInfo;

List<String> names = RandomUserInfoUtils.generateNames(10);
List<String> idCardNos = RandomUserInfoUtils.generateIdCardNos(10);
List<String> phoneNumbers = RandomUserInfoUtils.generatePhoneNumbers(10);
List<String> regions = RandomUserInfoUtils.generateRegions(10);

List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(10);
for (RandomUserInfo user : users) {
    // user.name() / user.phoneNumber() / user.idCardNo() / user.region()
}
```

## 5 个方法彼此独立

唯一性保证只作用于**同一次方法调用返回的列表内部**，不跨方法、不跨同一方法的不同调用——本类不维护任何跨调用的全局状态。例如连续两次调用 `generateNames(10)`，两次结果之间可能出现相同的姓名。

## `generateUsers`：姓名/手机号/身份证号分别唯一，地区不要求唯一

`generateUsers` 返回的 `RandomUserInfo(name, phoneNumber, idCardNo, region)` 列表中：

- `name`、`phoneNumber`、`idCardNo` 分别在列表内两两不重复（不要求"姓名+身份证号"组合唯一，也不要求三者联合唯一）；
- `region` **必须**与该条记录 `idCardNo` 的地区码在地区码表中对应的地区名称一致——这是生成身份证号时同一次选择产生的一对值，不是独立生成后拼接的；
- `region` 本身**不要求**在不同记录之间唯一（现实中同一地区有多个居民是正常情况）。

## 地区码数据来源：民政部 2023 年 1 月官方列表

地区码表以资源文件 `area-codes.csv` 的形式随项目打包，数据来源是民政部 2023 年 1 月公布的《2023年中华人民共和国县以上行政区划代码》（<https://www.mca.gov.cn/mzsj/xzqh/2023/202301xzqh.html>），取其中全部**县级（区/县/县级市）叶子节点，共 2842 条**，地区名称是完整的"省+市+县"三级路径（如 `河南省郑州市中原区`；直辖市/省直辖县级市等没有独立地级市层级的情况下只拼"省+县"，如 `北京市东城区`、`河南省济源市`）。

这是该官方列表在 2023-01 的一份**静态快照**，不会随后续行政区划调整（撤县设区、合并等）自动更新。`generateRegions(count)` 的 `count` 上限精确为 2842（表内总条数）；超过会直接抛出 `IllegalArgumentException`，不会重试。

## 手机号号段：静态内置的真实运营商号段清单

`generatePhoneNumbers`/`generateUsers` 生成的手机号由内置的 3 位运营商号段前缀（覆盖中国移动/联通/电信及部分虚拟运营商已公开使用的号段）+ 随机 8 位数字组成，整体匹配 `^1[3-9]\d{9}$`。号段清单是静态维护的快照，**不做**任何实时的携号转网、在网状态校验——只保证"结构和号段真实存在"，不保证具体号码当前是否被真实用户使用。

## 身份证号生成规则

- 地区码：从内置地区码表随机选取。
- 出生日期：固定范围 `1960-01-01` 至"当前日期减 18 年"（即生成的用户至少 18 周岁），不可配置。
- 顺序码：随机 3 位数字（`000`-`999`）。
- 校验码：按 GB 11643 算法（与 `IdCardNoValidatorUtils` 相同的加权系数表）计算，生成后立即用 `IdCardNoValidatorUtils.isValid` 自校验。

不保证生成姓名的性别与身份证号校验位前一位（第 17 位，奇数男/偶数女）编码的性别一致。

## 参数校验与异常

| 异常 | 触发条件 |
| --- | --- |
| `IllegalArgumentException` | `count` 不是正整数；或 `generateRegions` 的 `count` 超过地区码表总条数（2842） |
| `IllegalStateException` | 姓名/身份证号/手机号在限定尝试次数（1000 次/个）内无法生成出新的唯一值——通常意味着 `count` 过大，超出了该维度组合空间的合理使用范围 |

## 非目标

- 不保证地区码表、手机号号段清单随官方变化自动更新。
- 不支持自定义出生日期范围、地区码白名单、手机号号段范围、姓氏库等参数。
- 不保证极大 `count`（如超过十万）场景下 `generateNames`/`generateIdCardNos`/`generatePhoneNumbers`/`generateUsers` 的性能和成功率。

## 线程安全

本工具类无状态，所有方法均可被多线程并发调用。
