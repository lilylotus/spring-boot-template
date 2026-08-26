package org.example.simple.rpc.client;

import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.RpcResponse;

/**
 * 根据请求标识关联响应，并在连接失效时结束全部等待调用。
 */
final class RpcClientHandler extends SimpleChannelInboundHandler<RpcResponse> {

    private static final Logger LOG = LoggerFactory.getLogger(RpcClientHandler.class);

    private final ConcurrentMap<String, Promise<RpcResponse>> pendingCalls;
    private final DefaultEventLoop responseEventLoop;

    RpcClientHandler(
        ConcurrentMap<String, Promise<RpcResponse>> pendingCalls,
        DefaultEventLoop responseEventLoop) {
        this.pendingCalls = Objects.requireNonNull(pendingCalls, "待完成调用表不能为空");
        this.responseEventLoop = Objects.requireNonNull(responseEventLoop, "响应事件循环不能为空");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, RpcResponse response) {
        responseEventLoop.execute(() -> completeResponse(response));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) {
        failAll(new RpcException(RpcErrorCode.CONNECTION_CLOSED, "RPC 连接已关闭"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOG.warn("RPC 客户端连接处理失败，远端地址={}", context.channel().remoteAddress(), cause);
        failAll(new RpcException(RpcErrorCode.CONNECTION_CLOSED, "RPC 连接处理失败", cause));
        context.close();
    }

    Future<?> failAll(RpcException exception) {
        return responseEventLoop.submit(() -> pendingCalls.forEach((requestId, promise) -> {
            if (pendingCalls.remove(requestId, promise)) {
                promise.tryFailure(exception);
            }
        }));
    }

    void failCall(String requestId, Promise<RpcResponse> promise, RpcException exception) {
        responseEventLoop.execute(() -> {
            if (pendingCalls.remove(requestId, promise)) {
                promise.tryFailure(exception);
            }
        });
    }

    private void completeResponse(RpcResponse response) {
        Promise<RpcResponse> promise = pendingCalls.remove(response.requestId());
        if (promise == null) {
            LOG.warn("收到没有等待调用的 RPC 响应，请求标识={}", response.requestId());
            return;
        }
        promise.trySuccess(response);
    }
}
