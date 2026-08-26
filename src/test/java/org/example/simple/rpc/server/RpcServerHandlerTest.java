package org.example.simple.rpc.server;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.common.JacksonJsonSerializer;
import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.RpcResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * RPC 服务端处理器测试。
 */
class RpcServerHandlerTest {

    @Test
    void rejectedBusinessTaskReturnsServerBusy() {
        JacksonJsonSerializer serializer = new JacksonJsonSerializer();
        RpcRequestDispatcher dispatcher = new RpcRequestDispatcher(new ServiceRegistry(), serializer);
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("测试拒绝");
        };
        EmbeddedChannel channel = new EmbeddedChannel(new RpcServerHandler(dispatcher, rejectingExecutor));
        RpcRequest request = new RpcRequest("请求-繁忙", "任意服务", "任意方法", List.of(), List.of());

        channel.writeInbound(request);
        RpcResponse response = channel.readOutbound();

        assertFalse(response.success());
        assertEquals(RpcErrorCode.SERVER_BUSY, response.error().code());
        channel.finishAndReleaseAll();
    }
}
