package org.example.simple.rpc.server;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcRequest;
import org.example.simple.rpc.common.RpcResponse;

/**
 * 将 Netty I/O 线程收到的请求转交给业务执行器。
 */
final class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(RpcServerHandler.class);

    private final RpcRequestDispatcher dispatcher;
    private final Executor businessExecutor;

    RpcServerHandler(RpcRequestDispatcher dispatcher, Executor businessExecutor) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "请求分发器不能为空");
        this.businessExecutor = Objects.requireNonNull(businessExecutor, "业务执行器不能为空");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, RpcRequest request) {
        try {
            businessExecutor.execute(() -> context.writeAndFlush(dispatcher.dispatch(request)));
        } catch (RejectedExecutionException exception) {
            RpcResponse response = RpcResponse.failure(
                request.requestId(),
                RpcErrorCode.SERVER_BUSY,
                "服务端繁忙，请稍后重试");
            context.writeAndFlush(response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        LOG.warn("RPC 服务端连接处理失败，远端地址={}", context.channel().remoteAddress(), cause);
        context.close();
    }
}
