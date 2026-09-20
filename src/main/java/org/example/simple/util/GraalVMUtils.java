package org.example.simple.util;

import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * 通过 GraalJS 同步执行 JavaScript 函数体，每次调用独立创建和关闭 Context。
 * 仅用于应用维护者提供的脚本，不提供不可信脚本沙箱或强制执行超时。
 */
public final class GraalVMUtils {

    private GraalVMUtils() {
    }

    /**
     * 将脚本作为函数体执行，例如 {@code return param.get('name');}。
     * 参数是 Java Map，使用 {@code param.get('key')} 访问；不承诺 {@code param.key} 语义。
     * 字符串及标量按 JavaScript 语义转为 String，null、undefined 和无 return 映射为 Java null。
     * 对象、数组、函数和 Promise 不自动转换或等待，应在脚本中显式生成字符串。
     * <p>
     * 每次执行只复制顶层映射，嵌套对象仍共享引用。脚本可调用显式传入 Java 对象的公开成员，
     * 调用方负责对象访问和并发协调。结果在 Context 关闭前完成转换。
     *
     * @param param 参数映射，允许空映射及 null 值，不允许 null 映射或 null 键
     * @param script 非空白的 JavaScript 函数体，使用 return 指定结果
     * @return 计算结果的字符串表示，或 null
     * @throws IllegalArgumentException 输入不合法
     * @throws ScriptExecutionException 引擎、脚本、转换或资源管理失败，保留原始原因
     */
    public static String execute(Map<String, Object> param, String script) {
        Map<String, Object> parameters = ScriptSupport.copyParameters(param, script);
        String phase = "初始化";
        try (Context context = Context.newBuilder("js")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(name -> false)
            .build()) {
            Value stringify = context.eval("js", "((stringify) => value => stringify(value))(String)");
            phase = "编译";
            Source source = Source.newBuilder("js", "(function(param) {\n" + script + "\n})", "用户脚本.js")
                .buildLiteral();
            Value executable = context.eval(source);
            phase = "执行";
            Value result = executable.execute(parameters);
            phase = "结果转换";
            String text = scalarToString(result, stringify);
            phase = "资源关闭";
            return text;
        } catch (Exception exception) {
            throw new ScriptExecutionException("JavaScript 脚本" + phase + "失败", exception);
        }
    }

    private static String scalarToString(Value value, Value stringify) {
        if (value.isNull()) {
            return null;
        }
        if (value.isString() || value.isBoolean() || value.isNumber()
            || value.isHostObject() && ScriptSupport.isScalar(value.asHostObject())) {
            return stringify.execute(value).asString();
        }
        throw new IllegalArgumentException("脚本必须返回字符串、标量或空值，请在脚本内显式转换复杂结果");
    }
}
