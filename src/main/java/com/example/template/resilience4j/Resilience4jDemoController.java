package com.example.template.resilience4j;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * resilience4j 断路器/重试/限流器/隔板四种容错能力的演示接口。
 * <p>
 * 仅用于手动验证各注解的行为，不代表真实业务接口。断路器、重试的效果通过连续多次调用即可观察；
 * 限流器、隔板的拒绝效果则需要在短时间内并发调用同一端点多次才能触发——单次串行调用看不出限流/
 * 隔板生效，因为只有同一时间窗口/并发数内的请求数被突破时才会触发拒绝。
 */
@RestController
@Tag(name = "resilience4j示例接口", description = "断路器/重试/限流器/隔板用法演示")
public class Resilience4jDemoController {

    private final CircuitBreakerDemoService circuitBreakerDemoService;
    private final RetryDemoService retryDemoService;
    private final RateLimiterDemoService rateLimiterDemoService;
    private final BulkheadDemoService bulkheadDemoService;

    public Resilience4jDemoController(CircuitBreakerDemoService circuitBreakerDemoService,
                                       RetryDemoService retryDemoService,
                                       RateLimiterDemoService rateLimiterDemoService,
                                       BulkheadDemoService bulkheadDemoService) {
        this.circuitBreakerDemoService = circuitBreakerDemoService;
        this.retryDemoService = retryDemoService;
        this.rateLimiterDemoService = rateLimiterDemoService;
        this.bulkheadDemoService = bulkheadDemoService;
    }

    @GetMapping("/api/resilience4j/circuit-breaker")
    @Operation(summary = "断路器示例", description = "连续多次调用可观察到从正常调用切换为熔断降级的过程")
    public String circuitBreaker() {
        return circuitBreakerDemoService.callUnstableService();
    }

    @GetMapping("/api/resilience4j/retry")
    @Operation(summary = "重试示例(重试后成功)", description = "调用一个前两次失败、第三次成功的方法，观察重试是否生效")
    public String retry() {
        return retryDemoService.recoverAfterRetry();
    }

    @GetMapping("/api/resilience4j/retry/always-fail")
    @Operation(summary = "重试示例(重试耗尽)", description = "调用一个永远失败的方法，观察重试次数耗尽后走fallback降级")
    public String retryAlwaysFail() {
        return retryDemoService.alwaysFail();
    }

    @GetMapping("/api/resilience4j/rate-limiter")
    @Operation(summary = "限流器示例", description = "短时间内并发调用可观察到超出许可数的请求被限流拒绝")
    public String rateLimiter() {
        return rateLimiterDemoService.limitedOperation();
    }

    @GetMapping("/api/resilience4j/bulkhead")
    @Operation(summary = "隔板示例", description = "并发调用可观察到超出最大并发数的请求被隔板拒绝")
    public String bulkhead() {
        return bulkheadDemoService.limitedConcurrencyOperation();
    }

}
