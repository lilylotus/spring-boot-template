package org.example.simple.rpc.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/** 使用长度字段拆帧，仅校验轻量头部。 */
public final class RpcMessageDecoder extends LengthFieldBasedFrameDecoder {
    private final SerializerRegistry serializers;
    public RpcMessageDecoder(int maxBodyLength, SerializerRegistry serializers) {
        super(Math.addExact(maxBodyLength, 19), 15, 4, 0, 0, true);
        this.serializers = serializers;
    }
    @Override protected Object decode(ChannelHandlerContext context, ByteBuf input) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(context, input);
        if (frame == null) { return null; }
        try {
            if (frame.readInt() != RpcProtocol.MAGIC || frame.readUnsignedByte() != RpcProtocol.VERSION) {
                throw new CorruptedFrameException("协议魔数或版本不匹配");
            }
            byte type = frame.readByte();
            byte serializer = frame.readByte();
            long id = frame.readLong();
            int length = frame.readInt();
            if (id <= 0 || type < 1 || type > 4 || length != frame.readableBytes()) {
                throw new CorruptedFrameException("协议头不合法");
            }
            if (type >= 3) {
                if (serializer != 0 || length != 0) { throw new CorruptedFrameException("心跳帧不合法"); }
            } else { serializers.get(serializer); }
            byte[] body = new byte[length];
            frame.readBytes(body);
            return new RpcFrame(type, serializer, id, body);
        } finally { frame.release(); }
    }
}
