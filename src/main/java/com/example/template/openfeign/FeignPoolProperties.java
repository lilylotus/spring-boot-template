package com.example.template.openfeign;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@Getter
@Setter
@RefreshScope
// 现在的写法(整体绑定到一个Properties类)
@ConfigurationProperties(prefix = "feign.pool")
public class FeignPoolProperties {

    private int maxTotal = 200;

    private int maxPerRoute = 50;

}
