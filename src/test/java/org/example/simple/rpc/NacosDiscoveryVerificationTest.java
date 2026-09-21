package org.example.simple.rpc;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.example.simple.rpc.client.RpcClient;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.config.RpcConfig;
import org.example.simple.rpc.discovery.NacosServiceDiscovery;
import org.example.simple.rpc.server.RpcServer;
import org.example.simple.rpc.server.ServiceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Nacos 注册与发现的独立集成验证入口。
 * <p>
 * 该验证依赖外部 Nacos 实例，只有设置环境变量 {@code RPC_NACOS_SERVER_ADDR} 后才会执行；
 * 未设置时整个类被跳过，测试报告中记录为 skipped，不会被当作已验证通过。
 *
 * <p>所需环境变量：
 * <ul>
 *   <li>{@code RPC_NACOS_SERVER_ADDR}：Nacos 服务地址，例如 {@code 127.0.0.1:8848}</li>
 *   <li>{@code RPC_NACOS_NAMESPACE}：命名空间，可选</li>
 *   <li>{@code RPC_NACOS_USERNAME} 与 {@code RPC_NACOS_PASSWORD}：鉴权凭证，可选</li>
 *   <li>{@code RPC_NACOS_GROUP}：分组，默认 {@code DEFAULT_GROUP}</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(
    named = "RPC_NACOS_SERVER_ADDR",
    matches = ".+",
    disabledReason = "未配置 RPC_NACOS_SERVER_ADDR，Nacos 集成验证未执行")
class NacosDiscoveryVerificationTest {

    private static final String SERVICE_NAME = "问候服务";
    private static final String VERSION = "v1";

    @Test
    void registersAndDiscoversServiceThroughNacos() throws Exception {
        RpcConfig config = RpcConfig.builder()
            .requestTimeoutMillis(5000)
            .drainTimeoutMillis(2000)
            .shutdownTimeoutMillis(2000)
            .build();
        ServiceRegistry registry = new ServiceRegistry();
        registry.register(SERVICE_NAME, GreetingService.class, (GreetingService) () -> "Nacos");

        try (NacosServiceDiscovery discovery = new NacosServiceDiscovery(properties(), group(), VERSION);
             RpcServer server = new RpcServer(registry, config);
             RpcClient client = new RpcClient(config)) {
            server.startRegistered("127.0.0.1", 0, "127.0.0.1", discovery, SERVICE_NAME);
            client.subscribe(SERVICE_NAME, discovery);

            assertTrue(awaitReady(client), "订阅后未能在超时时间内取得可用实例");
            assertEquals("Nacos", invoke(client));
        }
    }

    private static Properties properties() {
        Properties properties = new Properties();
        properties.put("serverAddr", System.getenv("RPC_NACOS_SERVER_ADDR"));
        putIfPresent(properties, "namespace", "RPC_NACOS_NAMESPACE");
        putIfPresent(properties, "username", "RPC_NACOS_USERNAME");
        putIfPresent(properties, "password", "RPC_NACOS_PASSWORD");
        return properties;
    }

    private static void putIfPresent(Properties properties, String key, String variable) {
        String value = System.getenv(variable);
        if (value != null && !value.isBlank()) {
            properties.put(key, value);
        }
    }

    private static String group() {
        String group = System.getenv("RPC_NACOS_GROUP");
        return group == null || group.isBlank() ? "DEFAULT_GROUP" : group;
    }

    private static String invoke(RpcClient client) {
        return client.invoke(SERVICE_NAME, "greet", String.class, new Class<?>[0], new Object[0]);
    }

    private static boolean awaitReady(RpcClient client) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try {
                invoke(client);
                return true;
            } catch (RpcException ignored) {
                Thread.sleep(200);
            }
        }
        return false;
    }

    /**
     * 验证用问候服务。
     */
    interface GreetingService {

        /** 返回固定问候内容。 */
        String greet();
    }
}
