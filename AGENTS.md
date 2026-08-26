# 仓库指南

## 项目结构与模块组织

本项目是使用 Java 21 的单模块 Gradle 项目。生产代码位于 `src/main/java`，当前基础包为 `org.example.simple`。运行时配置及其他类路径资源放在 `src/main/resources`，例如 `logback.xml`。测试代码位于 `src/test/java`，其包结构应与生产代码一致。Gradle 包装器文件保存在 `gradle/wrapper`，OpenSpec 配置和变更材料放在 `openspec/`。`build/` 仅存放生成内容，不得提交。

## 构建、测试与开发命令

统一使用仓库内的 Gradle 包装器：

- `./gradlew build`（Windows 使用 `gradlew.bat build`）：编译、测试并打包项目。
- `./gradlew test`：运行全部 JUnit 5 测试。
- `./gradlew clean`：删除构建输出。
- `./gradlew dependencies`：检查依赖版本及冲突。
- `./gradlew classes`：仅编译生产代码。

项目要求使用 Java 21 JDK。不要依赖 `gradle.properties` 中特定于当前机器的 `org.gradle.java.home`，必要时在本地覆盖该配置。

## OpenSpec 变更流程

任何编码工作开始前，必须先遵循 OpenSpec 流程，在对应变更目录中编写 `proposal.md`、`design.md` 和 `tasks.md`。三份过程文档应分别说明变更目标与范围、技术设计与取舍，以及可验证的实施任务；未完成这些文档时不得修改生产代码或测试代码。

过程文档完成后，必须提交给用户手动审阅并获得明确确认。用户确认前只能补充或调整 OpenSpec 文档，不得执行编码、重构、测试实现或其他代码变更。实施过程中若需求、设计或任务发生变化，应先检查并同步更新对应 OpenSpec 文档，再继续工作。提交变更前再次核对代码、测试与 OpenSpec 内容保持一致。

## 内容语言、编码风格与命名

所有文件中的自然语言内容均使用中文，包括文档、注释、日志、用户可见文本、提交说明及新增配置中的描述。命令、路径、代码标识符、协议字段和第三方 API 名称等技术字面量保留原文。Java 类名、接口名、枚举名、`record` 名、方法名和字段名严禁使用中文字符，必须遵循英文命名约定；该规则同样适用于测试类、测试方法和测试夹具。

遵循 `.editorconfig`：使用 UTF-8、LF 行尾、Java 四空格缩进，Java 单行不超过 120 个字符。类名使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。包名保持小写并置于 `org.example.simple` 下。公共 API 的用途或约定不明显时，应添加中文 Javadoc。新增弃用警告应直接解决，不要大范围抑制。

## 测试指南

测试框架为 JUnit Jupiter（JUnit 5）。测试类按被测单元命名，例如 `MainTest`；测试方法应描述预期行为，例如 `printsExpectedGreeting()`。测试必须可重复执行，且不依赖执行顺序。每次行为变更都应补充或更新测试，提交前运行 `./gradlew test`。

## 提交与合并请求指南

仓库尚无提交历史。提交标题应简短、使用中文祈使句，可采用 Conventional Commits 格式，例如 `feat: 添加请求编解码器` 或 `test: 补充日志配置测试`。每个提交只处理一个主题。合并请求应说明变更内容与原因、列出验证命令，并关联相关议题或 OpenSpec 变更；仅在可见输出发生变化时附截图。不得提交 IDE 元数据、本地 JDK 路径、密钥或生成文件。
