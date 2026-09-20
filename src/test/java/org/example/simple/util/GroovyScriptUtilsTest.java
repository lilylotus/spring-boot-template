package org.example.simple.util;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroovyScriptUtilsTest extends ScriptUtilsContractTest {

    @Override
    protected String execute(Map<String, Object> param, String script) {
        return GroovyScriptUtils.execute(param, script);
    }

    @Test
    void executesStatementsAndConvertsGString() {
        assertEquals("小明:6", execute(Map.of("name", "小明", "values", List.of(1, 2, 3)), """
            def total = 0
            for (value in param.get('values')) {
                if (value > 0) { total += value }
            }
            return "${param.get('name')}:${total}"
            """));
        assertEquals("隐式结果", execute(Map.of(), "'隐式结果'"));
        assertEquals("a", execute(Map.of(), "return 'a' as char"));
    }

    @Test
    void wrapsRuntimeFailureAndDoesNotShareBinding() {
        ScriptExecutionException error = assertThrows(ScriptExecutionException.class,
            () -> execute(Map.of(), "throw new IllegalStateException('测试异常')"));
        assertTrue(error.getMessage().contains("执行"));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals("局部", execute(Map.of(), "state = '局部'; return state"));
        assertEquals("false", execute(Map.of(), "return binding.hasVariable('state')"));
    }

    @Test
    void acceptsExplicitStructuredTextAndRejectsClosure() {
        assertEquals("{\"name\":\"小明\"}", execute(Map.of(), """
            return '{"name":"小明"}'
            """));
        assertThrows(ScriptExecutionException.class, () -> execute(Map.of(), "return { -> '闭包' }"));
    }
}
