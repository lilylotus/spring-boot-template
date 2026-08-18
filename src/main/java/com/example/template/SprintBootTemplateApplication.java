package com.example.template;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@EnableDiscoveryClient
@MapperScan(basePackages = "com.example.template", annotationClass = Mapper.class)
@SpringBootApplication
public class SprintBootTemplateApplication {

    public static void main(String[] args) {
        // Nacos官方issue里记录的问题——Log4j2升级到较新版本后
        // Nacos客户端启动过程会触发这类包扫描警告
        // 本质是 Nacos 客户端自己内部有一套日志适配逻辑，跟新版Log4j2的初始化机制产生了冲突。
        System.setProperty("nacos.logging.default.config.enabled", "false");
        SpringApplication.run(SprintBootTemplateApplication.class, args);
    }

}
