package cn.nihility.gw.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(name = "SpringBoot4Template")
public interface Template4Client {

    @GetMapping("/welcome")
    Map<String, Object> welcome();

    @GetMapping("/api/welcome")
    Map<String, Object> apiWelcome();

}
