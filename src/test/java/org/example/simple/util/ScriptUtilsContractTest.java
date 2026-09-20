package org.example.simple.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 两种脚本工具共用的公开行为契约。 */
abstract class ScriptUtilsContractTest {

    protected abstract String execute(Map<String, Object> param, String script);

    @Test
    void returnsStringsScalarsAndNull() {
        assertEquals("中文", execute(Map.of(), "return '中文'"));
        assertEquals("", execute(Map.of(), "return ''"));
        assertEquals("12", execute(Map.of(), "return 12"));
        assertEquals("12.5", execute(Map.of(), "return 12.5"));
        assertEquals("true", execute(Map.of(), "return true"));
        assertEquals("false", execute(Map.of(), "return false"));
        assertNull(execute(Map.of(), "return null"));
    }

    @Test
    void bindsParametersAsDataAndSupportsNestedValues() {
        String text = "中文'; return '不应执行'; //\n换行";
        assertEquals(text, execute(Map.of("text", text), "return param.get('text')"));
        assertEquals("末项", execute(Map.of("nested", Map.of("items", List.of("首项", "末项"))),
            "return param.get('nested').get('items').get(1)"));
        assertEquals("false", execute(Map.of("enabled", false), "return param.get('enabled')"));
        assertEquals("12", execute(Map.of("count", 12), "return param.get('count')"));
        Map<String, Object> nullable = new HashMap<>();
        nullable.put("value", null);
        assertNull(execute(nullable, "return param.get('value')"));
        assertNull(execute(Map.of(), "return param.get('missing')"));
    }

    @Test
    void invokesPublicMethodOnExplicitlyPassedJavaObject() {
        assertEquals("你好，小明", execute(Map.of("person", new Person("小明")),
            "return param.get('person').greet()"));
    }

    @Test
    void rejectsInvalidInputsBeforeScriptExecution() {
        assertThrows(IllegalArgumentException.class, () -> execute(null, "return 1"));
        assertThrows(IllegalArgumentException.class, () -> execute(Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> execute(Map.of(), " \n\t"));
        Map<String, Object> invalid = new HashMap<>();
        invalid.put(null, "不允许空键");
        assertThrows(IllegalArgumentException.class, () -> execute(invalid, "return 1"));
    }

    @Test
    void rejectsComplexReturnValueAndPreservesCause() {
        ScriptExecutionException error = assertThrows(ScriptExecutionException.class,
            () -> execute(Map.of("value", List.of(1, 2)), "return param.get('value')"));
        assertTrue(error.getMessage().contains("结果转换"));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertThrows(ScriptExecutionException.class,
            () -> execute(Map.of("value", Map.of("name", "测试")), "return param.get('value')"));
    }

    @Test
    void wrapsSyntaxFailureAndRecoversForNextCall() {
        ScriptExecutionException error = assertThrows(ScriptExecutionException.class,
            () -> execute(Map.of(), "return ("));
        assertNotNull(error.getCause());
        assertTrue(error.getMessage().contains("编译"));
        assertEquals("后续正常", execute(Map.of(), "return '后续正常'"));
    }

    @Test
    void copiesTopLevelMapButKeepsNestedReferences() {
        Map<String, Object> nested = new HashMap<>();
        Map<String, Object> original = new HashMap<>();
        original.put("name", "原值");
        original.put("nested", nested);
        assertEquals("新值", execute(original, """
            param.put('name', '新值');
            param.put('newKey', '仅副本');
            param.get('nested').put('shared', '共享');
            return param.get('name');
            """));
        assertEquals("原值", original.get("name"));
        assertFalse(original.containsKey("newKey"));
        assertEquals("共享", nested.get("shared"));
    }

    @Test
    void isolatesConcurrentBindings() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                int value = index;
                results.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return execute(Map.of("id", value),
                        "param.put('local', param.get('id')); return param.get('local')");
                }));
            }
            start.countDown();
            for (int index = 0; index < results.size(); index++) {
                assertEquals(Integer.toString(index), results.get(index).get(30, TimeUnit.SECONDS));
            }
        }
    }

    /** 明确传入脚本的测试 Java 对象。 */
    public static final class Person {
        private final String name;
        public Person(String name) { this.name = name; }
        public String greet() { return "你好，" + name; }
    }
}
