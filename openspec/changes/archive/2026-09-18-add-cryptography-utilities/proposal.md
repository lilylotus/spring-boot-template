## Why

项目当前缺少统一的密码学工具，业务代码若自行处理算法、密钥格式和 Base64 编解码，容易产生重复实现与互操作差异。需要提供覆盖常用国际算法和国密算法的集中式工具 API，并用测试固定其行为与错误边界。

## What Changes

- 在 `org.example.simple.util` 包新增 RSA 工具，支持密钥对生成、分段加解密、SHA-256 with RSA 签名及验签。
- 新增 AES 工具，支持安全随机密钥生成，以及带随机 IV 和认证标签的加解密。
- 新增 SM2 工具，支持密钥对生成、加解密、SM3 with SM2 签名及验签。
- 新增 SM4 工具，支持安全随机密钥生成，以及带随机 IV 的加解密。
- 新增摘要工具，支持 SHA-256 和 SM3 摘要。
- 统一使用 UTF-8 处理字符串，密钥、密文、签名和摘要等二进制结果统一以标准 Base64 字符串传递。
- 引入 Bouncy Castle 密码学依赖，为 SM2、SM3、SM4 及相关密钥编解码提供实现。
- 为各工具补充正常流程、往返处理、篡改检测、非法 Base64、错误密钥等单元测试。

## Capabilities

### New Capabilities

- `cryptography-utilities`: 定义 RSA、AES、SM2、SM4、SHA-256 与 SM3 工具的密钥格式、Base64 输入输出、加解密、签名验签、摘要和错误处理行为。

### Modified Capabilities

无。

## Impact

- 新增 `src/main/java/org/example/simple/util` 下的五个无状态工具类。
- 新增 `src/test/java/org/example/simple/util` 下的单元测试。
- 修改 `build.gradle`，增加 Bouncy Castle Provider 依赖。
- 不修改现有 HTTP、RPC API 或运行时配置；新增 API 作为库内公共静态方法供调用方使用。
