## 背景

项目采用 Java 21，通用工具位于 `org.example.simple.util`。构建文件已包含 Groovy 与 GraalVM Polyglot/JS 依赖，当前尚无脚本工具。本次为独立新增能力，实施前由用户确认文档。

## 目标与非目标

提供两个无状态工具类，以 `Map<String, Object>` 和脚本文本执行同步逻辑，返回 `String`。调用方无需自行创建 Binding、引擎或上下文。

首期不提供编译缓存、异步脚本、Node.js 模块、超时强制终止、远程脚本加载或不可信脚本沙箱。脚本来自应用维护者；这是与 Java 对象交互的进程内执行入口。

## 技术决策

### 接口与参数

两个类均为 `final`，使用私有构造器，仅提供静态方法：

```java
public static String execute(Map<String, Object> param, String script)
```

包内辅助类 `ScriptSupport` 复用映射校验、浅复制及 Java 标量转换，不作为公开 API。

`param == null`、`script == null` 或空白脚本均抛出带中文描述的 `IllegalArgumentException`。空映射合法，映射值允许为 null；键必须是非 null 字符串。复制顶层映射为独立 `LinkedHashMap`，不使用拒绝空值的 `Map.copyOf`。脚本变量名固定为 `param`，不将所有键展开为全局变量，避免键名与引擎保留名称冲突。

两种脚本均使用 `param.get("name")` 访问映射；Groovy 可额外使用自身 Map 属性语法，不承诺 JavaScript 中 `param.name` 等价。支持数字、布尔、中文、嵌套 Map/List 和调用方显式传入的 Java 对象。复制只隔离顶层键的增删改，嵌套对象仍共享引用；调用方须自行协调共享对象的并发访问。

### Groovy 执行

每次创建独立 `Binding`、`GroovyShell` 和该次脚本的类加载资源，绑定 `param` 后执行完整脚本。支持显式 `return` 及 Groovy 最后一条表达式的返回语义。结果在类加载器清理前转成 String，最后关闭该次执行拥有的 GroovyClassLoader，不关闭应用父类加载器。

不共享 `Binding` 或 `Script` 实例。Groovy 官方说明 Binding 不保证线程安全，因此首期选择每次独立执行，避免引入缓存失效和共享状态问题。[Groovy 集成文档](https://docs.groovy-lang.org/latest/html/documentation/guide-integrating.html)

### GraalVM JavaScript 执行

使用 `org.graalvm.polyglot.Context`，不依赖 JSR-223 的引擎名称发现或额外 `js-scriptengine`。每次使用 try-with-resources 创建并关闭独立 JS Context；在关闭前完成结果转换，不能返回依赖已关闭 Context 的 Value。

将脚本作为 JavaScript 函数体封装为 `(function(param) { ... })`，随后通过 Polyglot `Value.execute` 传入参数副本。不把参数值拼接到源码中。函数体可直接包含变量声明、条件、循环及 `return`；函数体没有 return 时得到 undefined，映射为 Java null。纯表达式脚本如 `param.get("x") + 1` 不隐式返回，示例统一使用显式 return，避免与 Groovy 语义混淆。

采用 `HostAccess.ALL` 允许脚本使用已传入对象的公开成员和 Map/List 方法；不调用 `allowAllAccess(true)`，不开放任意宿主类查找或显式授权文件、进程和线程创建能力。该设置不构成安全沙箱：被传入对象的公开方法仍可能具有副作用。无需用户安装专用 GraalVM JDK；实施时在项目 Java 21 环境验证依赖及引擎启动，依赖不兼容时先调整文档与版本。

官方建议使用 Context 获得直接的嵌入控制；Java 类型互操作按 Polyglot API 实现。[GraalJS 引擎说明](https://www.graalvm.org/dev/reference-manual/js/ScriptEngine/)、[Java 互操作](https://www.graalvm.org/jdk21/reference-manual/js/JavaInteroperability/)

### 返回值与异常

- 字符串原样返回，空字符串不变；Groovy GString 转为普通 String。
- null 返回 Java null；JS undefined 同样返回 Java null。
- 数字、布尔、字符等标量按所属语言的字符串语义转换，JS 在 Context 内完成转换。
- Map、List、JS 对象/数组、函数和 Promise 等复杂结果不自动生成 JSON，抛出 `ScriptExecutionException`；需要结构化输出时由脚本显式生成字符串。Promise 不进行异步等待。
- 语法错误、脚本运行异常和结果转换失败统一包装为 `ScriptExecutionException extends RuntimeException`，中文消息注明语言及失败类别，保留原始 cause。不捕获并吞掉 JVM 致命 Error。
- 工具不主动记录脚本文本、参数内容或打印堆栈；调用方决定异常日志策略。无结果与失败不能都用空字符串表示。

### 示例

```java
Map<String, Object> param = Map.of("name", "小明", "count", 2);
String groovyResult = GroovyScriptUtils.execute(param,
    "return '你好，' + param.get('name') + '，次数=' + param.get('count')");
String jsResult = GraalVMUtils.execute(param,
    "return '你好，' + param.get('name') + '，次数=' + param.get('count');");
```

两者返回 `你好，小明，次数=2`。若 Map 数字值的 JavaScript 数值转换需要显式化，示例使用 `Number(param.get('count'))` 并在测试中核验。

## 风险与取舍

- 每次启动上下文和编译增加开销 → 首期优先保证隔离与资源生命周期正确，后续根据实际调用频率另行设计有界缓存。
- Groovy 与 JavaScript 数值及最后表达式语义不同 → 明确差异，测试使用显式 return 与必要的 Number 转换，不假设两种语言源码通用。
- 任意 Java 对象无法通用深拷贝 → 顶层复制、嵌套共享，文档明确并发责任。
- 进程内脚本可持续占用线程 → 本次不声明强制终止能力，调用方只执行自有可终止脚本。

## 迁移计划

新增 API，无存量调用迁移。用户确认后核验依赖，实现两工具和异常类，运行专项及全量测试，补充使用文档。不修改当前其他工作中的代码或将其标记完成。

## 待确认事项

当前方案选择方法名 `execute`、空结果返回 null、JS 脚本为含 return 的函数体。用户可在审阅时调整这些调用约定。
