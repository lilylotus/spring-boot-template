package com.example.template.openfeign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@FeignClient(name = "boot", url = "http://127.0.0.1:30040")
public interface HelloOpenFeign {

    @GetMapping("/welcome")
    Map<String, Object> welcome();

}
