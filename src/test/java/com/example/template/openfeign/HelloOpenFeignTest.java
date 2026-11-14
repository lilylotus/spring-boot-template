package com.example.template.openfeign;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

/**
 * {@link HelloOpenFeign} 的真实远程调用测试：通过Nacos服务发现找到名为BootDemo的下游服务并调用其
 * {@code /welcome} 接口。
 * <p>
 * 测试默认跳过，只有显式设置环境变量 {@code OPENFEIGN_INTEGRATION_ENABLED=true} 后才执行(做法与
 * {@code RedisDistributedLockIntegrationTest} 一致)：它要求Nacos中已经注册了可用的BootDemo实例，
 * 这个条件在本地和CI环境通常不成立，默认执行会让全量构建因为"下游不可用(503)"而失败，
 * 而这种失败与代码是否正确无关。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENFEIGN_INTEGRATION_ENABLED", matches = "true")
class HelloOpenFeignTest {

    @Autowired
    HelloOpenFeign helloOpenFeign;

    @Test
    void welcome() {
        Map<String, Object> welcome = helloOpenFeign.welcome();
        Assertions.assertNotNull(welcome);
        System.out.println(welcome);
    }
}
