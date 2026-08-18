package com.example.template.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("My Service API")
                .version("1.0.0")
                .description("生产环境接口文档")
                .contact(new Contact()
                    .name("运维团队")
                    .email("ops@example.com")))
            .servers(List.of(
                new Server().url("https://api.example.com").description("生产环境"),
                new Server().url("https://staging-api.example.com").description("预发环境")
            ));
    }
}
