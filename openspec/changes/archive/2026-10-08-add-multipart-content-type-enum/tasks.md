# Tasks

## 1. 审阅与确认

- [x] 1.1 获得用户对 `proposal.md`、`design.md` 和 `specs/multipart-content-type-enum/spec.md` 的明确确认。

## 2. 实现

- [x] 2.1 新增 `org.example.simple.http.MultipartContentTypeEnum` 枚举，声明 `contentType`（`String`）和 `description`（`String`）两个字段及私有构造器，并收录常量：`OCTET_STREAM`（`application/octet-stream`）、`TEXT_PLAIN`（`text/plain`）、`JSON`（`application/json`）、`XML`（`application/xml`）、`PDF`（`application/pdf`）、`ZIP`（`application/zip`）、`JPEG`（`image/jpeg`）、`PNG`（`image/png`）、`GIF`（`image/gif`），每个常量附带中文描述。
- [x] 2.2 为 `MultipartContentTypeEnum` 提供 `getContentType()` 和描述字段对应的 getter 方法，并补充类与枚举常量的中文 Javadoc（说明用途、取值来源为标准 MIME 字符串、不含 `charset` 等参数）。
- [x] 2.3 在 `MultipartFilePart` 的类或 `of()` 方法 Javadoc 中补充示例，说明可通过 `MultipartContentTypeEnum.OCTET_STREAM.getContentType()` 获取 `contentType` 参数值；不修改 `MultipartFilePart`/`MultipartFilePart.of()` 的方法签名与校验逻辑。

## 3. 测试

- [x] 3.1 新增 `MultipartContentTypeEnumTest`，验证每个常量的 `getContentType()` 返回预期的标准 MIME 字符串（逐一断言，覆盖 `proposal.md`/`design.md` 中列出的全部常量）。
- [x] 3.2 测试 `MultipartContentTypeEnum.values()` 中任意两个不同常量的 `getContentType()` 返回值互不相同（唯一性）。
- [x] 3.3 测试每个常量的描述 getter 返回非空、非空白字符串。
- [x] 3.4 测试将 `MultipartContentTypeEnum` 任意常量的 `getContentType()` 返回值传入 `MultipartFilePart.of(fieldName, path, contentType)` 能成功构造实例（通过现有 `requireContentType` 校验，不抛出异常）。

## 4. 文档与验证

- [x] 4.1 运行 `gradlew.bat test` 与 `gradlew.bat build`，确认全部测试通过且无新增编译或弃用警告。
- [x] 4.2 运行 `openspec validate add-multipart-content-type-enum --strict`，核对代码、测试与 OpenSpec 文档一致，并在本文件补充验证记录。

## 验证记录

- `gradlew.bat test --tests "org.example.simple.http.MultipartContentTypeEnumTest" --tests "org.example.simple.http.HttpModelsTest"`：全部通过。
- `gradlew.bat build`（全量）：`BUILD SUCCESSFUL`，无新增编译或弃用警告。
- `openspec validate add-multipart-content-type-enum --strict`：`Change 'add-multipart-content-type-enum' is valid`。
