## Why

上一版 `HttpRequestSignUtils` 设计只覆盖"参数+请求体+时间戳+随机串"的签名/验签，缺少开放接口签名方案里另一个核心维度——**身份标识**：谁在调用。没有 `accessKey`，服务端无法区分不同调用方、无法按调用方分别签发/吊销密钥，也无法实现"先按身份查密钥，再验签"这个标准流程。同时，上一版把 `timestamp`/`nonce`/`signature` 放在固定请求头（`X-Timestamp`/`X-Nonce`/`X-Signature`）里，与业界常见做法——把这些值当作普通签名参数、和业务参数一起按字典序参与签名——不一致，不利于调用方按通用约定互通。本次变更据此重新设计整个签名方案。

## What Changes

本次变更是对 `add-http-request-sign-utils` 现有实现的整体重做（替换，而非追加），覆盖以下几点：

- **新增 `accessKey`**：标识调用方身份的字符串，作为签名覆盖范围的一部分（防止被篡改替换成另一个调用方），服务端据此查找对应的 `secretKey`——查找逻辑由调用方提供（本工具不持有密钥存储）。
- **统一的规范化方式**：不再使用固定多段 `\n` 拼接，改为把 `method`/`accessKey`/`timestamp`/`nonce`/请求体摘要（`bodyDigest`，body 的 SHA-256 摘要，`null`/空字符串摘要相同）这五个"机制参数"，和调用方的业务参数（`bizParams`）合并进同一个 `Map<String, String>`，按参数名 ASCII 字典序排序、键值分别百分号编码后用 `key1=value1&key2=value2...` 拼接成唯一的待签字符串，再用 `secretKey` 作为密钥计算 HMAC-SHA256、标准 Base64 编码。业务参数中如果出现与这五个机制参数或 `sign` 同名的 key，视为非法用法，直接抛出 `IllegalArgumentException`。
- **`timestamp` 改为毫秒级 Unix 时间戳**（`System.currentTimeMillis()` 风格的 13 位数字），与常见开放平台的时间戳格式一致；校验时间窗口的参数相应改名为 `maxTimestampDriftMillis`（单位毫秒），避免单位歧义。
- **新增 `signToParams`**：自动生成 `timestamp`（当前时间）和 `nonce`（随机 UUID），计算签名后以 `Map<String, String>` 返回 `accessKey`/`timestamp`/`nonce`/`sign` 四个键值对，供调用方直接合并进请求参数一起发出去，不需要调用方自己生成、保管这两个值再手动拼接。
- **核心 `sign`/`verify` 改为显式接收 `timestamp`/`nonce`**（供需要自行控制这两个值的场景，如测试、重试复用同一 `nonce` 的特殊场景），不自动生成；是 `signToParams` 内部实际调用的底层方法。
- **签名范围仍然是业务参数 `bizParams` + 请求体（通过统一算法与 `method`/`accessKey`/`timestamp`/`nonce` 一起参与签名），但 `accessKey`/`timestamp`/`nonce`/`sign` 这四项元数据固定通过 HTTP 请求头传递，业务参数 `bizParams` 仍然走查询/表单参数（`request.getParameterMap()`），不放进请求头**：
  - 新增四个公开请求头名称常量：`HEADER_ACCESS_KEY`（`accessKey`）、`HEADER_TIMESTAMP`（`timestamp`）、`HEADER_NONCE`（`nonce`）、`HEADER_SIGNATURE`（`sign`）——不加 `X-` 前缀，直接复用与签名算法内部一致的字段名。
  - `signToParams(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, String secretKey)` 自动生成 `timestamp`/`nonce`、计算签名，返回包含 `accessKey`/`timestamp`/`nonce`/`sign` 四个键值对的 `Map<String, String>`；调用方把这四个键值对**设置为对应的四个请求头**（而不是合并进查询参数），`bizParams` 仍按业务原来的方式（查询串/表单字段）发送。
  - 服务端便捷验签 `verify(HttpServletRequest request, Function<String, String> secretKeyResolver, long maxTimestampDriftMillis)`：`method` 取 `request.getMethod()`；`accessKey`/`timestamp`/`nonce`/`sign` 通过 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 四个请求头读取；`bizParams` 取自 `request.getParameterMap()`（不读取其余请求头）；`body` 读取请求体原始文本。取到 `accessKey` 后调用 `secretKeyResolver.apply(accessKey)` 换取 `secretKey`，解析不到（返回 `null`）视为验签失败。
- 不再需要"把全部请求头当业务参数"和相应的参数名大小写归一化处理（上一轮方案已废弃）：`bizParams` 的来源重新回到 `request.getParameterMap()`，其 key 的大小写和含义与查询/表单参数一致，不存在 HTTP 请求头大小写不确定的问题。
- 验签仍然不做 `nonce` 去重存储（见既有 Non-Goal，未变）；提取签名材料失败（缺请求头、格式非法、`accessKey` 无法解析、方法不支持）统一返回 `false`，不抛异常，仅 `request`/`secretKeyResolver` 为 `null` 或 `maxTimestampDriftMillis` 为负数才抛 `IllegalArgumentException`。

## Capabilities

### New Capabilities

- `http-request-sign-utilities`：定义对 HTTP 请求调用方身份、参数与请求体进行规范化、计算 HMAC-SHA256 签名及验签的行为（该能力尚未归档/同步到主规格，本次变更直接整体重写其设计，不是对已发布能力的增量修改）。

## Impact

- 重写 `src/main/java/org/example/simple/util/HttpRequestSignUtils.java`（已有的 `HEADER_*` 常量、旧版 `verify(HttpServletRequest, String, long)` 被移除，`sign`/`verify` 的参数列表变化）。
- 重写 `src/test/java/org/example/simple/util/HttpRequestSignUtilsTest.java`。
- 重写 `docs/HttpRequestSignUtils使用说明.md`。
- 不新增 Gradle 依赖（`java.security.MessageDigest` 计算 `bodyDigest`、`javax.crypto.Mac` 计算 HMAC 均为 JDK 内置 API）；不修改 `HttpRequestUtils`、`HttpResponseUtils` 及既有密码学工具（`org.example.simple.util.codec` 包）。
