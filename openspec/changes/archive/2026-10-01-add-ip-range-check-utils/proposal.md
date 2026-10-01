## Why

项目已有 `HttpRequestUtils.getClientIp` 解析访问者真实 IP，但没有工具判断"这个 IP 是否落在指定的 IP 范围/IP 段内"——这是访问控制（IP 白名单/黑名单、内网网段放行、按地区/机房网段限流等）场景的高频需求。各处手写 CIDR 前缀长度计算、起止地址比较容易在边界值（网络地址、广播地址、IPv6 与 IPv4 混用）上出错。需要一个统一、职责单一的工具类负责 IP 范围的解析与归属判断。

## What Changes

- 新增 `org.example.simple.util.IpRangeCheckUtils` 静态工具类（`final` + 私有构造器），与 `HttpRequestUtils`/`HttpResponseUtils`/`HttpRequestSignUtils` 同级，仅使用 JDK 内置的 `java.net.InetAddress`，不引入新的第三方依赖。
- 支持两种 IP 范围表达式格式，统一通过字符串传入：
  - **CIDR 表示法**：如 `192.168.1.0/24`、`2001:db8::/32`。
  - **起止地址表示法**：如 `192.168.1.1-192.168.1.100`（用英文连字符分隔起止地址，均为同一地址族）。
  - 不是以上两种格式、本身就是一个单独 IP 地址的字符串（如 `192.168.1.1`），按"只包含这一个地址的范围"处理（等价于起止地址都是它自己）。
- 同时支持 IPv4 和 IPv6：底层基于 `InetAddress` 解析为字节数组做数值比较，CIDR 前缀长度上限按地址族自动区分（IPv4 最大 32、IPv6 最大 128）；IP 范围表达式与待检查 IP 的地址族不一致（如用 IPv4 CIDR 检查一个 IPv6 地址）时直接判定为不属于（返回 `false`），不抛异常。
- 提供 `boolean isInRange(String ip, String ipRange)`：判断单个 `ip` 是否落在单个 `ipRange` 表达式描述的范围内。
- 提供 `boolean isInAnyRange(String ip, Collection<String> ipRanges)`：判断 `ip` 是否落在 `ipRanges` 中任意一个范围内（典型用于白名单包含多条网段/地址的场景）。
- `ip` 或 `ipRange`/`ipRanges` 为 `null`/空白/格式非法（不是合法 IP、不是合法 CIDR、起止地址格式错误、起始地址大于结束地址等）时抛出 `IllegalArgumentException`——范围表达式本身是否合法属于调用方配置是否正确的问题，与"IP 是否落在合法范围内"这一业务判断结果（`true`/`false`）需要区分开。

## Capabilities

### New Capabilities

- `ip-range-check-utilities`：定义解析 CIDR/起止地址两种 IP 范围表达式，并判断指定 IP 是否属于该范围（或多个范围中任意一个）的行为。

### Modified Capabilities

无。不修改 `http-request-utilities` 等既有能力。

## Impact

- 新增 `src/main/java/org/example/simple/util/IpRangeCheckUtils.java`。
- 新增 `src/test/java/org/example/simple/util/IpRangeCheckUtilsTest.java`。
- 不新增 Gradle 依赖（`java.net.InetAddress` 为 JDK 内置 API）；不修改 `HttpRequestUtils` 等既有工具类。
