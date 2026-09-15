package org.example.simple.rpc.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.TooLongFrameException;

/** 编码固定头和已完成业务序列化的消息体。 */
public final class RpcMessageEncoder extends MessageToByteEncoder<RpcFrame> {
    /** 允许编码的最大消息体字节数，不包含固定头。 */
    private final int maxBodyLength;

    /**
     * 创建固定头协议编码器。
     *
     * @param maxBodyLength 允许编码的最大消息体字节数
     */
    public RpcMessageEncoder(int maxBodyLength) {
        this.maxBodyLength = maxBodyLength;
    }

    @Override
    protected void encode(ChannelHandlerContext context, RpcFrame frame, ByteBuf output) {
        if (frame.body().length > maxBodyLength) {
            throw new TooLongFrameException("消息体超过上限");
        }
        output.writeInt(RpcProtocol.MAGIC)
                .writeByte(RpcProtocol.VERSION)
                .writeByte(frame.messageType())
                .writeByte(frame.serializerId())
                .writeLong(frame.requestId())
                .writeInt(frame.body().length)
                .writeBytes(frame.body());
    }
}
