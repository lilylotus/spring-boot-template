package org.example.simple.rpc.client;

import io.netty.channel.*;

import org.example.simple.rpc.common.RpcFrame;

/** 响应匹配以及所属连接的失败传播。 */
final class RpcClientHandler extends SimpleChannelInboundHandler<RpcFrame> {
    /** 所属客户端实例，响应与断连事件都回调给它处理。 */
    private final RpcClient client;

    /**
     * 创建客户端处理器。
     *
     * @param client 所属客户端实例
     */
    RpcClientHandler(RpcClient client) {
        this.client = client;
    }

    /**
     * 把收到的响应帧交给客户端按请求编号匹配在途调用。
     *
     * @param ctx 通道处理上下文
     * @param frame 响应帧
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcFrame frame) {
        client.receive(ctx.channel(), frame);
    }

    /**
     * 连接断开时通知客户端，使该连接上的在途调用以连接关闭失败并触发重连。
     *
     * @param ctx 通道处理上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        client.disconnected(ctx.channel());
    }

    /**
     * 处理链异常时关闭连接，在途调用的失败传播由 {@link #channelInactive} 统一完成。
     *
     * @param ctx 通道处理上下文
     * @param error 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable error) {
        ctx.close();
    }
}
