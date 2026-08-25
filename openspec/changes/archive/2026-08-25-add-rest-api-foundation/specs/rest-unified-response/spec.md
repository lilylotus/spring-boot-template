## Purpose

为 REST 接口提供稳定、可复用且便于调用方解析的成功与失败响应契约，并把当前请求的链路追踪标识随响应一同返回，方便跨服务排查问题。

## ADDED Requirements

### Requirement: 统一响应字段
系统 MUST（必须）提供泛型统一响应模型，并在序列化后的响应对象中包含数值型 `code`、泛型 `data`、文本型 `message`、文本型 `traceId` 和数值型 `timestamp` 五个字段；没有业务数据时也必须保留 `data` 字段并将其值设为 `null`。

#### Scenario: 返回带数据的成功响应
- **WHEN** 接口使用统一响应能力返回业务数据
- **THEN** 响应体包含 `code`、`data`、`message`、`traceId`、`timestamp` 五个字段，且 `data` 等于该业务数据

#### Scenario: 返回无数据的响应
- **WHEN** 接口或异常处理没有可返回的业务数据
- **THEN** 响应体仍包含 `data` 字段，且其值为 `null`

### Requirement: 集中构造成功与失败响应
系统 MUST（必须）提供集中构造统一响应的方式；成功响应的 `code` 必须为 `0`、`message` 必须为“成功”，失败响应必须使用调用方传入的错误码和错误消息。

#### Scenario: 构造成功响应
- **WHEN** 调用方构造成功响应
- **THEN** 得到 `code` 为 `0`、`message` 为“成功”且包含指定业务数据的统一响应

#### Scenario: 构造失败响应
- **WHEN** 调用方以指定错误码和错误消息构造失败响应
- **THEN** 得到包含该错误码和错误消息且 `data` 为 `null` 的统一响应

### Requirement: 响应携带当前链路追踪标识
系统 MUST（必须）从当前请求上下文读取 `traceId` 并写入统一响应；同一 HTTP 请求的响应体 `traceId` 必须与 `X-Trace-Id` 响应头一致。在没有链路追踪上下文的非 HTTP 调用中，系统不得因缺少 `traceId` 而构造失败。

#### Scenario: HTTP 请求中构造响应
- **WHEN** 请求已经建立链路追踪上下文并构造统一响应
- **THEN** 响应体 `traceId` 与该请求的 `X-Trace-Id` 响应头一致

#### Scenario: 非 HTTP 上下文中构造响应
- **WHEN** 当前线程没有链路追踪标识但调用方构造统一响应
- **THEN** 系统正常返回统一响应，且 `traceId` 为 `null`

### Requirement: 响应携带构造时间戳
系统 MUST（必须）在每次构造统一响应时生成非空的 `timestamp`，其值必须是响应构造时刻对应的 Unix 毫秒时间戳；成功响应、失败响应以及非 HTTP 上下文构造的响应均不得省略该字段。

#### Scenario: 构造成功响应时记录时间
- **WHEN** 调用方构造统一成功响应
- **THEN** `timestamp` 位于响应构造前后取得的系统 Unix 毫秒时间戳之间

#### Scenario: 构造失败响应时记录时间
- **WHEN** 调用方或全局异常处理器构造统一失败响应
- **THEN** `timestamp` 位于响应构造前后取得的系统 Unix 毫秒时间戳之间
