package cn.nihility.gw.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 站在 BootDemo 位置上的打桩下游服务，供熔断/限流的集成测试使用。
 *
 * <p>为什么要真起一个服务器：熔断和限流要验证的核心是「被拦下的请求有没有真的打到下游」，
 * 这件事只有下游自己数得准。打桩服务器记录收到的请求数，测试据此断言拦截是否生效。</p>
 *
 * <p>必须在静态初始化阶段启动：{@code @DynamicPropertySource} 注册的属性会在上下文刷新时求值，
 * 而那发生在 {@code @BeforeAll} 之前，届时端口号必须已经可用。</p>
 */
public class StubDownstreamServer {

    /** 打桩服务返回的响应体，内容本身不重要，只要能确认「确实来自下游」。 */
    private static final String BODY = "{\"from\":\"stub\"}";

    private final DisposableServer server;

    /** 收到的请求总数，用来断言被拦下的请求没有穿透到下游。 */
    private final AtomicInteger requestCount = new AtomicInteger();

    /** 打桩服务当前返回的状态码，测试可随时切换以模拟下游故障或恢复。 */
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    /** 打桩服务响应前的人为延迟，用来验证超时相关行为。 */
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);

    /** 在随机空闲端口上启动打桩服务。 */
    public StubDownstreamServer() {
        this.server = HttpServer.create()
                .port(0)
                .handle((request, response) -> {
                    requestCount.incrementAndGet();
                    response.status(status.get());
                    response.header("Content-Type", "application/json");
                    Duration currentDelay = delay.get();
                    if (currentDelay.isZero()) {
                        return response.sendString(Mono.just(BODY));
                    }
                    return response.sendString(Mono.just(BODY).delayElement(currentDelay));
                })
                .bindNow();
    }

    /** 打桩服务监听的端口。 */
    public int port() {
        return server.port();
    }

    /** 打桩服务的基地址，可直接注册成服务发现里的实例地址。 */
    public String baseUrl() {
        return "http://localhost:" + port();
    }

    /** 迄今为止收到的请求数。 */
    public int requestCount() {
        return requestCount.get();
    }

    /** 把请求计数清零，通常在每个用例开始前调用。 */
    public void resetRequestCount() {
        requestCount.set(0);
    }

    /** 切换打桩服务返回的状态码，用于模拟下游故障与恢复。 */
    public void respondWith(int statusCode) {
        status.set(statusCode);
    }

    /** 设置响应前的人为延迟。 */
    public void respondAfter(Duration duration) {
        delay.set(duration);
    }

    /** 关闭打桩服务，释放端口。 */
    public void stop() {
        server.disposeNow();
    }

}
