# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/http-request-sign-utilities/spec.md` 的明确确认，尤其是：只有 `accessKey`/`timestamp`/`nonce`/`sign` 四项元数据走请求头（`HEADER_*` 常量，不加 `X-` 前缀），`bizParams` 仍走 `request.getParameterMap()`/调用方自行传入；`signToParams` 返回的四项供调用方设置为请求头；不再有 `signToHeaders`、不再有参数名大小写归一化。

## 2. 实现

- [x] 2.1 重写 `org.example.simple.util.HttpRequestSignUtils`（`final` + 私有构造器）。
- [x] 2.2 保留枚举 `HttpMethod { GET, POST, PUT }`。
- [x] 2.3 新增公开常量 `HEADER_ACCESS_KEY = "accessKey"`、`HEADER_TIMESTAMP = "timestamp"`、`HEADER_NONCE = "nonce"`、`HEADER_SIGNATURE = "sign"`（不加 `X-` 前缀）；新增私有静态常量：机制参数名 `method`、`accessKey`、`timestamp`、`nonce`、`bodyDigest`，以及 `sign`（用于重名校验，不参与签名内容本身）。
- [x] 2.4 实现待签字符串构建的私有方法：校验 `bizParams` 不含 `null` 键/值、不含与机制参数或 `sign` 重名的 key（任一违反抛 `IllegalArgumentException`）；构造合并 Map（`bizParams` + `method`/`accessKey`/`timestamp`（十进制字符串）/`nonce`/`bodyDigest`（`body` 为 `null` 时按空字符串计算 SHA-256，标准 Base64）五项）；按 key 自然顺序排序，键值分别 `URLEncoder.encode(value, StandardCharsets.UTF_8)` 编码后以 `key=value` 形式用 `&` 连接，返回最终待签字符串。
- [x] 2.5 实现 `sign(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, long timestamp, String nonce, String secretKey)`：校验 `method`/`accessKey`/`bizParams`/`secretKey` 非 `null`，`accessKey`/`secretKey`/`nonce` 非空白；用 2.4 的待签字符串和 `secretKey`（UTF-8 字节）通过 `javax.crypto.Mac`（`HmacSHA256`）计算 HMAC，返回标准 Base64 签名。
- [x] 2.6 实现 `signToParams(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, String secretKey)`：生成 `timestamp = System.currentTimeMillis()`、`nonce = UUID.randomUUID().toString()`，调用 2.5 的 `sign` 计算签名，返回包含 `accessKey`/`timestamp`/`nonce`/`sign` 四个键值对的不可变 `Map<String, String>`（调用方把这四项设置为 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 四个请求头）。
- [x] 2.7 实现 `verify(HttpMethod method, String accessKey, Map<String, String> bizParams, String body, long timestamp, String nonce, String secretKey, String sign, long maxTimestampDriftMillis)`：复用 2.5 的参数校验；额外对 `sign` 为 `null`/空白字符串、`maxTimestampDriftMillis` 为负数抛 `IllegalArgumentException`；`sign` 不是合法标准 Base64 时抛 `IllegalArgumentException`；先比较 `Math.abs(当前毫秒 - timestamp)` 与 `maxTimestampDriftMillis`，超出直接返回 `false`；未超出则调用 `sign` 方法重新计算并用 `MessageDigest.isEqual` 常数时间比较。
- [x] 2.8 实现便捷重载 `verify(HttpServletRequest request, Function<String, String> secretKeyResolver, long maxTimestampDriftMillis)`：`request`/`secretKeyResolver` 为 `null`，或 `maxTimestampDriftMillis` 为负数抛 `IllegalArgumentException`；解析 `request.getMethod()` 失败（非 GET/POST/PUT）返回 `false`；通过 `request.getHeader(HEADER_ACCESS_KEY)`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 取出 `accessKey`/`timestamp`/`nonce`/`sign`（缺失/空白任一返回 `false`，`timestamp` 非十进制数字返回 `false`，`sign` 非法 Base64 返回 `false`）；`bizParams` 从 `request.getParameterMap()` 构造（每个 key 取 `String[]` 第一个元素）；调用 `secretKeyResolver.apply(accessKey)`，返回 `null` 则整体返回 `false`；读取请求体（`IOException` 包装为 `IllegalStateException`）；调用 2.7 的 `verify` 完成验签。
- [x] 2.9 为全部公开类型、常量与方法补充中文 Javadoc：说明统一签名算法（含 `bodyDigest` 折叠请求体的原因）、机制参数重名校验、`timestamp`/`maxTimestampDriftMillis` 的毫秒单位、`signToParams` 返回值用于设置请求头、`accessKey`/`timestamp`/`nonce`/`sign` 走请求头而 `bizParams` 走查询/表单参数的分工、`secretKeyResolver` 的职责边界、提取/解析失败统一返回 `false` 与编程错误抛异常的区分、nonce 去重仍需调用方自行实现、线程安全性。

## 3. 测试

- [x] 3.1 重写 `HttpRequestSignUtilsTest`。
- [x] 3.2 测试 `sign`：相同输入两次调用结果相同；`bizParams` 插入顺序不同但内容相同结果相同；`method`/`accessKey`/`body`/`timestamp`/`nonce`/`secretKey` 任一不同时结果不同；`body` 为 `null` 与 `""` 产生相同签名；`bizParams` 含与机制参数或 `sign` 重名的 key 时抛 `IllegalArgumentException`；`method`/`accessKey`/`bizParams`/`secretKey` 为 `null`，`accessKey`/`secretKey`/`nonce` 为空白，或 `bizParams` 含 `null` 键/值时抛 `IllegalArgumentException`。
- [x] 3.3 测试 `signToParams`：返回的 `Map` 含 `accessKey`/`timestamp`/`nonce`/`sign` 四个键；用返回值和原始输入调用 `verify` 返回 `true`；非法输入场景与 `sign` 对齐。
- [x] 3.4 测试 `verify(HttpMethod, ...)`：用 `sign`/`signToParams` 生成的签名回代验证返回 `true`；篡改 `accessKey`/`bizParams`/`body`/`secretKey` 后返回 `false`；`timestamp` 超出 `maxTimestampDriftMillis` 窗口返回 `false`（窗口边界场景留出真实耗时余量，不卡精确临界值）；`sign` 非法 Base64 抛 `IllegalArgumentException`；`maxTimestampDriftMillis` 为负数抛 `IllegalArgumentException`；其余参数非法场景覆盖 `sign` 的全部用例。
- [x] 3.5 补充 GET（无 body，`bizParams` 非空）、POST（`bizParams` 为空、`body` 为 JSON 字符串）、PUT（`bizParams` 和 `body` 都非空）三种方法的端到端 `signToParams`（元数据设置为请求头）+ `verify(HttpServletRequest, ...)` 场景。
- [x] 3.6 新增 `verify(HttpServletRequest, Function, long)` 测试，复用 `java.lang.reflect.Proxy` 测试替身构造 `HttpServletRequest`（提供 `getMethod`/`getHeader`/`getParameterMap`/`getReader`）：四个请求头 + `bizParams`（通过 `getParameterMap()`）+ body 与某次 `signToParams` 结果一致时返回 `true`；方法不支持、缺失 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 任一请求头、`timestamp` 非数字、`sign` 非法 Base64、`secretKeyResolver` 对给定 `accessKey` 返回 `null` 时均返回 `false`；`request`/`secretKeyResolver` 为 `null`、`maxTimestampDriftMillis` 为负数抛 `IllegalArgumentException`；请求体读取抛 `IOException` 时包装为 `IllegalStateException`。

## 4. 文档与验证

- [x] 4.1 重写 `docs/HttpRequestSignUtils使用说明.md`：覆盖统一签名算法说明（含 `bodyDigest`）、`sign`/`signToParams`/核心 `verify`/便捷 `verify(HttpServletRequest, ...)` 示例（GET/POST/PUT 各一个，说明四个请求头 + `bizParams` 走查询/表单参数的分工）、四个请求头常量的用途、`accessKey`/`secretKey` 的身份管理职责提示、`secretKeyResolver` 用法示例、时间窗口防重放（毫秒单位）与取值建议、nonce 去重存储需调用方自行实现的说明、参数校验规则；`README.md` 已有的入口链接无需改动（文件路径不变）。
- [x] 4.2 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.3 运行 `openspec validate add-http-request-sign-utils --strict`，核对代码、测试、文档与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.util.HttpRequestSignUtilsTest"`：31 个测试全部通过。
- `gradlew.bat build`（全量）：`BUILD SUCCESSFUL`，无新增编译或弃用警告。
- `openspec validate add-http-request-sign-utils --strict`：`Change 'add-http-request-sign-utils' is valid`。
