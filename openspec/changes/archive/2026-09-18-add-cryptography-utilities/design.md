## Context

项目使用 Java 21 和 Gradle，目前仅有 Commons Codec 等通用依赖，没有可提供完整国密算法的密码学 Provider，也没有 `org.example.simple.util` 包。参见 `proposal.md` 的动机与 `specs/cryptography-utilities/spec.md` 的行为约束。本设计需要同时解决安全默认值、跨 Provider 互操作、长消息处理和统一异常语义。

## Goals / Non-Goals

**Goals:**

- 提供无状态、线程安全、仅含静态 API 的五个最终工具类。
- 让调用方只处理 UTF-8 文本和标准 Base64 字符串，不直接操作 Provider 专用对象。
- 选择具备机密性与完整性保护的对称加密模式，并固定密钥与密文格式。
- 使用已知向量、往返、篡改和错误密钥测试覆盖安全边界。

**Non-Goals:**

- 不提供密钥持久化、证书、PEM 文件、硬件密钥、密钥轮换或密钥托管功能。
- 不提供流式大文件加密接口；本次 API 面向可驻留内存的字符串数据。
- 不兼容历史密文格式，也不提供 ECB、固定 IV、PKCS#1 v1.5 加密等弱配置。
- 不新增 Spring Bean 或 Web API。

## Decisions

### 使用 JCA/JCE 与 Bouncy Castle Provider

RSA、AES 和 SHA-256 优先通过 Java 标准 JCA/JCE API 实现；SM2、SM3、SM4 使用 `bcprov-jdk18on`。代码持有私有的 Bouncy Castle Provider 实例，并在请求国密算法时显式传入，避免修改 JVM 全局 Provider 顺序。

选择 Bouncy Castle 是因为它在 Java 21 上提供完整且成熟的国密算法与 ASN.1 密钥支持。自行实现密码算法不可审计且风险过高；依赖操作系统 Provider 则无法保证部署环境一致。

### 公共 API 与返回模型

每个工具类使用私有构造器禁止实例化，公共方法提供基于 `String` 的便捷 API。`RsaUtils` 与 `Sm2Utils` 各自定义不可变嵌套 `record KeyPairData(String publicKey, String privateKey)`，使密钥生成的所有返回字段均为 Base64 字符串；`AesUtils` 与 `Sm4Utils` 的 `generateKey()` 直接返回 Base64 密钥。

加密、签名和摘要返回标准 Base64；解密返回由明文字节按 UTF-8 还原的字符串；验签返回布尔值。这里将“所有结果使用 Base64”解释为所有二进制密码学产物均 Base64 化，而语义结果（解密后的原文和验签布尔值）保持原始类型，避免二次编码给调用方造成歧义。

备选方案是所有方法只接收和返回 Base64 字节串，但这会迫使普通文本调用方自行编码，降低易用性且容易出现字符集不一致。

### 固定密钥编码格式

RSA 和 SM2 公钥统一使用 X.509 `SubjectPublicKeyInfo` DER 编码，私钥统一使用 PKCS#8 DER 编码，然后进行标准 Base64 编码。RSA 默认生成 2048 位密钥；SM2 使用命名曲线 `sm2p256v1`。这种格式可直接由 `KeyFactory` 重建，并比 Provider 专有的裸坐标或整数格式更利于互操作。

### RSA 使用 OAEP-SHA-256 并自动分段

RSA 加密固定为 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`，同时显式配置 OAEP 主摘要和 MGF1 摘要均为 SHA-256，避免 Provider 默认参数差异。加密块上限按 `k - 2*hLen - 2` 计算，解密块固定为模数字节长度，结果按顺序拼接。签名固定为 `SHA256withRSA`。

未选择 PKCS#1 v1.5 加密，因为 OAEP 提供更稳健的安全属性。RSA 分段仅满足需求中的长文本便捷处理；对大量数据更合适的混合加密不在本次范围。

### AES 与 SM4 使用 GCM 封装格式

AES 使用 256 位密钥，SM4 使用 128 位密钥；二者均采用 GCM 无填充模式、96 位随机 IV 和 128 位认证标签。每次加密使用 `SecureRandom` 创建新 IV，输出字节布局为 `版本字节 || IV || 密文与标签`，整体再 Base64。首个版本字节固定为 `0x01`，以便未来扩展格式时明确拒绝未知版本。

未选择 CBC，因为 CBC 本身不能检测密文篡改，额外组合 MAC 更复杂且容易误用。未将 IV 单独作为参数，是为了保证调用方不会复用或丢失 IV。

### SM2 固定 C1C3C2 与 DER 签名

SM2 加解密显式使用 C1C3C2 布局，防止不同库默认使用 C1C2C3 导致互操作失败。签名使用 `SM3withSM2`，返回 JCA 产生的 ASN.1 DER 编码签名后再 Base64。若采用底层 SM2 引擎，将在工具内部完成 JCA 公私钥到 Bouncy Castle 参数对象的转换，不向公共 API 暴露 Provider 类型。

### 统一校验与异常边界

所有公共方法首先校验必填字符串、Base64 和密钥长度。调用方输入导致的解码、密钥重建、认证失败或密码运算失败统一包装为带中文消息的 `IllegalArgumentException`，并保留原始异常作为 cause；正常但不匹配的验签结果返回 `false`。不可预期的算法缺失或内部初始化失败包装为 `IllegalStateException`。

错误消息不包含明文、密钥或完整密文，避免敏感数据进入日志。工具不会自行记录敏感参数。

## Risks / Trade-offs

- [RSA 分段不是大数据的最佳密码学封装] → 在 Javadoc 中标明适用范围，并将混合加密留作后续独立能力。
- [SM4-GCM 与部分只支持 CBC 的外部系统不互操作] → 本次优先保证认证加密；通过固定版本化封装为未来新增明确模式保留空间。
- [Bouncy Castle 版本变化可能影响 Provider 行为] → 锁定 Gradle 依赖版本，并用标准向量和格式断言测试关键行为。
- [字符串 API 无法由调用方主动擦除内存中的敏感内容] → 明确工具定位为便捷 API；高敏感或大文件场景后续提供字节/流式 API。
- [错误类型统一后会丢失细粒度密码异常类型] → 保留 cause 供诊断，同时保持公共 API 简洁且不泄露 Provider 差异。

## Migration Plan

1. 增加并锁定 Bouncy Castle Provider 依赖。
2. 新增工具类和单元测试，不改动现有调用路径。
3. 运行完整测试与构建，确认依赖不与现有组件冲突。
4. 如需回滚，删除新增工具类、测试及 Bouncy Castle 依赖即可；现有 API 和数据不受影响。
