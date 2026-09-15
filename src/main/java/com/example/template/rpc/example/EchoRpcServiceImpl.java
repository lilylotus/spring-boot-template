package com.example.template.rpc.example;

/**
 * {@link EchoRpcService} 的服务端实现，仅用于框架自身的端到端联调/测试，不代表真实业务用法。
 */
public class EchoRpcServiceImpl implements EchoRpcService {

    @Override
    public String echo(String message) {
        return message;
    }

    @Override
    public String slowEcho(String message, long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return message;
    }

}
