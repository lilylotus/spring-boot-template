# Spec Delta

## MODIFIED Requirements

### Requirement: /proxy 前缀请求转发到 BootDemo 服务

网关 SHALL 将所有路径以 `/proxy` 开头的 HTTP 请求转发到服务发现中名为 `BootDemo` 的服务实例。目标实例 MUST 通过服务发现与客户端负载均衡解析，不得硬编码 IP 或端口。转发 MUST 保留原请求的 HTTP 方法、查询字符串和请求体。

转发前置于 `gateway-resilience` 定义的限流与熔断之后：当配额已耗尽或熔断处于打开状态时，网关 MUST 直接返回降级响应，MUST NOT 向 `BootDemo` 发起请求。本需求中的各转发场景均以「未被限流、熔断关闭」为前提。

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

#### Scenario: 被限流或熔断拦下时不转发

- **WHEN** `/proxy/**` 的请求配额已耗尽，或熔断处于打开状态
- **THEN** 网关按 `gateway-resilience` 的约定返回降级响应，不向 `BootDemo` 发起该请求
