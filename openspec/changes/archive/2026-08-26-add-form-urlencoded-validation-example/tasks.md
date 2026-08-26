## 1. 表单请求模型

- [x] 1.1 新增包含 `username`、`email`、`age` 的不可变表单请求 DTO，配置非空、长度、邮箱和年龄范围约束及中文消息，并通过编译和约束反射检查确认注解完整
- [x] 1.2 为表单 DTO 补充中文类级说明、组件说明和 `@Schema` 示例，并通过代码审查确认每个对外字段都有文档描述

## 2. 表单校验接口

- [x] 2.1 移除 `ValidationExampleController` 的类级路径映射，将既有 POST、GET 注解改为方法级完整 URL，并通过现有 MVC 测试确认两个接口路径和行为不变
- [x] 2.2 新增 `/api/validation/form` PUT 接口，限制 `application/x-www-form-urlencoded`，使用 `@Valid @ModelAttribute` 绑定表单 DTO，并通过成功请求测试确认统一响应返回全部字段
- [x] 2.3 为新增接口补充 `@Operation`、表单 DTO 参数文档和返回说明，并通过代码审查确认与实际方法、媒体类型和约束一致

## 3. 媒体类型异常处理

- [x] 3.1 在全局异常处理器中新增不受支持媒体类型的专用 HTTP 415 映射，返回统一错误响应，并通过 MVC 测试确认该异常不会落入 HTTP 500 兜底

## 4. 测试与验证

- [x] 4.1 扩展 `ValidationExampleControllerTest`，覆盖合法表单、用户名、邮箱、年龄约束失败和 JSON Content-Type 不匹配，并验证 HTTP 状态、统一响应、`traceId` 与 `timestamp`
- [x] 4.2 运行参数校验相关目标测试，确认其不依赖 MySQL、Redis 或 Nacos且全部通过
- [x] 4.3 运行完整 Gradle 测试，并区分本次代码失败与本地基础设施或既有测试隔离问题
