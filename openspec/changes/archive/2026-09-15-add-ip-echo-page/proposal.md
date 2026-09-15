## Why

用户需要一个可直接访问的页面查看当前访问者的 IP 地址。纯静态页面无法读取请求源地址，因此需要由同源的 Node.js 服务提供可控的 IP 回显接口。

## What Changes

- 新增 `echoIp.html`，仅使用原生 HTML、CSS 和 JavaScript 加载、显示、复制与刷新 IP 地址。
- 新增不依赖第三方包的 Node.js 服务，同时托管页面并提供同源 IP 查询接口。
- 对 IPv4、IPv4-映射 IPv6 与代理转发场景提供明确的地址解析规则和可预期的失败提示。

## Capabilities

### New Capabilities

- `ip-echo-page`: 通过同源页面和接口显示访问者 IP 地址。

### Modified Capabilities

无。

## Impact

- 新增静态页面和 Node.js 服务脚本，不修改现有 Java 模块、Gradle 依赖或 HTTP 工具。
- 页面的 `fetch` 调用仅访问同源 `/api/ip`，不向第三方发送访问者地址。
