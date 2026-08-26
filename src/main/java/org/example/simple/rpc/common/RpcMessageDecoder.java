package org.example.simple.rpc.common;

import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * 从四字节长度前缀的帧中反序列化 RPC 消息。
 *
 * @param <T> 消息类型
 */
public final class RpcMessageDecoder<T> extends LengthFieldBasedFrameDecoder {

    private static final int LENGTH_FIELD_SIZE = Integer.BYTES;

    private final MessageSerializer serializer;
    private final Class<T> messageType;

    /**
     * 创建消息解码器。
     *
     * @param messageType 目标消息类型
     * @param serializer 消息序列化器
     * @param maxMessageLength 最大消息体字节数
     */
    public RpcMessageDecoder(Class<T> messageType, MessageSerializer serializer, int maxMessageLength) {
        super(validateLength(maxMessageLength) + LENGTH_FIELD_SIZE, 0, LENGTH_FIELD_SIZE, 0, LENGTH_FIELD_SIZE);
        this.messageType = Objects.requireNonNull(messageType, "消息类型不能为空");
        this.serializer = Objects.requireNonNull(serializer, "消息序列化器不能为空");
    }

    @Override
    protected Object decode(ChannelHandlerContext context, ByteBuf input) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(context, input);
        if (frame == null) {
            return null;
        }
        try {
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            return serializer.deserialize(bytes, messageType);
        } finally {
            frame.release();
        }
    }

    private static int validateLength(int maxMessageLength) {
        if (maxMessageLength <= 0 || maxMessageLength > Integer.MAX_VALUE - LENGTH_FIELD_SIZE) {
            throw new IllegalArgumentException("最大消息长度不合法");
        }
        return maxMessageLength;
    }
}
