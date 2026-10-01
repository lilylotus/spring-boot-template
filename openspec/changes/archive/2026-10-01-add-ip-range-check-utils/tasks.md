# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/ip-range-check-utilities/spec.md` 的明确确认，尤其是：手动实现 IPv4/IPv6 文本解析（不使用 `InetAddress.getByName`，避免退化为 DNS 解析）、CIDR 与起止地址统一转换为 `BigInteger` 数值区间比较、地址族不一致返回 `false` 而非异常、`isInAnyRange` 空集合返回 `false` 但 `null`/含 `null` 元素抛异常这几项设计决策。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.util.IpRangeCheckUtils`（`final` + 私有构造器）。
- [x] 2.2 实现 IPv4 字面量解析为 4 字节数组：按 `.` 分成 4 段，每段必须是 0-255 的十进制数且不含除数字外的其他字符；格式错误抛 `IllegalArgumentException`。
- [x] 2.3 实现 IPv6 字面量解析为 16 字节数组：支持标准 8 组冒号分隔十六进制、`::` 压缩零段（最多一次）、结尾内嵌 IPv4 形式（复用 2.2 的解析）；格式错误（分组数不对、非法十六进制、多个 `::` 等）抛 `IllegalArgumentException`。
- [x] 2.4 实现统一入口 `parseLiteralAddress(String text)`：含 `:` 走 IPv6 解析，不含 `:` 走 IPv4 解析，返回字节数组及对应地址族枚举（`IPV4`/`IPV6`）；`text` 为 `null`/空白时抛 `IllegalArgumentException`。
- [x] 2.5 实现 `parseRange(String ipRange)`：按以下规则解析为 `(地址族, 起始地址 BigInteger, 结束地址 BigInteger)`：
  - 含 `/`：CIDR，前半部分用 2.4 解析地址，后半部分解析为前缀长度（十进制整数），校验在 `[0, 32]`（IPv4）或 `[0, 128]`（IPv6）范围内；用 `hostBits = 总位数 - 前缀长度` 计算主机位掩码，起始地址 = 地址值清除低 `hostBits` 位，结束地址 = 起始地址按位或主机位掩码。
  - 含 `-`（且不含 `/`）：起止地址，两端分别用 2.4 解析，要求地址族相同（不同则抛 `IllegalArgumentException`），起始地址数值不能大于结束地址数值（否则抛 `IllegalArgumentException`）。
  - 否则：按单个 IP 处理，起始地址 = 结束地址 = 该地址数值。
  - 地址数值统一用 `new BigInteger(1, bytes)`（无符号）表示。
- [x] 2.6 实现 `isInRange(String ip, String ipRange)`：用 2.4 解析 `ip` 得到地址族和数值，用 2.5 解析 `ipRange` 得到地址族与起止数值；地址族不一致返回 `false`；否则返回 `起始 <= ip数值 <= 结束`。
- [x] 2.7 实现 `isInAnyRange(String ip, Collection<String> ipRanges)`：`ip`/`ipRanges` 为 `null`，或 `ipRanges` 含 `null` 元素时抛 `IllegalArgumentException`；`ipRanges` 为空返回 `false`；否则遍历调用 2.6，命中即短路返回 `true`，全部未命中返回 `false`。
- [x] 2.8 为 `IpRangeCheckUtils` 及两个公开方法、关键私有方法补充中文 Javadoc：说明支持的三种范围表达式格式、IPv4/IPv6 字面量解析规则与 Non-Goal（不支持区域 ID、不做 DNS 解析）、地址族不一致返回 `false` 的语义、参数校验与异常触发条件、线程安全性（无状态，可并发调用）。

## 3. 测试

- [x] 3.1 新增 `IpRangeCheckUtilsTest`。
- [x] 3.2 测试 IPv4 CIDR：网段内命中（含网络地址、广播地址边界）、网段外未命中、`/32` 单地址、`/0` 全网段。
- [x] 3.3 测试 IPv4 起止地址：范围内命中（含两端边界）、范围外未命中、起始等于结束。
- [x] 3.4 测试 IPv6 CIDR：标准全写地址、`::` 压缩地址、内嵌 IPv4 形式地址分别验证网段内/外命中情况；`/128` 单地址、`/0` 全网段。
- [x] 3.5 测试 IPv6 起止地址：范围内/外命中情况。
- [x] 3.6 测试单 IP 精确匹配（`ipRange` 不含 `/` 和 `-`）：IPv4 和 IPv6 各一个用例。
- [x] 3.7 测试地址族不一致：IPv6 地址对 IPv4 CIDR/范围，反之亦然，均返回 `false` 不抛异常。
- [x] 3.8 测试参数非法：`ip`/`ipRange` 为 `null`/空白字符串；`ip` 不是合法 IP（如 `999.1.1.1`、`not-an-ip`、域名字符串）；`ipRange` 的 CIDR 前缀长度越界（负数、超过地址族上限）；起止地址表示法中起始大于结束；均抛 `IllegalArgumentException`。
- [x] 3.9 测试 `isInAnyRange`：集合中某一项命中返回 `true`；全部未命中返回 `false`；空集合返回 `false`；`ip`/`ipRanges` 为 `null` 或集合含 `null` 元素抛 `IllegalArgumentException`。

## 4. 文档与验证

- [x] 4.1 新增 `docs/IpRangeCheckUtils使用说明.md`：覆盖支持的三种范围表达式格式、IPv4/IPv6 示例、地址族不一致返回 `false` 的说明、不做 DNS 解析的设计原因、参数校验规则、`isInAnyRange` 用法；在 `README.md` 增加一行入口链接。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-ip-range-check-utils --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.IpRangeCheckUtilsTest"`：34 个测试全部通过。
- `gradlew.bat build`（全量）：`IpRangeCheckUtilsTest` 全部通过；`RpcLoopbackIntegrationTest.gracefulShutdownCompletesInFlightCall()` 在全量并发执行下偶发失败，单独重跑（`--tests "org.example.simple.rpc.RpcLoopbackIntegrationTest" --rerun`）通过，确认是与本变更无关的既有偶发性（flaky）测试，未作改动。
- `openspec validate add-ip-range-check-utils --strict`：`Change 'add-ip-range-check-utils' is valid`。
