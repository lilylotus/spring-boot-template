package org.example.simple.rpc.client;

import io.netty.channel.*;

import org.example.simple.rpc.common.RpcFrame;

/** 响应匹配以及所属连接的失败传播。 */
final class RpcClientHandler extends SimpleChannelInboundHandler<RpcFrame> {
    private final RpcClient client;

    RpcClientHandler(RpcClient client) {
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
        client.receive(ctx.channel(), frame);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.disconnected(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
        ctx.close();
    }
}
