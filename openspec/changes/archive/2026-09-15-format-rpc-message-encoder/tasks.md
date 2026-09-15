## 1. 格式化与说明补全

- [x] 1.1 检查 RPC 包现有未提交改动，确认可安全处理的文件范围，并以 `git diff` 验证不覆盖无关修改。
- [x] 1.2 格式化 `client`、`common` 和 `config` 子包的全部 Java 文件，补充必要中文说明，并以 `gradlew.bat classes` 验证编译通过。
- [x] 1.3 格式化 `discovery`、`loadbalance` 和 `monitoring` 子包的全部 Java 文件，补充必要中文说明，并以 `gradlew.bat classes` 验证编译通过。
- [x] 1.4 格式化 `server` 和 `transport` 子包的全部 Java 文件，补充必要中文说明，并以 `gradlew.bat classes` 验证编译通过。

## 2. 审阅与验证

- [x] 2.1 审阅全部 RPC 源码差异，确认仅包含空白、换行、缩进和注释变更，并以 `git diff --check` 验证不存在空白错误。
- [x] 2.2 运行 `gradlew.bat test` 和 `openspec validate format-rpc-message-encoder --strict`，确认测试及变更文档校验通过。
