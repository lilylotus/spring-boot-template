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

    /**
     * 按固定头格式写出一帧：魔数、版本、消息类型、序列化标识、请求编号、体长度、消息体。
     *
     * @param context 通道处理上下文
     * @param frame 已完成业务序列化的消息帧
     * @param output 出站字节缓冲
     * @throws TooLongFrameException 当消息体超过上限时抛出，避免把超大帧发往对端
     */
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
