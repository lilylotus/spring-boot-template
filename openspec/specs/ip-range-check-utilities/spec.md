# ip-range-check-utilities Specification

## Purpose
为 Java 代码提供判断指定 IP 地址是否落在给定 IP 范围（CIDR 或起止地址表示法）内的统一静态工具，同时支持 IPv4 和 IPv6，纯本地计算不发起网络调用。

## Requirements

### Requirement: 判断 IP 是否属于单个范围
系统 SHALL 提供 `IpRangeCheckUtils.isInRange(String ip, String ipRange)`，判断 `ip` 是否落在 `ipRange` 描述的范围内。`ipRange` SHALL 支持 CIDR 表示法（如 `192.168.1.0/24`）、起止地址表示法（如 `192.168.1.1-192.168.1.100`）、单个 IP 地址（视为只包含该地址的范围）三种格式。

#### Scenario: CIDR 范围内命中
- **WHEN** `ip` 是 `192.168.1.100`，`ipRange` 是 `192.168.1.0/24`
- **THEN** 方法返回 `true`

#### Scenario: CIDR 范围外未命中
- **WHEN** `ip` 是 `192.168.2.1`，`ipRange` 是 `192.168.1.0/24`
- **THEN** 方法返回 `false`

#### Scenario: 起止地址范围内命中
- **WHEN** `ip` 是 `10.0.0.50`，`ipRange` 是 `10.0.0.1-10.0.0.100`
- **THEN** 方法返回 `true`

#### Scenario: 起止地址范围外未命中
- **WHEN** `ip` 是 `10.0.0.200`，`ipRange` 是 `10.0.0.1-10.0.0.100`
- **THEN** 方法返回 `false`

#### Scenario: 单个 IP 精确匹配
- **WHEN** `ip` 和 `ipRange` 是同一个 IP 地址字符串
- **THEN** 方法返回 `true`

#### Scenario: IPv6 CIDR 范围内命中
- **WHEN** `ip` 是 `2001:db8::1`，`ipRange` 是 `2001:db8::/32`
- **THEN** 方法返回 `true`

#### Scenario: 地址族不一致判定为不属于
- **WHEN** `ip` 是合法的 IPv6 地址，`ipRange` 是合法的 IPv4 CIDR（或反之）
- **THEN** 方法返回 `false`，不抛出异常

#### Scenario: IP 参数非法
- **WHEN** 调用方传入的 `ip` 为 `null`、空白字符串，或不是合法的字面量 IPv4/IPv6 地址
- **THEN** 方法抛出 `IllegalArgumentException`

#### Scenario: 范围表达式非法
- **WHEN** 调用方传入的 `ipRange` 为 `null`、空白字符串，CIDR 前缀长度超出该地址族允许的范围（IPv4 超过 32、IPv6 超过 128）或为负数，或起止地址表示法中起始地址大于结束地址
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 判断 IP 是否属于任意一个范围
系统 SHALL 提供 `IpRangeCheckUtils.isInAnyRange(String ip, Collection<String> ipRanges)`，判断 `ip` 是否落在 `ipRanges` 中任意一个范围内，每个元素遵循与 `isInRange` 相同的范围表达式格式。

#### Scenario: 命中集合中的某一个范围
- **WHEN** `ipRanges` 包含多个范围表达式，其中至少一个范围包含 `ip`
- **THEN** 方法返回 `true`

#### Scenario: 未命中集合中的任何范围
- **WHEN** `ipRanges` 中的每个范围都不包含 `ip`
- **THEN** 方法返回 `false`

#### Scenario: 空集合视为不匹配
- **WHEN** `ipRanges` 是空集合
- **THEN** 方法返回 `false`，不抛出异常

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `ip` 为 `null`/空白/格式非法，`ipRanges` 为 `null`，`ipRanges` 中存在 `null` 元素，或某个元素本身格式非法
- **THEN** 方法抛出 `IllegalArgumentException`
