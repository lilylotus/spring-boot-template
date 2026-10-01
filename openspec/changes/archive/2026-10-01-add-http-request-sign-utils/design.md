## Context

本变更是对同一个 `add-http-request-sign-utils` 变更的第三次调整，取代其上一版已经实现并通过测试的内容（代码尚未归档，可以直接整体替换，不需要走"先废弃再新增"的兼容路径）。核心演进路径：第一版只有参数/请求体/时间戳/随机串；第二版加入"调用方身份标识"（`accessKey`）并统一了签名算法；中途曾短暂考虑把全部参数（含业务参数）都放进请求头，但那个方案对中间代理过于敏感（任何代理新增/删除请求头都会被当作篡改）。本次定稿为：**业务参数 `bizParams` 和请求体仍按原来的方式传递（查询/表单参数、请求体），只有 `accessKey`/`timestamp`/`nonce`/`sign` 这四项验签元数据固定通过 HTTP 请求头传递**，兼顾"元数据和业务数据分离、不依赖自定义请求头之外的额外约定"与"不把整个请求头集合都纳入签名范围"两个诉求。

`org.example.simple.util.codec` 包已有 `DigestUtils.sha256`（SHA-256 摘要，Base64 返回）可直接复用其算法思路（但 `codec` 包的 `CryptoSupport`/`DigestUtils` 均为包私有或仅服务包内其他类，无法跨包直接调用私有的 `CryptoSupport` 校验方法；本类自行用 `MessageDigest.getInstance("SHA-256")` 实现一份独立的最小摘要逻辑，与 `HttpRequestUtils`/`HttpResponseUtils` 当前"各自独立、不跨类共享内部校验逻辑"的既有做法一致）。

## Goals / Non-Goals

**Goals:**
- 签名覆盖范围包含调用方身份（`accessKey`）、请求方法、业务参数、请求体、时间戳、随机串，任一被篡改都会导致验签失败。
- 把所有参与签名的"机制参数"（`method`/`accessKey`/`timestamp`/`nonce`/`bodyDigest`）和业务参数统一到同一个排序规则下，签名算法只有一套逻辑，不区分"固定段"和"普通参数"两类特殊处理。
- 提供"自动生成 `timestamp`/`nonce` 并以 `Map` 返回全部待发送元数据"的便捷签名入口，减少调用方样板代码。
- 提供"按 `accessKey` 动态解析 `secretKey`"的服务端便捷验签入口，贴合"一个服务对接多个调用方、每个调用方一对 `accessKey`/`secretKey`"的真实场景。
- 不引入新的第三方依赖，复用 JDK 内置 `javax.crypto`/`java.security.MessageDigest`。

**Non-Goals:**
- 不提供 `accessKey` → `secretKey` 的存储或查找实现；这是业务方的密钥管理职责（数据库、配置中心、密钥管理服务等），本工具只定义一个函数式接口让调用方接入自己的查找逻辑。
- 不做 `nonce` 去重存储；原因与上一版相同——需要带 TTL 的共享存储，超出无状态静态工具类的职责边界。`nonce` 依然参与签名、不可被篡改，调用方按需自行接入去重存储配合时间窗口校验使用。
- 不支持数组/多值业务参数；`bizParams` 固定是 `Map<String, String>`。
- 不提供可重复读取请求体的包装类；不支持 `Content-Type: application/x-www-form-urlencoded` 的请求体读取（与上一版相同的 Servlet API 限制）。
- 不支持除 HMAC-SHA256 外的其他签名算法。
- 不把业务参数放进请求头，也不读取除 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 外的其他请求头；`bizParams` 固定来自 `request.getParameterMap()`。

## Decisions

- **统一的"全参数排序签名"算法，取代固定多段拼接**：把 `method`（请求方法字符串）、`accessKey`、`timestamp`（毫秒级时间戳的十进制字符串）、`nonce`、`bodyDigest`（请求体的 SHA-256 摘要，标准 Base64；`body` 为 `null` 时按空字符串计算摘要，保证 `null` 与 `""` 产生相同签名，延续上一版"两者等价"的既有约定）这五个"机制参数"，与调用方传入的 `bizParams` 合并进同一个 `Map<String, String>`，统一按参数名的 `String` 自然顺序（ASCII/Unicode 码点）排序，键值分别用 `URLEncoder` 百分号编码后以 `key=value` 形式用 `&` 连接成唯一的待签字符串。
  - 相比上一版"固定分段 + `\n` 连接"的做法，这里只有一条规则、一种数据结构，不需要再单独记忆"第几段是什么"；也与用户描述的典型签名算法（"所有业务参数按 key 字典序排序拼接"）直接对应，`accessKey`/`timestamp`/`nonce` 不过是这个排序集合里固定会出现的几个 key，不需要特殊化处理。
  - `bodyDigest` 用摘要而不是原始请求体拼接：这样"请求体"也能像其他参数一样被统一地排进同一个 Map、参与同一套排序编码逻辑，不需要在算法里为"最后一段是 body 原文、不编码"这件事单独开一个例外分支；代价是验签方需要重新对收到的请求体计算一次 SHA-256，计算量可忽略不计。
  - 业务参数的 key 如果与 `method`/`accessKey`/`timestamp`/`nonce`/`bodyDigest` 或最终发送时使用的 `sign` 字段重名，视为调用方用法错误，直接抛出 `IllegalArgumentException`，而不是静默覆盖——重名意味着业务参数要么会被机制参数的值覆盖（签名内容和调用方预期不符），要么会在调用方把 `signToParams` 的返回结果与业务参数合并发送时在请求层面互相覆盖，这两种情况都应该在开发阶段尽早暴露，而不是留到联调时才发现签名对不上。
- **`timestamp` 改为毫秒级 Unix 时间戳，窗口参数改名为 `maxTimestampDriftMillis`**：毫秒级时间戳是 `System.currentTimeMillis()` 的原生单位，也是大多数开放平台实际采用的格式；把窗口参数也统一到毫秒单位，避免"时间戳是毫秒、窗口是秒"这种容易被调用方搞混、算出偏差 1000 倍的常见错误。
- **新增 `signToParams`，自动生成 `timestamp`/`nonce` 并以 `Map<String, String>` 返回 `accessKey`/`timestamp`/`nonce`/`sign`**：这是最贴近实际调用方代码的入口——调用方只需要准备 `accessKey`/业务参数/请求体/`secretKey`，拿到返回的 `Map` 后直接 `putAll` 进自己的请求参数一起发出去，不需要自己生成随机数、记录当前时间再手动拼 `sign`。底层仍然通过显式接收 `timestamp`/`nonce` 的核心 `sign` 方法实现，保留后者供需要自行控制这两个值的场景使用（如测试里需要构造固定输入、特殊场景下需要复用同一个 `nonce` 重新计算签名）。
- **服务端便捷验签改为按 `accessKey` 动态解析 `secretKey`，签名改为 `verify(HttpServletRequest request, Function<String, String> secretKeyResolver, long maxTimestampDriftMillis)`**：真实场景下一个服务端点要接受多个不同调用方的请求，每个调用方有自己的一对 `accessKey`/`secretKey`，服务端不可能在调用验签之前就知道该用哪个 `secretKey`——必须先从请求里取出 `accessKey`，再查出对应的 `secretKey`。用 `java.util.function.Function<String, String>` 表达"查找"这个动作，调用方可以用方法引用接入任意存储（`accessKeyStore::findSecretKeyByAccessKey`），本工具不关心查找的具体实现；解析不到（`resolver` 返回 `null`）按验签失败处理，返回 `false`。
- **只有 `accessKey`/`timestamp`/`nonce`/`sign` 四项元数据走请求头，`bizParams` 仍走查询/表单参数**：新增 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 四个固定请求头名称常量，取值直接是 `accessKey`/`timestamp`/`nonce`/`sign`（不加 `X-` 前缀），与签名算法内部使用的字段名一致，调用方不需要在"请求头名称"和"字段名"之间做心理翻译。相比"全部参数都走请求头"的方案，这个折中避免了两个问题：一是业务参数经过请求头传递时，中间代理/网关新增或删除无关请求头会被误判为篡改（见 Risks）；二是业务参数不需要逐个转成请求头，调用方原有的"用查询串/表单字段传业务参数"的使用习惯不需要改变，只是额外加四个请求头承载验签元数据。`signToParams` 的职责不变（生成并返回这四项，供调用方设置为请求头），不再需要一个额外的"把 `bizParams` 也转成请求头"的方法。
- **`verify(HttpServletRequest, ...)` 的 `bizParams` 固定来自 `request.getParameterMap()`，不读取其余请求头**：由于业务参数不走请求头，不存在"要不要把全部请求头当业务参数"的问题，也就不需要为"HTTP 请求头大小写不确定"引入参数名归一化处理——`bizParams` 的 key 就是查询/表单参数名，和调用方签名时传入 `sign`/`signToParams` 的 `bizParams` key 大小写一一对应，无需做任何大小写转换。
- **提取失败（缺参数、格式非法、方法不支持、`accessKey` 无法解析）统一返回 `false`，只有 `request`/`secretKeyResolver` 为 `null` 或 `maxTimestampDriftMillis` 为负数抛 `IllegalArgumentException`**：延续上一版"来自客户端可控内容的问题视为验签失败，调用方自身的编程错误才抛异常"的既有原则；`accessKey` 无法解析成 `secretKey`（陌生/被吊销的调用方）属于前者，不应该让服务端代码为此写 `try-catch`。

## Risks / Trade-offs

- **这是一次破坏性重做，不是增量变更**：上一轮已经实现、测试、写过文档的 `HttpRequestSignUtils`（含基于查询/表单参数的 `verify(HttpServletRequest, ...)`）会被整体替换；如果已经有代码在用上一版的 API，需要同步改造。由于该变更尚未归档、尚未对外发布为"已完成的能力"，本次视为设计阶段的调整而不是对已发布 API 的 breaking change。
- **只把四个固定请求头纳入验签范围，中间代理新增/删除其他请求头不受影响**：相比"签名覆盖全部请求头"的方案，本设计只要求中间代理不修改 `HEADER_ACCESS_KEY`/`HEADER_TIMESTAMP`/`HEADER_NONCE`/`HEADER_SIGNATURE` 这四个固定请求头，风险范围显著收窄；但如果确实存在会篡改或丢弃自定义请求头的代理/网关，仍然会导致验签失败，这是基于请求头传递元数据的方案的通用限制。
- **`secretKeyResolver` 的查找性能/可用性由调用方负责**：如果调用方的解析逻辑本身很慢（如每次都查远程数据库无缓存）或会抛出未捕获异常，会直接影响 `verify(HttpServletRequest, ...)` 的调用方；本工具不对 `resolver` 的实现做任何约束或超时控制。
- **`bodyDigest` 要求验签方完整读到与签名方完全一致的请求体字节**：任何中间代理对请求体做的修改（哪怕是无意义的空白调整）都会导致摘要不同、验签失败，这是"对请求体做摘要参与签名"方案的通用特性，不是本工具独有的限制。
- **服务端与客户端的时钟必须大致同步**（与上一版相同的既有限制，窗口单位改为毫秒）。
