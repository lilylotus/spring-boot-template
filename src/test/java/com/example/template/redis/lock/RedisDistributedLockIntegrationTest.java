package com.example.template.redis.lock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 分布式锁真实并发集成测试。
 * <p>
 * 测试默认跳过，只有显式设置 {@code REDIS_LOCK_INTEGRATION_ENABLED=true} 后才会连接由环境变量指定的独立 Redis。
 * 所有键均使用每次运行生成的专用前缀，清理范围仅限本测试创建的精确键。
 */
@EnabledIfEnvironmentVariable(named = "REDIS_LOCK_INTEGRATION_ENABLED", matches = "true")
class RedisDistributedLockIntegrationTest {

    private static final Duration WAIT_TIME = Duration.ofSeconds(1);
    private static final Duration LEASE_TIME = Duration.ofSeconds(3);
    private static final Duration RENEWAL_INTERVAL = Duration.ofSeconds(1);

    private final String keyPrefix = "lock:integration:" + UUID.randomUUID() + ":";
    private final List<Client> clients = new ArrayList<>();

    private Client firstClient;
    private Client secondClient;

    /**
     * 为每个场景创建两个独立 Redis 客户端，模拟不同应用实例。
     */
    @BeforeEach
    void setUp() {
        firstClient = createClient();
        secondClient = createClient();
    }

    /**
     * 关闭客户端并只删除本次测试明确创建的锁键。
     */
    @AfterEach
    void tearDown() {
        for (Client client : clients) {
            client.template().delete(keyPrefix + "same");
            client.template().delete(keyPrefix + "different-a");
            client.template().delete(keyPrefix + "different-b");
            client.template().delete(keyPrefix + "renew");
            client.template().delete(keyPrefix + "expired");
            client.close();
        }
    }

    /**
     * 验证两个客户端并发竞争同一业务键时最多一个持有者成功。
     */
    @Test
    void shouldAllowOnlyOneClientToAcquireSameKey() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        boolean[] results = new boolean[2];
        Thread first = new Thread(() -> results[0] = tryAcquireAfterSignal(firstClient, "same", ready, start));
        Thread second = new Thread(() -> results[1] = tryAcquireAfterSignal(secondClient, "same", ready, start));
        first.start();
        second.start();

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        first.join(5_000);
        second.join(5_000);

        assertTrue(results[0] ^ results[1]);
    }

    /**
     * 验证不同业务键不会因同一客户端已经持锁而互相阻塞。
     */
    @Test
    void shouldAcquireDifferentKeysIndependently() throws Exception {
        RedisLockHandle firstHandle = firstClient.service().tryLock("different-a", WAIT_TIME, LEASE_TIME).orElseThrow();
        RedisLockHandle secondHandle = secondClient.service().tryLock("different-b", WAIT_TIME, LEASE_TIME).orElseThrow();

        assertTrue(firstClient.service().unlock(firstHandle));
        assertTrue(secondClient.service().unlock(secondHandle));
    }

    /**
     * 验证主动释放后，另一个客户端可以接手同一业务键。
     */
    @Test
    void shouldAllowAnotherClientToAcquireAfterRelease() throws Exception {
        RedisLockHandle firstHandle = firstClient.service().tryLock("same", WAIT_TIME, LEASE_TIME).orElseThrow();
        assertTrue(firstClient.service().unlock(firstHandle));

        RedisLockHandle secondHandle = secondClient.service().tryLock("same", WAIT_TIME, LEASE_TIME).orElseThrow();
        assertTrue(secondClient.service().unlock(secondHandle));
    }

    /**
     * 验证自动续期使持有时间超过初始租期后，其他客户端仍不能接手。
     */
    @Test
    void shouldRenewLockBeforeLeaseExpires() throws Exception {
        RedisLockHandle handle = firstClient.service().tryLock("renew", WAIT_TIME, LEASE_TIME).orElseThrow();

        Thread.sleep(LEASE_TIME.plusSeconds(1).toMillis());

        assertTrue(secondClient.service().tryLock("renew", Duration.ZERO, LEASE_TIME).isEmpty());
        assertTrue(firstClient.service().unlock(handle));
    }

    /**
     * 验证已停止续期的旧句柄在租期结束后不能删除新持有者的锁。
     */
    @Test
    void shouldNotDeleteNewOwnerLockWithExpiredHandle() throws Exception {
        RedisLockHandle oldHandle = firstClient.service().tryLock("expired", WAIT_TIME, LEASE_TIME).orElseThrow();
        oldHandle.cancelRenewal();
        Thread.sleep(LEASE_TIME.plusMillis(300).toMillis());

        RedisLockHandle newHandle = secondClient.service().tryLock("expired", WAIT_TIME, LEASE_TIME).orElseThrow();
        assertFalse(firstClient.service().unlock(oldHandle));
        assertTrue(secondClient.service().unlock(newHandle));
    }

    /**
     * 等待并发起同一时刻的非阻塞抢锁，测试线程异常时按失败处理。
     */
    private boolean tryAcquireAfterSignal(Client client, String key, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                return false;
            }
            return client.service().tryLock(key, Duration.ZERO, LEASE_TIME).isPresent();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 创建一个独立连接工厂、模板及续期调度器，模拟单独应用实例。
     */
    private Client createClient() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
            requiredEnvironment("REDIS_LOCK_TEST_HOST"),
            Integer.parseInt(requiredEnvironment("REDIS_LOCK_TEST_PORT")));
        String password = System.getenv("REDIS_LOCK_TEST_PASSWORD");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(password);
        }

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("redis-lock-integration-");
        scheduler.initialize();

        RedisLockProperties properties = new RedisLockProperties();
        properties.setKeyPrefix(keyPrefix);
        properties.setRenewalInterval(RENEWAL_INTERVAL);
        RedisDistributedLockService service = new RedisDistributedLockService(template, scheduler, properties, "integration");
        Client client = new Client(connectionFactory, template, scheduler, service);
        clients.add(client);
        return client;
    }

    /**
     * 获取必需环境变量，避免测试误连项目默认 Redis。
     */
    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("启用 Redis 集成测试时必须设置环境变量: " + name);
        }
        return value;
    }

    /**
     * 一个独立测试客户端持有的全部资源，确保场景结束后释放连接与调度线程。
     */
    private record Client(
        LettuceConnectionFactory connectionFactory,
        StringRedisTemplate template,
        ThreadPoolTaskScheduler scheduler,
        RedisDistributedLockService service) {

        /** 关闭当前客户端的调度器和 Redis 连接工厂。 */
        private void close() {
            scheduler.shutdown();
            connectionFactory.destroy();
        }
    }
}
