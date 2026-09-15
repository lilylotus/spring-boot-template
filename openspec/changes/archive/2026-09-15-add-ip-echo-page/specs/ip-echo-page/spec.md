## Purpose

为访问者提供一个无框架、可直接访问的 IP 查询页面，并使用同源服务端回显当前 HTTP 请求的连接来源地址。

## ADDED Requirements

### Requirement: 提供访问者 IP 查询接口
系统 SHALL 提供一个同源的 GET 接口，以 JSON 返回当前 HTTP 请求的 IP 地址。地址为 IPv4-映射 IPv6 时，系统 SHALL 返回对应的 IPv4 表示。

#### Scenario: 成功获取访问者地址
- **WHEN** 访问者向 IP 查询接口发起 GET 请求
- **THEN** 系统返回 `200` 和包含字符串 `ip` 字段的 JSON 响应

#### Scenario: 规范化 IPv4-映射 IPv6 地址
- **WHEN** 请求连接地址以 `::ffff:` 为前缀
- **THEN** 系统仅返回前缀之后的 IPv4 地址

### Requirement: 显示并操作当前 IP 地址
系统 SHALL 提供名为 `echoIp.html` 的同源页面。页面加载时 SHALL 请求 IP 查询接口并明确呈现加载、成功或失败状态。

#### Scenario: 显示查询结果
- **WHEN** 页面成功获取包含 IP 地址的响应
- **THEN** 页面可选中地显示 IP 地址，并提供复制和刷新控件

#### Scenario: 复制 IP 地址
- **WHEN** 访问者点击复制控件
- **THEN** 页面将当前 IP 地址写入剪贴板，并反馈操作结果

#### Scenario: 刷新 IP 地址
- **WHEN** 访问者点击刷新控件
- **THEN** 页面重新请求 IP 查询接口并更新显示结果

#### Scenario: 查询失败
- **WHEN** IP 查询接口返回非成功响应或网络请求失败
- **THEN** 页面显示可理解的错误信息并保留可用的刷新控件

### Requirement: 提供可直接访问的页面服务
系统 SHALL 提供不依赖第三方包的 Node.js 服务，并在默认端口上托管 `echoIp.html` 与 IP 查询接口。

#### Scenario: 访问页面
- **WHEN** 访问者请求页面路径
- **THEN** 系统返回 HTML 页面且声明 `text/html` 媒体类型

#### Scenario: 访问不存在路径
- **WHEN** 访问者请求未定义的路径
- **THEN** 系统返回 `404`
