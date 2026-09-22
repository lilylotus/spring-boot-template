# Spec Delta

## Purpose

定义网关对外暴露的 HTTP 反向代理行为：哪些请求路径由网关接管、转发到哪个下游服务、以及转发过程中路径和请求头如何变换。

## ADDED Requirements

### Requirement: /proxy 前缀请求转发到 BootDemo 服务

网关 SHALL 将所有路径以 `/proxy` 开头的 HTTP 请求转发到服务发现中名为 `BootDemo` 的服务实例。目标实例 MUST 通过服务发现与客户端负载均衡解析，不得硬编码 IP 或端口。转发 MUST 保留原请求的 HTTP 方法、查询字符串和请求体。

#### Scenario: 转发带子路径的请求

- **WHEN** 客户端请求 `GET /proxy/welcome`
- **THEN** 网关向某个 `BootDemo` 实例发起 `GET /welcome` 请求，并把该实例的响应状态码、响应头和响应体原样返回给客户端

#### Scenario: 保留查询参数

- **WHEN** 客户端请求 `GET /proxy/order/list?page=1&size=20`
- **THEN** 网关向 `BootDemo` 发起 `GET /order/list?page=1&size=20` 请求，查询字符串不被改写

#### Scenario: 转发非 GET 请求

- **WHEN** 客户端以 `POST /proxy/order` 携带 JSON 请求体访问网关
- **THEN** 网关以 `POST /order` 将同样的请求体和 `Content-Type` 转发到 `BootDemo`

#### Scenario: 非 /proxy 路径不受影响

- **WHEN** 客户端请求 `GET /welcome`（网关自身的接口，不带 `/proxy` 前缀）
- **THEN** 该请求由网关自身处理，不转发到 `BootDemo`

#### Scenario: 下游服务不可用

- **WHEN** 服务发现中没有可用的 `BootDemo` 实例，且客户端请求 `GET /proxy/welcome`
- **THEN** 网关返回 `503 Service Unavailable`，不返回网关内部异常堆栈

### Requirement: 转发时剥离 /proxy 前缀

网关 SHALL 在转发前从请求路径中移除第一段路径 `/proxy`，下游服务接收到的 MUST 是不含该前缀的业务路径。

#### Scenario: 剥离多级路径的前缀

- **WHEN** 客户端请求 `GET /proxy/api/v1/users/1`
- **THEN** `BootDemo` 收到的请求路径是 `/api/v1/users/1`

#### Scenario: 仅访问前缀本身

- **WHEN** 客户端请求 `GET /proxy/`
- **THEN** `BootDemo` 收到的请求路径是 `/`

### Requirement: 转发时保留客户端原始 Host 头

网关 SHALL 把客户端请求中的 `Host` 头原样转发给 `BootDemo`，MUST NOT 将其替换为被选中下游实例的 `host:port`。

#### Scenario: 保留外部访问域名

- **WHEN** 客户端以 `Host: gateway.example.com` 请求 `GET /proxy/welcome`，网关选中的实例地址为 `10.4.100.130:8080`
- **THEN** `BootDemo` 收到的请求中 `Host` 头仍为 `gateway.example.com`，而不是 `10.4.100.130:8080`

### Requirement: 代理请求携带链路追踪标识

经 `/proxy` 转发的请求 SHALL 携带与网关本次请求一致的 `X-Trace-Id` 请求头，使网关日志与下游日志可按同一 traceId 关联。

#### Scenario: 透传调用方传入的 traceId

- **WHEN** 客户端以 `X-Trace-Id: abc123...` 请求 `GET /proxy/welcome`
- **THEN** `BootDemo` 收到的请求中 `X-Trace-Id` 为 `abc123...`，且网关的响应头中也回显该值

#### Scenario: 调用方未传 traceId

- **WHEN** 客户端不带 `X-Trace-Id` 请求 `GET /proxy/welcome`
- **THEN** 网关生成一个 32 位十六进制 traceId，随请求转发给 `BootDemo`，并在响应头中回显同一个值
