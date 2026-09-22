package cn.nihility.gw.resilience;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 降级响应的公共断言，熔断和限流两条路径共用。
 *
 * <p>spec 要求两种拦截返回结构一致、且不泄漏内部细节，这两点在两个集成测试里都要验，
 * 收在这里避免各写一份写出分歧。</p>
 */
final class DegradeResponseAssertions {

    /** 不该出现在降级响应里的字样：堆栈、框架内部类名、下游实例地址。 */
    private static final List<String> FORBIDDEN = List.of(
            "Exception",
            "at org.springframework",
            "at io.github.resilience4j",
            "java.lang.",
            "reactor.core",
            "localhost:",
            "Caused by");

    private DegradeResponseAssertions() {
    }

    /**
     * 断言降级响应体里没有内部实现细节。
     *
     * @param body 响应体原始字节
     */
    static void assertNoInternalDetails(byte[] body) {
        assertNotNull(body, "降级响应必须有响应体");
        String text = new String(body, StandardCharsets.UTF_8);
        for (String forbidden : FORBIDDEN) {
            assertFalse(text.contains(forbidden),
                    "降级响应不应包含 [" + forbidden + "]，实际响应体: " + text);
        }
    }

}
