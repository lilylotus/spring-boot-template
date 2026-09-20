## 1. 审阅与依赖确认

- [x] 1.1 获得用户对本变更文档的明确确认。
- [x] 1.2 在 Java 21 下核验现有 Groovy/GraalVM 依赖解析、JS 运行时组件与兼容性，必要时先同步设计再修正依赖。

## 2. 工具实现

- [x] 2.1 新增 ScriptExecutionException 和输入校验约定，提供中文错误说明并保留原因。
- [x] 2.2 实现 GroovyScriptUtils.execute，独立 Binding、顶层 Map 副本、语句执行、标量转换及类加载器清理。
- [x] 2.3 实现 GraalVMUtils.execute，独立 Context、函数体封装、Map 参数互操作、显式 return、结果转换及上下文关闭。
- [x] 2.4 为公开 API 添加中文 Javadoc，明确 null、复杂返回值、浅复制及同步脚本约定。

## 3. 验证与交付

- [x] 3.1 为两工具测试中文、数字、布尔、空映射、null 值、嵌套 Map/List、Java 对象访问及多行条件循环。
- [x] 3.2 测试字符串/GString、标量、null/undefined、无 return、复杂结果拒绝及显式结构化字符串返回。
- [x] 3.3 测试非法输入、语法错误、运行异常、保留 cause、异常后再次执行与并发绑定隔离。
- [x] 3.4 补充双引擎中文使用示例，说明 JavaScript 的 param.get 及显式 return 规则。
- [x] 3.5 使用 Gradle 包装器运行专项测试、全量 test 和 build，执行 OpenSpec 严格校验并核对代码与文档一致。

## 验证记录

- 使用 Java 21，现有 Groovy 5.1.2 与 GraalVM 25.2.4 依赖无需调整即可编译和执行。
- 专项测试：Groovy 11 项、GraalJS 12 项，均通过且无跳过。
- `gradlew.bat test build --no-daemon`：构建成功；全量 113 项测试，109 项通过、4 项跳过、0 项失败。
- `openspec validate add-script-execution-utilities --strict`：通过。
