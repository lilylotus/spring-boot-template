# Spec Delta

## Purpose
为 Java 代码提供对 HTTP 请求的调用方身份、参数与请求体进行规范化、计算 HMAC-SHA256 签名及验签的统一静态工具，支持 GET/POST/PUT 请求，并内置基于时间窗口的防重放校验。

## ADDED Requirements

### Requirement: 计算请求签名
系统 SHALL 提供 `HttpRequestSignUtils.sign(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, long timestamp, String nonce, String secretKey)`。系统 SHALL 把 `method`、`accessKey`、`timestamp`、`nonce`、请求体的 SHA-256 摘要（`body` 为 `null` 时按空字符串计算）这五项与 `bizParams` 合并为一个参数集合，按参数名升序排序，并逐个百分号编码后以 `key=value` 形式用 `&` 连接为待签字符串，使用 `secretKey` 作为密钥计算 HMAC-SHA256，并以标准 Base64 返回签名。

#### Scenario: 相同输入产生相同签名
- **WHEN** 使用相同的 `method`、`accessKey`、`bizParams`、`body`、`timestamp`、`nonce` 和 `secretKey` 两次调用 `sign`
- **THEN** 两次返回的签名完全相同

#### Scenario: 业务参数顺序不影响签名结果
- **WHEN** 使用内容相同但键值对插入顺序不同的两个 `bizParams` 调用 `sign`，其余参数相同
- **THEN** 两次返回的签名完全相同

#### Scenario: 不同方法产生不同签名
- **WHEN** 使用相同的 `accessKey`、`bizParams`、`body`、`timestamp`、`nonce`、`secretKey`，但 `method` 分别为 `GET` 和 `POST` 调用 `sign`
- **THEN** 两次返回的签名不同

#### Scenario: 不同调用方身份产生不同签名
- **WHEN** 使用相同的 `method`、`bizParams`、`body`、`timestamp`、`nonce`、`secretKey`，但 `accessKey` 不同调用 `sign`
- **THEN** 两次返回的签名不同

#### Scenario: 请求体参与签名
- **WHEN** 使用相同的 `method`、`accessKey`、`bizParams`、`timestamp`、`nonce`、`secretKey`，但 `body` 不同（包括一个为 `null`）调用 `sign`
- **THEN** 两次返回的签名不同，除非两个 `body` 分别是 `null` 和空字符串

#### Scenario: 时间戳与随机数参与签名
- **WHEN** 使用相同的 `method`、`accessKey`、`bizParams`、`body`、`secretKey`，但 `timestamp` 或 `nonce` 不同调用 `sign`
- **THEN** 两次返回的签名不同

#### Scenario: 业务参数与机制参数重名
- **WHEN** `bizParams` 中存在键名为 `method`/`accessKey`/`timestamp`/`nonce`/`bodyDigest`/`sign` 之一的条目
- **THEN** 方法抛出 `IllegalArgumentException`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `method`/`accessKey`/`bizParams`/`secretKey` 为 `null`，`accessKey`/`secretKey`/`nonce` 为 `null`/空白字符串，或 `bizParams` 中存在 `null` 键或 `null` 值
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 自动生成时间戳与随机数并返回签名参数
系统 SHALL 提供 `HttpRequestSignUtils.signToParams(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, String secretKey)`，内部生成当前毫秒级 Unix 时间戳和随机 `nonce`，调用与 `sign` 相同的规则计算签名，并返回包含 `accessKey`、`timestamp`、`nonce`、`sign` 四个键值对的 `Map<String, String>`。

#### Scenario: 返回值包含全部待发送元数据
- **WHEN** 调用方调用 `signToParams`
- **THEN** 返回的 `Map` 包含 `accessKey`、`timestamp`、`nonce`、`sign` 四个键，且用返回的 `timestamp`/`nonce`/`sign` 和原始输入调用 `verify` 返回 `true`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `method`/`accessKey`/`bizParams`/`secretKey` 为 `null`，`accessKey`/`secretKey` 为 `null`/空白字符串，或 `bizParams` 中存在 `null` 键、`null` 值或与机制参数重名的键
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 验证请求签名与时间窗口
系统 SHALL 提供 `HttpRequestSignUtils.verify(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, long timestamp, String nonce, String secretKey, String sign, long maxTimestampDriftMillis)`。系统 SHALL 先校验 `timestamp` 与当前时间（毫秒）的差值是否不超过 `maxTimestampDriftMillis`，超出范围直接判定验签失败；范围内则按 `sign` 方法同样的规则重新计算签名，并与传入的 `sign` 做常数时间比较。时间窗口校验失败与签名内容不匹配 SHALL 产生相同的失败结果。

#### Scenario: 验签成功
- **WHEN** 调用方使用 `sign` 对一组 `method`/`accessKey`/`bizParams`/`body`/`timestamp`/`nonce`/`secretKey` 计算出的签名，在允许的时间窗口内原样传给 `verify` 并使用相同的输入
- **THEN** `verify` 返回 `true`

#### Scenario: 身份、参数或请求体被篡改导致验签失败
- **WHEN** `verify` 时的 `accessKey`/`bizParams`/`body` 与签名时不同，其余不变，且仍在时间窗口内
- **THEN** `verify` 返回 `false`

#### Scenario: 密钥不匹配导致验签失败
- **WHEN** `verify` 时的 `secretKey` 与签名时不同，其余不变，且仍在时间窗口内
- **THEN** `verify` 返回 `false`

#### Scenario: 时间戳超出允许窗口导致验签失败
- **WHEN** `timestamp` 与当前时间的差值（毫秒）超过 `maxTimestampDriftMillis`，即便其余参数与签名均正确
- **THEN** `verify` 返回 `false`

#### Scenario: 签名格式非法
- **WHEN** 调用方传入的 `sign` 不是合法的标准 Base64 字符串
- **THEN** 方法抛出 `IllegalArgumentException`

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `method`/`accessKey`/`bizParams`/`secretKey` 为 `null`，`accessKey`/`secretKey`/`nonce`/`sign` 为 `null`/空白字符串，`bizParams` 中存在 `null` 键、`null` 值或与机制参数重名的键，或 `maxTimestampDriftMillis` 为负数
- **THEN** 方法抛出 `IllegalArgumentException`

### Requirement: 从 HttpServletRequest 直接验证请求签名
系统 SHALL 提供便捷重载 `HttpRequestSignUtils.verify(HttpServletRequest request, Function<String, String> secretKeyResolver, long maxTimestampDriftMillis)`，从 `request` 中提取 `method`（`request.getMethod()`）、`accessKey`/`timestamp`/`nonce`/`sign`（`HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 对应请求头的值）、`bizParams`（`request.getParameterMap()`）与请求体文本，调用 `secretKeyResolver.apply(accessKey)` 解析 `secretKey` 后按与 `verify(HttpMethod, ...)` 相同的规则完成验签。

#### Scenario: 请求验签成功
- **WHEN** 请求的方法、请求头（`HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 是调用 `signToParams` 并按约定设置为请求头的结果）、查询/表单参数（`bizParams`）、请求体均与签名时一致，且在时间窗口内，`secretKeyResolver` 能为对应 `accessKey` 解析出正确的 `secretKey`
- **THEN** `verify(request, secretKeyResolver, maxTimestampDriftMillis)` 返回 `true`

#### Scenario: 提取或解析签名材料失败返回 false
- **WHEN** 请求的方法不是 `GET`/`POST`/`PUT`，缺失/格式非法 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 任一请求头，或 `secretKeyResolver.apply(accessKey)` 返回 `null`
- **THEN** 方法返回 `false`，不抛出异常

#### Scenario: 参数非法
- **WHEN** 调用方传入的 `request`/`secretKeyResolver` 为 `null`，或 `maxTimestampDriftMillis` 为负数
- **THEN** 方法抛出 `IllegalArgumentException`
