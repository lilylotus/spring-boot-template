package org.example.simple.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 脚本工具的公共输入和返回值处理。 */
final class ScriptSupport {

    private ScriptSupport() {
    }

    static Map<String, Object> copyParameters(Map<String, Object> param, String script) {
        if (param == null) {
            throw new IllegalArgumentException("脚本参数映射不能为空");
        }
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("脚本文本不能为空或空白");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : param.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("脚本参数键必须是非空引用的字符串");
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    static boolean isScalar(Object value) {
        return value instanceof CharSequence || value instanceof Character
            || value instanceof Number || value instanceof Boolean;
    }

    static String scalarToString(Object value) {
        if (value == null) {
            return null;
        }
        if (!isScalar(value)) {
            throw new IllegalArgumentException("脚本必须返回字符串、标量或空值，请在脚本内显式转换复杂结果");
        }
        return value.toString();
    }
}
