package cn.nihility.gw.route;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 application.yaml 中 /proxy 代理路由的配置能被正确绑定成 {@link RouteDefinition}。
 * <p>
 * 这条路由要真正跑通需要 Nacos 上有已注册的 BootDemo 实例，不适合做成自动化的端到端用例，
 * 因此这里只覆盖「配置写对了、能被 Gateway 绑定」这一层，转发行为由手工验证兜底。
 * <p>
 * 关闭 Nacos config / discovery 是为了让用例在 Nacos 不可达时也能跑——
 * {@code spring.config.import} 用的是 {@code optional:} 前缀，但 discovery 的心跳线程没必要在测试里起来。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
class ProxyRouteConfigTest {

    /** 路由 id，与 application.yaml 中的定义保持一致 */
    private static final String ROUTE_ID = "proxy-boot-demo";

    @Autowired
    private GatewayProperties gatewayProperties;

    /** 路由存在，且目标指向服务发现中的 BootDemo，而不是硬编码的实例地址。 */
    @Test
    void shouldBindProxyRouteToBootDemo() {
        RouteDefinition route = findProxyRoute();

        assertEquals("lb://BootDemo", route.getUri().toString(), "代理路由必须走服务发现，不能硬编码 host:port");
    }

    /** 谓词只匹配 /proxy 前缀，避免误接管网关自身的接口。 */
    @Test
    void shouldMatchOnlyProxyPathPrefix() {
        List<PredicateDefinition> predicates = findProxyRoute().getPredicates();

        assertEquals(1, predicates.size(), "代理路由只应配置 Path 一个谓词");
        PredicateDefinition path = predicates.get(0);
        assertEquals("Path", path.getName());
        // 简写形式的参数键是 Gateway 生成的 _genkey_0，这里只断言值，避免耦合内部命名
        assertTrue(path.getArgs().containsValue("/proxy/**"), "实际谓词参数: " + path.getArgs());
    }

    /**
     * 过滤器的数量与顺序。
     *
     * <p>顺序本身就是设计决定：限流排在熔断之前，超配额的请求根本没调用下游，不应该污染熔断的
     * 失败率样本；反过来排会让一次限流风暴把熔断也带开。所以这里断言的是下标，不是「包含」。</p>
     */
    @Test
    void shouldApplyFiltersInResilienceFirstOrder() {
        List<FilterDefinition> filters = findProxyRoute().getFilters();

        assertEquals(4, filters.size(), "代理路由应有 4 个过滤器，实际: " + filters);
        assertEquals("Resilience4jRateLimiter", filters.get(0).getName(), "限流必须排在最前");
        assertEquals("CircuitBreaker", filters.get(1).getName(), "熔断必须排在限流之后、转发改写之前");
        assertEquals("StripPrefix", filters.get(2).getName());
        assertEquals("PreserveHostHeader", filters.get(3).getName());
    }

    /** 限流过滤器指向 resilience4j 中配置的 RateLimiter 实例。 */
    @Test
    void shouldBindRateLimiterInstanceName() {
        FilterDefinition rateLimiter = findProxyRoute().getFilters().get(0);

        // 简写形式的参数键是 Gateway 生成的 _genkey_0，这里只断言值，避免耦合内部命名
        assertTrue(rateLimiter.getArgs().containsValue("bootDemoRateLimiter"),
                "实际参数: " + rateLimiter.getArgs());
    }

    /** 熔断过滤器的实例名、降级地址与失败状态码。 */
    @Test
    void shouldBindCircuitBreakerArgs() {
        Map<String, String> args = findProxyRoute().getFilters().get(1).getArgs();

        assertEquals("bootDemoCircuitBreaker", args.get("name"));
        assertEquals("forward:/fallback/boot-demo", args.get("fallbackUri"));
        // 不显式列出状态码的话，下游返回的 5xx 不会计入失败率，只有异常才算
        assertEquals("500,502,503,504", args.get("statusCodes"));
        // 降级地址不能落在 /proxy 下，否则会被代理路由再匹配一次形成回环
        assertFalse(args.get("fallbackUri").contains("/proxy"), "降级地址不能落在代理路由的匹配范围内");
    }

    /** 转发改写：剥掉 /proxy 前缀，保留调用方原始 Host 头。 */
    @Test
    void shouldStripPrefixAndPreserveHostHeader() {
        List<FilterDefinition> filters = findProxyRoute().getFilters();

        FilterDefinition stripPrefix = filters.get(2);
        assertEquals("StripPrefix", stripPrefix.getName());
        assertTrue(stripPrefix.getArgs().containsValue("1"), "实际参数: " + stripPrefix.getArgs());

        FilterDefinition preserveHost = filters.get(3);
        assertEquals("PreserveHostHeader", preserveHost.getName());
        assertTrue(preserveHost.getArgs().isEmpty(), "PreserveHostHeader 不接受参数");
    }

    /**
     * 从绑定后的网关配置中取出代理路由。
     *
     * @return id 为 {@value #ROUTE_ID} 的路由定义
     */
    private RouteDefinition findProxyRoute() {
        return gatewayProperties.getRoutes().stream()
                .filter(route -> ROUTE_ID.equals(route.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "未找到 id 为 " + ROUTE_ID + " 的路由，已绑定的路由: " + gatewayProperties.getRoutes()));
    }

}
