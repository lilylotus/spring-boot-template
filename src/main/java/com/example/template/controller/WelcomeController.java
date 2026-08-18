package com.example.template.controller;

import com.example.template.openfeign.HelloOpenFeign;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@Tag(name = "SpringBoot模版测试接口", description = "业务测试接口")
public class WelcomeController {

    @Autowired
    private HelloOpenFeign helloOpenFeign;

    @GetMapping("/welcome")
    @Operation(summary = "welcome接口", description = "OpenFeign /welcome接口")
    public Map<String, Object> welcome() {
        return helloOpenFeign.welcome();
    }

    @GetMapping("/echo")
    @Operation(summary = "回声接口", description = "返回请求参数")
    public Map<String, Object> echo(
        @Parameter(description = "页码，默认第 1 页")
        @RequestParam(required = false, defaultValue = "1") Long page) {
        return Collections.singletonMap("page", page);
    }

}
