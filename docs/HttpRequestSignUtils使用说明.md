# HttpRequestSignUtils 使用说明

`HttpRequestSignUtils` 位于 `org.example.simple.util`，是面向 HTTP 开放接口场景的无状态签名/验签工具类：对 `GET`/`POST`/`PUT` 请求的调用方身份（`accessKey`）、业务参数、请求体计算 HMAC-SHA256 签名，并内置基于时间窗口的防重放校验。使用 JDK 内置的 `javax.crypto.Mac`/`java.security.MessageDigest`，不新增第三方依赖。

## 签名算法

把以下五个"机制参数"和调用方的业务参数 `bizParams` 合并为同一个 `Map`：

| 机制参数 | 取值 |
| --- | --- |
| `method` | 请求方法（`GET`/`POST`/`PUT`） |
| `accessKey` | 调用方身份标识 |
| `timestamp` | 毫秒级 Unix 时间戳的十进制字符串 |
| `nonce` | 调用方生成的一次性随机串 |
| `bodyDigest` | 请求体的 SHA-256 摘要（标准 Base64）；`body` 为 `null` 时按空字符串计算，与空字符串请求体产生相同摘要 |

合并后的整个参数集合按参数名的 `String` 自然顺序排序，键值分别用 `URLEncoder` 百分号编码后以 `key=value` 形式用 `&` 连接成唯一的待签字符串，再用 `secretKey` 作为密钥计算 HMAC-SHA256，以标准 Base64 返回签名。

**业务参数不能使用 `method`/`accessKey`/`timestamp`/`nonce`/`bodyDigest`/`sign` 这几个保留名**，否则会被直接拒绝（`IllegalArgumentException`），而不是静默覆盖——这是为了避免业务参数和机制参数在签名内容或最终发送的请求参数里产生歧义。

这个算法不对外暴露，调用方不需要、也不应该自己拼接——只通过 `sign`/`signToParams`/`verify` 使用。

## API 速查

| 场景 | 方法 |
| --- | --- |
| 计算签名（显式控制 timestamp/nonce） | `sign(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, long timestamp, String nonce, String secretKey)` |
| 计算签名并自动生成元数据 | `signToParams(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, String secretKey)` → `Map<String, String>`（含 `accessKey`/`timestamp`/`nonce`/`sign`） |
| 验证签名（已知各字段） | `verify(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, long timestamp, String nonce, String secretKey, String sign, long maxTimestampDriftMillis)` |
| 验证签名（直接传请求，按 accessKey 查密钥） | `verify(HttpServletRequest request, Function<String, String> secretKeyResolver, long maxTimestampDriftMillis)` |

`HttpMethod` 枚举只包含 `GET`、`POST`、`PUT` 三个值。

## 请求头常量

`accessKey`/`timestamp`/`nonce`/`sign` 四项验签元数据固定通过以下请求头传递（业务参数不在此列，仍走查询/表单参数）：

| 常量 | 请求头名称 | 对应内容 |
| --- | --- | --- |
| `HEADER_ACCESS_KEY` | `accessKey` | 调用方身份标识 |
| `HEADER_TIMESTAMP` | `timestamp` | 毫秒级 Unix 时间戳 |
| `HEADER_NONCE` | `nonce` | 一次性随机串 |
| `HEADER_SIGNATURE` | `sign` | 签名结果（标准 Base64） |

## 基本用法

```java
import org.example.simple.util.HttpRequestSignUtils;
import org.example.simple.util.HttpRequestSignUtils.HttpMethod;

// 客户端：业务参数 + accessKey/secretKey 即可，不用自己生成 timestamp/nonce
Map<String, String> bizParams = Map.of("userId", "42", "page", "1");

Map<String, String> metadata = HttpRequestSignUtils.signToParams(HttpMethod.GET, accessKey, bizParams, null, secretKey);
// metadata = {accessKey=..., timestamp=..., nonce=..., sign=...}

// bizParams 仍按原来的方式发出去（查询串/表单字段）；metadata 的四项设置为对应请求头，不要合并进查询参数
httpRequest.setHeader(HttpRequestSignUtils.HEADER_ACCESS_KEY, metadata.get(HttpRequestSignUtils.HEADER_ACCESS_KEY));
httpRequest.setHeader(HttpRequestSignUtils.HEADER_TIMESTAMP, metadata.get(HttpRequestSignUtils.HEADER_TIMESTAMP));
httpRequest.setHeader(HttpRequestSignUtils.HEADER_NONCE, metadata.get(HttpRequestSignUtils.HEADER_NONCE));
httpRequest.setHeader(HttpRequestSignUtils.HEADER_SIGNATURE, metadata.get(HttpRequestSignUtils.HEADER_SIGNATURE));
```

```java
// 服务端：已知各字段时验证（适合单元测试或自己手动拆参数的场景）
boolean valid = HttpRequestSignUtils.verify(HttpMethod.GET, accessKey, bizParams, null,
        Long.parseLong(metadata.get("timestamp")), metadata.get("nonce"), secretKey, metadata.get("sign"),
        300_000); // 允许 ±300000 毫秒（5 分钟）时钟偏差
```

```java
// 服务端：直接从 HttpServletRequest 验证，按 accessKey 动态查 secretKey（过滤器/拦截器场景）
boolean valid = HttpRequestSignUtils.verify(httpServletRequest,
        accessKey -> accessKeyStore.findSecretKeyByAccessKey(accessKey), // 返回 null 表示未知调用方
        300_000);
```

## 从 HttpServletRequest 直接验证

`verify(HttpServletRequest, Function<String, String>, long)` 自动从请求中提取全部签名材料，`accessKey`/`timestamp`/`nonce`/`sign` 走请求头，业务参数仍走查询/表单参数：

| 签名材料 | 提取方式 |
| --- | --- |
| `method` | `request.getMethod()`，不区分大小写解析为 `HttpMethod` |
| `accessKey`/`timestamp`/`nonce`/`sign` | `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 四个固定请求头的值 |
| `bizParams` | `request.getParameterMap()`（查询/表单参数），每个参数只取第一个值（不支持多值参数） |
| `body` | 读取请求体原始文本 |
| `secretKey` | 调用 `secretKeyResolver.apply(accessKey)` 解析得到 |

**提取或解析失败统一返回 `false`，不抛异常**：请求方法不是 `GET`/`POST`/`PUT`、缺失上述任一请求头、`timestamp` 不是合法十进制数字、`sign` 不是合法 Base64、`secretKeyResolver` 对给定 `accessKey` 返回 `null`（未知或已吊销的调用方）——这些情况全部来自客户端可控的请求内容或业务侧的密钥管理结果，缺失或畸形本身就代表"这不是一个可以通过验签的请求"。只有 `request`/`secretKeyResolver` 为 `null`，或 `maxTimestampDriftMillis` 为负数这类调用方自身的编程错误才抛出 `IllegalArgumentException`。

**限制：本方法只能读取一次请求体**。以下两种情况会导致读到空的请求体，进而验签失败：

- 调用方在本方法之前已经读取过请求体（如某个日志过滤器打印了请求体）。
- 请求的 `Content-Type` 是 `application/x-www-form-urlencoded`：Servlet 容器会在调用 `getParameterMap()` 时提前消费请求体输入流解析为参数（对纯表单请求而言，业务参数本身就已经在 `getParameterMap()` 里拿到了，通常不需要再额外签一份 JSON 请求体）。

读取请求体过程中发生 `IOException`（如连接中断）会包装为 `IllegalStateException` 抛出，不归为验签失败。

## 身份与密钥管理：accessKey / secretKey

- `accessKey` 标识调用方身份，纳入签名范围（不可被篡改替换成另一个调用方）。
- `secretKey` 按原始 UTF-8 字符串处理，不要求 Base64（这类密钥通常是业务侧按 `accessKey` 签发给调用方的密钥字符串）。
- `accessKey` → `secretKey` 的查找、存储、吊销由调用方自行负责，本工具不持有任何密钥存储；`verify(HttpServletRequest, ...)` 用 `Function<String, String>` 表达这个查找动作，可以直接用方法引用接入既有存储（如 `accessKeyStore::findSecretKeyByAccessKey`）。
- 验签使用 `MessageDigest.isEqual` 做常数时间比较，不会因为提前发现不同字节就提前返回。

## 防重放：时间窗口 + nonce

- **时间窗口**：`verify` 先检查 `|当前时间 - timestamp|`（毫秒）是否超过 `maxTimestampDriftMillis`，超出直接判定验签失败。该值由调用方按场景传入（内部系统可以收紧到几十秒，开放给第三方的接口可能需要放宽到 5 分钟），工具不内置默认值。时间戳超窗和签名内容不匹配返回相同的 `false`，不向调用方区分具体原因。
- **nonce 去重由调用方自行实现**：本工具保证 `nonce` 参与签名、不可被篡改，但不做"这个 nonce 是否已经出现过"的去重存储——这需要一个带 TTL 的共享存储（Redis/数据库），超出无状态静态工具类的职责边界。典型做法是验签通过后，用 Redis `SETNX nonce:{nonce} 1 EX {windowSeconds}` 之类的操作判断并记录；`SETNX` 返回失败即认为是重复提交，拒绝该请求。

## 参数校验

| 方法 | 触发 `IllegalArgumentException` 的条件 |
| --- | --- |
| `sign` | `method`/`bizParams`/`secretKey` 为 `null`；`accessKey`/`secretKey`/`nonce` 为 `null`/空白字符串；`bizParams` 含 `null` 键/值或与机制参数/`sign` 同名的 key |
| `signToParams` | 同 `sign`（不含 `timestamp`/`nonce` 相关校验，因为由本方法生成） |
| `verify(HttpMethod, ...)` | 同 `sign`，另加 `sign` 为 `null`/空白字符串或不是合法 Base64；`maxTimestampDriftMillis` 为负数 |
| `verify(HttpServletRequest, ...)` | `request`/`secretKeyResolver` 为 `null`；`maxTimestampDriftMillis` 为负数 |

## 非目标

- 不支持除 HMAC-SHA256 外的其他签名算法。
- 不支持数组/多值参数；`bizParams` 固定是单值 `Map<String, String>`。
- 不提供可重复读取请求体的包装类；不支持表单编码请求体与 JSON 请求体同时存在的场景。
- 不做 nonce 去重存储，只保证其不可被篡改。
- 不提供 `accessKey` → `secretKey` 的存储或管理实现。
