package com.example.template.rpc.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 把 {@link RpcMessage} 编码为协议二进制帧：魔数(4B) + 版本(1B) + 消息类型(1B) + 序列化方式(1B)
 * + requestId(8B) + 消息体长度(4B) + 消息体。
 * <p>
 * 标注 {@link ChannelHandler.Sharable} 是因为本类不持有任何连接相关的可变状态，客户端/服务端的
 * 所有连接可以共用同一个编码器实例，避免每条连接都 new 一个。
 */
@ChannelHandler.Sharable
public class RpcMessageEncoder extends MessageToByteEncoder<RpcMessage> {

    private final RpcSerializerRegistry serializerRegistry;

    public RpcMessageEncoder(RpcSerializerRegistry serializerRegistry) {
        this.serializerRegistry = serializerRegistry;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) {
        // 心跳消息没有消息体，其余类型按序列化方式编码取具体序列化实现
        byte[] body = msg.getData() == null
            ? new byte[0]
            : serializerRegistry.get(msg.getSerializerType()).serialize(msg.getData());

        out.writeInt(RpcConstants.MAGIC_NUMBER);
        out.writeByte(RpcConstants.VERSION);
        out.writeByte(msg.getMessageType().getCode());
        out.writeByte(msg.getSerializerType());
        out.writeLong(msg.getRequestId());
        out.writeInt(body.length);
        out.writeBytes(body);
    }

}
