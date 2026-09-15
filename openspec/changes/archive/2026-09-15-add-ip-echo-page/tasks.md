## 1. Node.js IP 回显服务

- [x] 1.1 新增仅使用 Node.js 内置模块的 `echo-ip-server.js`，托管 `/echoIp.html`、`/` 和 `/api/ip`，并通过本地 HTTP 请求验证页面、JSON 与 `404` 响应
- [x] 1.2 实现 IPv4-映射 IPv6 地址规范化、`PORT` 端口覆盖、静态页面读取失败与启动错误处理，并通过脚本化检查验证

## 2. IP 查询页面

- [x] 2.1 新增单文件 `echoIp.html`，仅使用原生 HTML、CSS 和 JavaScript 实现响应式网络探针布局，并通过浏览器检查显示、窄屏布局和键盘聚焦
- [x] 2.2 实现同源 IP 加载、刷新、复制、加载状态、失败提示和减少动效支持，并通过浏览器交互验证

## 3. 质量验证

- [x] 3.1 运行 Node.js 语法检查、本地端到端请求、格式与行宽检查，并运行 `openspec validate add-ip-echo-page --strict`
