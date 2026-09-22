package org.example.simple.rpc.common;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/** 使用长度字段拆帧，仅校验轻量头部。 */
public final class RpcMessageDecoder extends LengthFieldBasedFrameDecoder {
    /** 序列化器注册表，用于在拆帧阶段就拒绝未启用的序列化标识。 */
    private final SerializerRegistry serializers;

    /**
     * 创建固定头协议解码器。
     *
     * <p>帧上限为消息体上限加固定头长度；长度字段位于第 15 字节、占 4 字节，且不表示头部长度，
     * 因此长度调整量与跳过字节数均为 0，拆帧后仍保留完整头部供本类自行校验。
     *
     * @param maxBodyLength 允许解码的最大消息体字节数
     * @param serializers 序列化器注册表
     */
    public RpcMessageDecoder(int maxBodyLength, SerializerRegistry serializers) {
        super(Math.addExact(maxBodyLength, 19), 15, 4, 0, 0, true);
        this.serializers = serializers;
    }

    /**
     * 拆出一个完整帧并校验协议头，产出待反序列化的 {@link RpcFrame}。
     *
     * <p>校验项：魔数与版本、请求编号为正、消息类型在取值范围内、体长度与实际可读字节一致；
     * 心跳帧额外要求序列化标识为 0 且消息体为空，业务帧则要求序列化标识已注册。
     *
     * @param context 通道处理上下文
     * @param input 累积的入站字节
     * @return 拆出的消息帧；字节不足以构成完整帧时返回 {@code null}
     * @throws CorruptedFrameException 当协议头不合法时抛出，由上层关闭连接
     * @throws Exception 底层拆帧失败时抛出
     */
    @Override
    protected Object decode(ChannelHandlerContext context, ByteBuf input) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(context, input);
        if (frame == null) {
            return null;
        }
        try {
            if (frame.readInt() != RpcProtocol.MAGIC
                    || frame.readUnsignedByte() != RpcProtocol.VERSION) {
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
                if (serializer != 0 || length != 0) {
                    throw new CorruptedFrameException("心跳帧不合法");
                }
            } else {
                serializers.get(serializer);
            }
            byte[] body = new byte[length];
            frame.readBytes(body);
            return new RpcFrame(type, serializer, id, body);
        } finally {
            frame.release();
        }
    }
}
