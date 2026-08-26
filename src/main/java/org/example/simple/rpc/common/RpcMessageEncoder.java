package org.example.simple.rpc.common;

import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.TooLongFrameException;

/**
 * 将 RPC 消息编码为四字节长度前缀加 JSON 消息体。
 *
 * @param <T> 消息类型
 */
public final class RpcMessageEncoder<T> extends MessageToByteEncoder<T> {

    private final MessageSerializer serializer;
    private final int maxMessageLength;

    /**
     * 创建消息编码器。
     *
     * @param messageType 可接受的消息类型
     * @param serializer 消息序列化器
     * @param maxMessageLength 最大消息体字节数
     */
    public RpcMessageEncoder(Class<? extends T> messageType, MessageSerializer serializer, int maxMessageLength) {
        super(messageType);
        this.serializer = Objects.requireNonNull(serializer, "消息序列化器不能为空");
        if (maxMessageLength <= 0) {
            throw new IllegalArgumentException("最大消息长度必须大于零");
        }
        this.maxMessageLength = maxMessageLength;
    }

    @Override
    protected void encode(ChannelHandlerContext context, T message, ByteBuf output) {
        byte[] bytes = serializer.serialize(message);
        if (bytes.length > maxMessageLength) {
            throw new TooLongFrameException("RPC 消息超过最大长度: " + bytes.length);
        }
        output.writeInt(bytes.length);
        output.writeBytes(bytes);
    }
}
