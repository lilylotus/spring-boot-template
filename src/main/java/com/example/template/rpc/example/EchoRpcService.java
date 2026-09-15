package com.example.template.rpc.example;

/**
 * 端到端联调用的示例服务接口：把收到的字符串原样返回，用于验证客户端-服务端整条链路可用。
 */
public interface EchoRpcService {

    /**
     * 原样返回输入内容。
     *
     * @param message 输入内容
     * @return 与输入相同的内容
     */
    String echo(String message);

    /**
     * 睡眠指定毫秒数后再返回，用于测试客户端超时场景。
     *
     * @param message    输入内容
     * @param delayMillis 服务端处理前的人为延迟(毫秒)
     * @return 与输入相同的内容
     */
    String slowEcho(String message, long delayMillis);

}
