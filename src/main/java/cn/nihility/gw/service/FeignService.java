package cn.nihility.gw.service;

import cn.nihility.gw.feign.Template4Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 调用下游 SpringBoot4Template 服务的业务层。
 *
 * <p>OpenFeign 本身是阻塞式的，直接在 WebFlux 的事件循环线程上调用会把整个事件循环卡住，
 * 因此这里统一用 {@code Mono.fromCallable(...).subscribeOn(boundedElastic)} 把阻塞调用
 * 挪到弹性线程池执行，对外暴露成标准的响应式接口。</p>
 */
@Service
public class FeignService {

    private static final Logger log = LoggerFactory.getLogger(FeignService.class);

    @Autowired
    private Template4Client template4Client;

    /** 调用下游的 /welcome 接口。 */
    public Mono<Map<String, Object>> welcome() {
        return callOnElastic("feign /welcome call", () -> template4Client.welcome());
    }

    /** 调用下游的 /api/welcome 接口。 */
    public Mono<Map<String, Object>> apiWelcome() {
        return callOnElastic("feign /api/welcome call", () -> template4Client.apiWelcome());
    }

    /**
     * 把一次阻塞的 Feign 调用包装成 Mono，并调度到 boundedElastic 线程池。
     *
     * <p>用 fromCallable 而不是 just，是为了让调用延迟到订阅时才发生；
     * just 会在组装阶段(即 Controller 方法返回之前)就把阻塞调用执行掉，等于没挪走。</p>
     */
    private Mono<Map<String, Object>> callOnElastic(String message, Supplier<Map<String, Object>> call) {
        return Mono.fromCallable(() -> {
                    log.info(message);
                    return call.get();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

}
