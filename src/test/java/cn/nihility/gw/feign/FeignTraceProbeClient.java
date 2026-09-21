package cn.nihility.gw.feign;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 测试用的 FeignClient，指向测试自身启动的 Web 服务。
 *
 * <p>url 带默认值是必需的：FeignConfiguration 会扫描 cn.nihility.gw 下的所有 FeignClient，
 * 非 Web 的测试上下文里没有 local.server.port，没有默认值会导致那些上下文启动失败。</p>
 */
@FeignClient(name = "feignTraceProbe", url = "${trace.probe.url:http://localhost:1}")
public interface FeignTraceProbeClient {

    /** 返回下游实际收到的 X-Trace-Id 请求头，用于验证链路 id 是否透传成功。 */
    @GetMapping("/feign-probe/echo-trace")
    String echoTrace();

    /** 故意拖慢的接口，用于验证读超时配置是否生效。 */
    @GetMapping("/feign-probe/slow")
    String slow();

}
