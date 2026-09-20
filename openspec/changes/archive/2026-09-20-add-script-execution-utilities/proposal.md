## 背景与原因

项目已声明 Groovy 与 GraalVM JavaScript 依赖，但缺少统一的脚本调用入口。新增两种脚本工具，使调用方传入参数映射与脚本文本即可获得字符串结果。

## 变更内容

- 新增 `org.example.simple.util.GroovyScriptUtils` 和 `org.example.simple.util.GraalVMUtils`，均提供 `public static String execute(Map<String, Object> param, String script)`。
- 将参数映射绑定为脚本变量 `param`，支持多行语句、条件、循环和显式返回。
- 明确非字符串结果、空值及错误处理语义；统一使用中文异常描述。
- 每次执行使用独立上下文，执行结束释放引擎相关资源，避免并发调用串用变量。
- 补充 Java 21 下的双引擎测试与中文调用示例。

## 能力

### 新增能力

- `script-execution-utilities`：Groovy 与 JavaScript 脚本的参数绑定、执行、返回值转换及生命周期契约。

### 修改能力

无。

## 影响范围

- 新增两个工具类和共用的 `ScriptExecutionException`，测试放在对应的 `org.example.simple.util` 包。
- 优先复用当前 `build.gradle` 已声明的 Groovy `5.1.2`、GraalVM `25.2.4`；实施时检查依赖是否可解析及能否在 Java 21 运行，确有兼容问题再调整并同步文档。
- 本变更独立于正在实施的 Netty RPC 变更，不将该变更视为已完成或归档。
