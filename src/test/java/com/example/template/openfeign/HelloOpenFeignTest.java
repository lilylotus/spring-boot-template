package com.example.template.openfeign;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

@SpringBootTest
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
