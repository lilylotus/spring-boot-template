package com.example.template.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.template.response.RestResult;
import com.example.template.response.RestResultUtils;
import com.example.template.validation.ValidationExampleRequest;

/**
 * Jakarta Validation 使用示例接口，演示请求体字段校验和控制器方法参数校验。
 */
@RestController
@RequestMapping("/api/validation")
@Tag(name = "参数校验示例接口", description = "演示Jakarta Validation常用约束和统一错误响应")
public class ValidationExampleController {

    /**
     * 校验包含常用约束注解的请求体，全部通过后原样返回请求数据。
     *
     * @param request 待校验的示例请求
     * @return 包含已校验请求数据的统一成功响应
     */
    @PostMapping("/example")
    @Operation(summary = "请求体校验示例", description = "演示常用Jakarta Validation字段约束")
    public RestResult<ValidationExampleRequest> validateRequest(
        @Valid @RequestBody ValidationExampleRequest request) {
        return RestResultUtils.success(request);
    }

    /**
     * 校验查询参数必须为正数，用于演示控制器方法参数约束异常的处理方式。
     *
     * @param value 待校验的正整数
     * @return 包含已校验数值的统一成功响应
     */
    @GetMapping("/positive")
    @Operation(summary = "方法参数校验示例", description = "演示查询参数上的@Positive约束")
    public RestResult<Integer> validatePositive(
        @Parameter(description = "必须为正数", example = "1")
        @RequestParam
        @Positive(message = "value必须为正数") Integer value) {
        return RestResultUtils.success(value);
    }
}
