# IpRangeCheckUtils 使用说明

`IpRangeCheckUtils` 位于 `org.example.simple.util`，是无状态的 IP 范围匹配工具类：判断指定 IP 地址是否落在给定的 IP 范围内，同时支持 IPv4 和 IPv6。纯本地字符串解析和数值比较，不发起任何网络调用。

## 支持的范围表达式格式

| 格式 | 示例 | 说明 |
| --- | --- | --- |
| CIDR | `192.168.1.0/24`、`2001:db8::/32` | 前缀长度 IPv4 为 0-32，IPv6 为 0-128 |
| 起止地址 | `192.168.1.1-192.168.1.100` | 用英文连字符分隔，两端地址族必须相同，起始地址不能大于结束地址 |
| 单个 IP | `192.168.1.1` | 视为只包含该地址的范围（等价于 `/32` 或 `/128`） |

## API 速查

| 场景 | 方法 |
| --- | --- |
| 判断是否属于单个范围 | `isInRange(String ip, String ipRange)` |
| 判断是否属于任意一个范围 | `isInAnyRange(String ip, Collection<String> ipRanges)` |

## 基本用法

```java
import org.example.simple.util.IpRangeCheckUtils;

// CIDR
boolean inSubnet = IpRangeCheckUtils.isInRange("192.168.1.100", "192.168.1.0/24"); // true

// 起止地址
boolean inRange = IpRangeCheckUtils.isInRange("10.0.0.50", "10.0.0.1-10.0.0.100"); // true

// IPv6
boolean inIpv6Subnet = IpRangeCheckUtils.isInRange("2001:db8::1", "2001:db8::/32"); // true

// 白名单：多个范围任意一个命中即可
List<String> whitelist = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.1.0/24");
boolean allowed = IpRangeCheckUtils.isInAnyRange(clientIp, whitelist);
```

典型用法是和 `HttpRequestUtils.getClientIp(request)` 搭配，在拦截器/过滤器里做访问控制：

```java
String clientIp = HttpRequestUtils.getClientIp(request);
if (!IpRangeCheckUtils.isInAnyRange(clientIp, allowedRanges)) {
    // 拒绝访问
}
```

## 地址族不一致：返回 false，不是异常

`ip` 和 `ipRange` 的地址族（IPv4/IPv6）不一致时，直接判定为不属于，返回 `false`，不抛出异常——这是正常的"规则不适用"结果：

```java
IpRangeCheckUtils.isInRange("2001:db8::1", "192.168.1.0/24"); // false，不抛异常
```

只有 **范围表达式本身格式错误**（CIDR 前缀长度越界、起止地址表示法中起始地址大于结束地址等）才是 `IllegalArgumentException`，因为这属于调用方配置写错了，不应该被静默当成"不匹配"。

## 不做 DNS 解析

IPv4/IPv6 字面量由本工具手动解析为字节数组，不使用 `InetAddress.getByName(String)`——该方法对不合法的字面量会退化为真正的主机名 DNS 解析，一个写错的 IP 字符串就可能触发网络调用。本工具保证任何输入下都是纯本地计算：传入域名字符串（如 `"example.com"`）会被当作格式非法直接拒绝（`IllegalArgumentException`），不会尝试解析。

IPv6 解析支持标准全写、`::` 压缩零段（最多一次）、结尾内嵌 IPv4 形式（如 `::ffff:192.168.1.1`），不支持区域 ID 后缀（如 `fe80::1%eth0` 中的 `%eth0`）。

## 参数校验

| 方法 | 触发 `IllegalArgumentException` 的条件 |
| --- | --- |
| `isInRange` | `ip`/`ipRange` 为 `null`/空白字符串；`ip` 不是合法字面量 IP；`ipRange` 的 CIDR 前缀长度越界、起止地址地址族不一致或起始地址大于结束地址 |
| `isInAnyRange` | 同 `isInRange` 的 `ip` 校验；`ipRanges` 为 `null`；`ipRanges` 含 `null` 元素；`ipRanges` 中任意元素格式非法 |

`isInAnyRange` 的 `ipRanges` 为空集合时返回 `false`（不抛异常）——这是合法的"没有任何规则放行"状态。

## 非目标

- 不做主机名解析。
- 不支持通配符表示法（如 `192.168.1.*`）；可以换算成等价的 CIDR 或起止地址。
- 不支持单个字符串里用逗号分隔多个范围；需要匹配多个范围请使用 `isInAnyRange`。
- 不从 `HttpServletRequest` 读取 IP；这是 `HttpRequestUtils.getClientIp` 的职责，本工具只接收已经拿到的 IP 字符串。
- 不做 IPv4-映射 IPv6（`::ffff:a.b.c.d`）规范化；如需要，先用 `HttpRequestUtils.getClientIp` 处理。

## 线程安全

本工具类无状态，所有方法均可被多线程并发调用。
