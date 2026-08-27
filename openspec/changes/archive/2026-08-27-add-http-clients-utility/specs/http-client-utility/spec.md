## Purpose

为调用方提供一致的同步 HTTP 请求能力，覆盖常用方法、请求体编码、原始响应读取和 JSON 对象转换，并以可测试的方式统一成功与失败行为。

## ADDED Requirements

### Requirement: 支持常用 HTTP 方法
系统 SHALL 允许调用方通过静态方法直接提供合法的 HTTP 或 HTTPS URL 文本，发送 `GET`、`POST`、`PUT` 和 `DELETE` 请求，并允许调用方设置请求头和 URL 查询参数；调用方 SHALL 无需创建客户端实例或预先构造 `URI` 对象。

#### Scenario: 静态方法直接调用
- **WHEN** 调用方执行 `HttpClients.get("http://127.0.0.1:23456/hello")` 或对应的 `post`、`put`、`delete` 静态方法
- **THEN** 系统使用共享客户端资源直接发送请求，无需调用方创建或关闭 `HttpClients` 实例

#### Scenario: 发送四种方法
- **WHEN** 调用方分别使用 `GET`、`POST`、`PUT` 和 `DELETE` 传入类似 `http://127.0.0.1:23456/hello` 的合法 URL 文本
- **THEN** 系统按调用方指定的方法、URL、请求头和查询参数发送请求

#### Scenario: 拒绝非法请求参数
- **WHEN** 调用方提供空 URL 文本、格式错误 URL、非 HTTP 协议 URL、空请求头名称或其他不合法的请求参数
- **THEN** 系统在发送网络请求前抛出可识别的参数异常

### Requirement: 支持多种请求体类型
系统 SHALL 支持 `multipart/form-data`、`application/x-www-form-urlencoded`、`application/json` 和任意媒体类型的二进制文件请求体，并 SHALL 使用 UTF-8 编码表单文本和 JSON 文本。

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

### Requirement: 提供原始响应
系统 SHALL 为成功响应提供状态码、响应头和完整响应体字节，并 SHALL 在读取完成后释放底层响应资源。

#### Scenario: 返回成功原始响应
- **WHEN** 服务端返回任意 `2xx` 状态响应
- **THEN** 系统返回包含实际状态码、响应头和响应体字节的结果

#### Scenario: 处理无响应体成功响应
- **WHEN** 服务端返回无响应体的 `2xx` 状态响应
- **THEN** 系统返回空字节数组且不返回空引用

### Requirement: 直接转换 JSON 响应
系统 SHALL 支持将成功响应的 JSON 响应体直接转换为调用方指定的普通 Java 类型或带泛型的 Java 类型。

#### Scenario: 转换普通对象
- **WHEN** 服务端返回合法 JSON 且调用方提供普通目标类型
- **THEN** 系统返回转换后的目标对象

#### Scenario: 转换泛型对象
- **WHEN** 服务端返回合法 JSON 且调用方提供包含泛型信息的目标类型
- **THEN** 系统保留泛型类型信息并返回转换后的对象

#### Scenario: JSON 转换失败
- **WHEN** 成功响应体不是合法 JSON 或与目标类型不兼容
- **THEN** 系统抛出可识别的响应转换异常并保留原始原因

### Requirement: 统一请求失败行为
系统 SHALL 将网络传输失败和非 `2xx` HTTP 响应转换为统一的客户端异常；对于 HTTP 状态失败，异常 SHALL 携带状态码、响应头和响应体字节，供调用方诊断。

#### Scenario: 服务端返回非成功状态
- **WHEN** 服务端返回非 `2xx` 状态响应
- **THEN** 系统完整读取并释放响应资源，然后抛出包含该响应信息的客户端异常

#### Scenario: 网络传输失败
- **WHEN** DNS、连接、TLS 握手或响应读取失败
- **THEN** 系统抛出保留底层原因的客户端异常
