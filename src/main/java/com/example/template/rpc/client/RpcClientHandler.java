package com.example.template.rpc.client;

import com.example.template.rpc.protocol.RpcMessage;
import com.example.template.rpc.protocol.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入站处理器：收到服务端响应后回调 {@link NettyRpcClient#handleResponse} 完成对应的 Future。
 */
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcMessage> {

    private static final Logger log = LoggerFactory.getLogger(RpcClientHandler.class);

    private final NettyRpcClient client;

    public RpcClientHandler(NettyRpcClient client) {
        this.client = client;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcMessage msg) {
        switch (msg.getMessageType()) {
            case RESPONSE: {
                client.handleResponse((RpcResponse) msg.getData());
                break;
            }
            case HEARTBEAT: {
                // 服务端目前不主动回复心跳，这里预留分支；后续若服务端也发心跳ack，在此处理即可
                break;
            }
            default: {
                log.warn("RPC客户端收到未预期的消息类型: {}", msg.getMessageType());
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("RPC客户端连接发生异常，关闭连接: {}", ctx.channel(), cause);
        ctx.close();
    }

}
