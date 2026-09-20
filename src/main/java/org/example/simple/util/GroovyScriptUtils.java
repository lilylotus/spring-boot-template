package org.example.simple.util;

import java.util.Map;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * 同步执行由应用维护者提供的 Groovy 语句脚本，每次调用独立创建绑定与脚本类加载器。
 * 该工具不提供不可信脚本沙箱或强制执行超时。
 */
public final class GroovyScriptUtils {

    private GroovyScriptUtils() {
    }

    /**
     * 使用变量 {@code param} 执行脚本，例如 {@code return param.get('name')}。
     * 支持显式 return 及 Groovy 最后表达式结果；字符串、GString、数字、布尔和字符转为 String，
     * null 保持为 null，复杂结果必须在脚本内显式转换。
     * <p>
     * 每次执行复制顶层映射，脚本修改顶层键不会影响原映射；嵌套对象仍共享引用，调用方负责并发协调。
     *
     * @param param 参数映射，允许空映射及 null 值，不允许 null 映射或 null 键
     * @param script 非空白的完整 Groovy 脚本
     * @return 计算结果的字符串表示，或 null
     * @throws IllegalArgumentException 输入不合法
     * @throws ScriptExecutionException 脚本编译、执行、转换或资源管理失败，保留原始原因
     */
    public static String execute(Map<String, Object> param, String script) {
        Map<String, Object> parameters = ScriptSupport.copyParameters(param, script);
        String phase = "初始化";
        try {
            Binding binding = new Binding();
            binding.setVariable("param", parameters);
            GroovyShell shell = new GroovyShell(GroovyScriptUtils.class.getClassLoader(), binding);
            try (GroovyClassLoader loader = shell.getClassLoader()) {
                phase = "编译";
                Script executable = shell.parse(script, "用户脚本.groovy");
                phase = "执行";
                Object result = executable.run();
                phase = "结果转换";
                String text = ScriptSupport.scalarToString(result);
                phase = "资源关闭";
                return text;
            }
        } catch (Exception exception) {
            throw new ScriptExecutionException("Groovy 脚本" + phase + "失败", exception);
        }
    }
}
