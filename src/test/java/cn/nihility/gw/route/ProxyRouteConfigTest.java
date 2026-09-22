package cn.nihility.gw.route;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** 过滤器顺序与内容：先剥掉 /proxy 前缀，再保留调用方原始 Host 头。 */
    @Test
    void shouldStripPrefixAndPreserveHostHeader() {
        List<FilterDefinition> filters = findProxyRoute().getFilters();

        assertEquals(2, filters.size(), "代理路由应只有 StripPrefix 和 PreserveHostHeader 两个过滤器");

        FilterDefinition stripPrefix = filters.get(0);
        assertEquals("StripPrefix", stripPrefix.getName());
        assertTrue(stripPrefix.getArgs().containsValue("1"), "实际参数: " + stripPrefix.getArgs());

        FilterDefinition preserveHost = filters.get(1);
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
