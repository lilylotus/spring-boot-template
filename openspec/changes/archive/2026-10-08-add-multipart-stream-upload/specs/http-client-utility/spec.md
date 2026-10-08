# Spec Delta

## MODIFIED Requirements

### Requirement: 支持多种请求体类型

系统 SHALL 支持 `multipart/form-data`、`application/x-www-form-urlencoded`、`application/json` 和任意媒体类型的二进制文件请求体，并 SHALL 使用 UTF-8 编码表单文本和 JSON 文本。对于 `multipart/form-data` 请求，系统 SHALL 允许文件字段的内容来自本地文件或调用方提供的输入流；当文件字段内容来自调用方提供的输入流时，系统 SHALL 在请求发送完成后（无论成功、失败或超时）关闭该流，调用方无需也不应再手动关闭。

#### Scenario: 发送 multipart 表单

- **WHEN** 调用方提交包含文本字段和一个或多个文件字段的 multipart 请求
- **THEN** 系统发送带正确 boundary、字段名称、文件名称和内容的 `multipart/form-data` 请求体

#### Scenario: 发送 URL 编码表单

- **WHEN** 调用方提交包含一个或多个键值对的 URL 编码表单
- **THEN** 系统按 UTF-8 编码并发送 `application/x-www-form-urlencoded` 请求体

#### Scenario: 发送 JSON 对象

- **WHEN** 调用方提供可序列化对象作为 JSON 请求体
- **THEN** 系统将对象序列化并发送为 UTF-8 的 `application/json` 请求体

#### Scenario: 发送二进制文件

- **WHEN** 调用方提供可读取的文件和媒体类型
- **THEN** 系统以指定媒体类型发送与文件字节一致的请求体

#### Scenario: GET 携带请求体

- **WHEN** 调用方尝试为 `GET` 请求配置请求体
- **THEN** 系统在发送网络请求前拒绝该请求

#### Scenario: 通过输入流发送 multipart 文件字段

- **WHEN** 调用方提交 multipart 请求，其中一个或多个文件字段的内容来自调用方提供的输入流（而不是本地文件路径）
- **THEN** 系统读取该输入流的全部内容并以指定媒体类型和文件名发送对应的 `multipart/form-data` 文件字段，且请求体字节与流内容一致

#### Scenario: 发送完成后关闭调用方提供的输入流

- **WHEN** 系统已经使用调用方提供的输入流构建并发送了 multipart 请求（无论服务端返回成功、失败状态，还是请求过程中发生网络传输失败或超时）
- **THEN** 系统在请求处理结束前关闭该输入流

#### Scenario: multipart 请求体不包含任何字段

- **WHEN** 调用方提交的 multipart 请求既没有文本字段，也没有基于本地文件的文件字段，也没有基于输入流的文件字段
- **THEN** 系统在发送网络请求前抛出可识别的参数异常
