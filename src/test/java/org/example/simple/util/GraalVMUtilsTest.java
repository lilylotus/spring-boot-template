package org.example.simple.util;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GraalVMUtilsTest extends ScriptUtilsContractTest {

    @Override
    protected String execute(Map<String, Object> param, String script) {
        return GraalVMUtils.execute(param, script);
    }

    @Test
    void executesStatementsAndUsesExplicitReturn() {
        assertEquals("小明:6", execute(Map.of("name", "小明", "values", List.of(1, 2, 3)), """
            let total = 0;
            const values = param.get('values');
            for (let i = 0; i < values.size(); i++) {
                const value = Number(values.get(i));
                if (value > 0) { total += value; }
            }
            return param.get('name') + ':' + total;
            """));
        assertNull(execute(Map.of(), "1 + 2;"));
        assertNull(execute(Map.of(), "return undefined;"));
        assertEquals("12345678901234567890", execute(Map.of(), "return 12345678901234567890n;"));
    }

    @Test
    void wrapsRuntimeFailureAndDoesNotShareGlobalVariables() {
        ScriptExecutionException error = assertThrows(ScriptExecutionException.class,
            () -> execute(Map.of(), "throw new Error('测试异常');"));
        assertTrue(error.getMessage().contains("执行"));
        assertNotNull(error.getCause());
        assertEquals("局部", execute(Map.of(), "globalThis.state = '局部'; return state;"));
        assertEquals("undefined", execute(Map.of(), "return typeof globalThis.state;"));
    }

    @Test
    void acceptsExplicitJsonAndRejectsObjectsFunctionsAndPromises() {
        assertEquals("{\"name\":\"小明\"}", execute(Map.of("name", "小明"),
            "return JSON.stringify({name: param.get('name')});"));
        for (String value : List.of("({name: '小明'})", "[1, 2]", "() => '函数'", "Promise.resolve('异步')")) {
            assertThrows(ScriptExecutionException.class, () -> execute(Map.of(), "return " + value + ";"));
        }
    }

    @Test
    void doesNotEnableArbitraryHostClassLookup() {
        assertThrows(ScriptExecutionException.class,
            () -> execute(Map.of(), "return Java.type('java.lang.System').getProperty('java.version');"));
    }
}
