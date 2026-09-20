# 脚本执行工具

`GroovyScriptUtils` 和 `GraalVMUtils` 位于 `org.example.simple.util`，均提供：

```java
public static String execute(Map<String, Object> param, String script)
```

## Groovy 语句脚本

```java
Map<String, Object> param = Map.of("name", "小明", "count", 3);
String result = GroovyScriptUtils.execute(param, """
    def count = param.get('count')
    if (count <= 0) {
        return '无需处理'
    }
    def total = 0
    for (int i = 1; i <= count; i++) {
        total += i
    }
    return "${param.get('name')}:${total}"
    """);
// 返回：小明:6
```

Groovy 支持显式 `return` 和最后一个表达式的结果，GString 自动转为 String。

## GraalVM JavaScript 语句脚本

```java
Map<String, Object> param = Map.of("name", "小明", "count", 3);
String result = GraalVMUtils.execute(param, """
    const count = Number(param.get('count'));
    if (count <= 0) {
        return '无需处理';
    }
    let total = 0;
    for (let i = 1; i <= count; i++) {
        total += i;
    }
    return param.get('name') + ':' + total;
    """);
// 返回：小明:6
```

JS 脚本作为函数体执行，必须通过 `return` 指定结果。`1 + 2;` 没有 return，返回 Java null。
`param` 是 Java Map，使用 `param.get('name')`，不要依赖 `param.name`。
嵌套 Map/List 可使用 `param.get('items').get(0)`；也可以调用参数中显式传入的 Java 对象的公开方法。

## 输入、结果与异常

| 情况 | 行为 |
| --- | --- |
| null 参数映射、null/空白脚本、null 键 | 抛出 `IllegalArgumentException` |
| 空映射或 null 参数值 | 允许 |
| 字符串、空字符串 | 原样返回 |
| 数字、布尔、字符、Groovy GString | 转换为 String |
| null、JS undefined、JS 无 return | 返回 Java null |
| Map/List、JS 对象/数组、函数或 Promise | 抛出 `ScriptExecutionException` |
| 语法、运行、转换、上下文初始化或资源关闭错误 | 中文 `ScriptExecutionException`，保留 cause |

需要 JSON 字符串时由脚本显式转换，例如 JS 使用：

```java
String json = GraalVMUtils.execute(Map.of("name", "小明"),
    "return JSON.stringify({name: param.get('name')});");
```

## 并发与生命周期

每次调用独立创建脚本绑定和执行上下文，结束时释放自身持有的类加载资源或 Context。顶层 Map 是浅副本：
脚本对顶层键的修改不影响传入映射，嵌套 Map、List 和 Java 对象仍共享引用，由调用方协调并发访问。
不应在执行期间并发修改正在复制的原始映射。

工具执行应用维护者提供的同步脚本，不实现不可信脚本沙箱、编译缓存或强制终止超时。
JavaScript 不提供 Node.js 模块或 Promise 等待。脚本可以访问传入对象的公开方法，因此应仅传入业务所需对象。

使用项目已有 Groovy `5.1.2` 与 GraalVM `25.2.4` 依赖，在 Java 21 下通过 Gradle 包装器构建。
无需额外安装 Nashorn 或配置 JSR-223 引擎名称。标准 JDK 上是否启用 GraalJS 即时编译取决于运行时，
不应将解释执行提示误认为脚本失败。

## 验证命令

```powershell
.\gradlew.bat test --tests '*GroovyScriptUtilsTest' --tests '*GraalVMUtilsTest'
.\gradlew.bat test
.\gradlew.bat build
```
