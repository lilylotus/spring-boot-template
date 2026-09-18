# cryptography-utilities Specification

## Purpose

为调用方提供统一、可互操作且默认安全的密码学能力，使国际算法与国密算法共享明确的密钥格式、Base64 表示、字符编码和失败语义。

## Requirements

### Requirement: 统一的编码与参数约定
工具 SHALL 使用 UTF-8 在字符串与字节之间转换，并 SHALL 使用标准 Base64（非 URL 安全变体）表示所有密钥、密文、签名和摘要。工具 MUST 拒绝空值、空白密钥、非法 Base64 及不符合算法要求的密钥材料，并以参数异常向调用方报告。

#### Scenario: 二进制结果使用标准 Base64
- **WHEN** 调用方生成密钥、加密数据、生成签名或计算摘要
- **THEN** 返回的每个二进制结果均为可由标准 Base64 解码器解析的字符串

#### Scenario: 拒绝非法 Base64
- **WHEN** 调用方传入无法按标准 Base64 解码的密钥、密文或签名
- **THEN** 工具抛出参数异常且不返回部分结果

### Requirement: RSA 密钥生成与加解密
工具 SHALL 生成至少 2048 位的 RSA 密钥对，以 X.509 编码公钥、PKCS#8 编码私钥，并以 Base64 返回二者。工具 SHALL 使用带 SHA-256 的 OAEP 填充执行公钥加密和私钥解密，并 SHALL 自动分段处理超过单个 RSA 运算块容量的数据。

#### Scenario: RSA 长文本往返
- **WHEN** 调用方使用生成的公钥加密长度超过单个 RSA 块容量的 UTF-8 文本，并使用配对私钥解密
- **THEN** 解密结果与原始文本完全一致

#### Scenario: RSA 错误私钥解密失败
- **WHEN** 调用方使用不匹配的私钥解密 RSA 密文
- **THEN** 工具抛出参数异常且不返回明文

### Requirement: RSA 签名与验签
工具 SHALL 使用 SHA-256 with RSA 算法生成 Base64 签名，并使用对应公钥验证签名。验签对于内容被修改、签名不匹配或公钥不匹配的情况 SHALL 返回 `false`。

#### Scenario: RSA 签名验证成功
- **WHEN** 调用方使用私钥签名文本并使用配对公钥验证原文和签名
- **THEN** 验签结果为 `true`

#### Scenario: RSA 篡改内容验证失败
- **WHEN** 调用方验证的文本与签名时的文本不同
- **THEN** 验签结果为 `false`

### Requirement: AES 密钥生成与认证加密
工具 SHALL 生成 256 位安全随机 AES 密钥并以 Base64 返回。工具 SHALL 使用 AES-GCM 执行认证加密，每次加密生成新的 96 位随机 IV，并将 IV、密文与认证标签组合为单个 Base64 结果；解密 SHALL 验证认证标签后再返回 UTF-8 明文。

#### Scenario: AES 文本往返
- **WHEN** 调用方使用生成的 AES 密钥加密 UTF-8 文本并解密所得结果
- **THEN** 解密结果与原始文本完全一致

#### Scenario: AES 重复加密使用不同 IV
- **WHEN** 调用方使用同一密钥对同一文本执行两次加密
- **THEN** 两次返回的 Base64 密文不同且均可正确解密

#### Scenario: AES 篡改密文被拒绝
- **WHEN** 调用方解密认证标签或密文已被修改的 AES 结果
- **THEN** 工具抛出参数异常且不返回明文

### Requirement: SM2 密钥生成与加解密
工具 SHALL 基于 `sm2p256v1` 曲线生成 SM2 密钥对，以 X.509 编码公钥、PKCS#8 编码私钥，并以 Base64 返回二者。工具 SHALL 使用 C1C3C2 密文布局执行公钥加密和私钥解密。

#### Scenario: SM2 文本往返
- **WHEN** 调用方使用生成的 SM2 公钥加密 UTF-8 文本并使用配对私钥解密
- **THEN** 解密结果与原始文本完全一致

#### Scenario: SM2 错误私钥解密失败
- **WHEN** 调用方使用不匹配的 SM2 私钥解密密文
- **THEN** 工具抛出参数异常且不返回明文

### Requirement: SM2 签名与验签
工具 SHALL 使用 SM3 with SM2 算法生成 Base64 编码的 DER 签名，并使用对应公钥验证签名。验签对于内容被修改、签名不匹配或公钥不匹配的情况 SHALL 返回 `false`。

#### Scenario: SM2 签名验证成功
- **WHEN** 调用方使用 SM2 私钥签名文本并使用配对公钥验证原文和签名
- **THEN** 验签结果为 `true`

#### Scenario: SM2 篡改内容验证失败
- **WHEN** 调用方验证的文本与 SM2 签名时的文本不同
- **THEN** 验签结果为 `false`

### Requirement: SM4 密钥生成与认证加密
工具 SHALL 生成 128 位安全随机 SM4 密钥并以 Base64 返回。工具 SHALL 使用 SM4-GCM 执行认证加密，每次加密生成新的 96 位随机 IV，并将 IV、密文与认证标签组合为单个 Base64 结果；解密 SHALL 验证认证标签后再返回 UTF-8 明文。

#### Scenario: SM4 文本往返
- **WHEN** 调用方使用生成的 SM4 密钥加密 UTF-8 文本并解密所得结果
- **THEN** 解密结果与原始文本完全一致

#### Scenario: SM4 重复加密使用不同 IV
- **WHEN** 调用方使用同一密钥对同一文本执行两次加密
- **THEN** 两次返回的 Base64 密文不同且均可正确解密

#### Scenario: SM4 篡改密文被拒绝
- **WHEN** 调用方解密认证标签或密文已被修改的 SM4 结果
- **THEN** 工具抛出参数异常且不返回明文

### Requirement: SHA-256 与 SM3 摘要
工具 SHALL 分别支持 SHA-256 和 SM3 摘要，并 SHALL 将摘要字节以标准 Base64 字符串返回。相同输入和算法 SHALL 始终产生相同结果。

#### Scenario: SHA-256 摘要符合标准向量
- **WHEN** 调用方对已知 UTF-8 文本计算 SHA-256 摘要
- **THEN** Base64 解码后的摘要与对应标准测试向量一致

#### Scenario: SM3 摘要符合标准向量
- **WHEN** 调用方对已知 UTF-8 文本计算 SM3 摘要
- **THEN** Base64 解码后的摘要与对应标准测试向量一致
