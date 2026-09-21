package cn.nihility.gw.controller;

import cn.nihility.gw.service.FeignService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.Map;

/**
 * 示例接口，统一返回 Mono，由 WebFlux 订阅后再写回响应。
 */
@RestController
public class WelcomeController {

    private static final Logger log = LoggerFactory.getLogger(WelcomeController.class);

    @Autowired
    private FeignService feignService;

    /** 本地直接返回，不涉及下游调用。 */
    @GetMapping("/welcome")
    public Mono<Map<String, Object>> welcome() {
        // 用 fromSupplier 把日志和构造动作推迟到订阅时执行，保持副作用在响应式链路内
        return Mono.fromSupplier(() -> {
            log.info("/welcome call");
            return Collections.singletonMap("message", "WelcomeSuccess");
        });
    }

    /** 经 Feign 调用下游的 /welcome。 */
    @GetMapping("/feign/welcome")
    public Mono<Map<String, Object>> feignWelcome() {
        // doFirst 在订阅时触发，比在方法体里直接打印更贴合响应式的执行时机
        return feignService.welcome()
                .doFirst(() -> log.info("Controller feign /welcome call"));
    }

    /** 经 Feign 调用下游的 /api/welcome。 */
    @GetMapping("/feign/api/welcome")
    public Mono<Map<String, Object>> feignApiWelcome() {
        return feignService.apiWelcome()
                .doFirst(() -> log.info("Controller feign /api/welcome call"));
    }

}
