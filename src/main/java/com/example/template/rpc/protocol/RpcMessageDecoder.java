package com.example.template.rpc.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把字节流解码为 {@link RpcMessage}，基于 {@link LengthFieldBasedFrameDecoder} 先解决 TCP
 * 粘包半包问题（拿到一个完整的协议帧），再解析定长消息头各字段还原出内存模型。
 * <p>
 * 长度字段偏移量(15)/长度字段长度(4)/lengthAdjustment(0)/initialBytesToStrip(0) 均按设计文档给出
 * 的参数配置：不剥离任何字节，交给本类自己按偏移量解析消息头，而不是让父类先切走长度字段。
 */
public class RpcMessageDecoder extends LengthFieldBasedFrameDecoder {

    private static final Logger log = LoggerFactory.getLogger(RpcMessageDecoder.class);

    private final RpcSerializerRegistry serializerRegistry;

    public RpcMessageDecoder(RpcSerializerRegistry serializerRegistry) {
        super(RpcConstants.MAX_FRAME_LENGTH, RpcConstants.LENGTH_FIELD_OFFSET, RpcConstants.LENGTH_FIELD_LENGTH, 0, 0);
        this.serializerRegistry = serializerRegistry;
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            // 半包：父类还没攒够一个完整帧，等待后续数据到达再解析
            return null;
        }

        try {
            int magic = frame.readInt();
            if (magic != RpcConstants.MAGIC_NUMBER) {
                // 非法魔数，说明协议不一致或数据已损坏，无法再信任后续任何字节，直接关闭连接
                log.error("收到非法魔数的RPC协议帧: {}，关闭连接: {}", magic, ctx.channel());
                ctx.close();
                return null;
            }

            frame.readByte(); // 协议版本号，当前版本暂不做兼容性分支处理，读出来跳过即可
            byte messageTypeCode = frame.readByte();
            byte serializerType = frame.readByte();
            long requestId = frame.readLong();
            int bodyLength = frame.readInt();

            RpcMessageType messageType = RpcMessageType.fromCode(messageTypeCode);
            Object data = null;
            if (bodyLength > 0) {
                byte[] body = new byte[bodyLength];
                frame.readBytes(body);
                Class<?> bodyClass = resolveBodyClass(messageType);
                if (bodyClass != null) {
                    data = serializerRegistry.get(serializerType).deserialize(body, bodyClass);
                }
            }

            return new RpcMessage(requestId, messageType, serializerType, data);
        } finally {
            // super.decode()返回的帧是独立于累积缓冲区的一份切片引用，用完必须手动释放，
            // 否则每处理一帧就泄漏一次内存(不像直接转发ByteBuf那样由后续Handler负责释放)
            frame.release();
        }
    }

    private Class<?> resolveBodyClass(RpcMessageType messageType) {
        switch (messageType) {
            case REQUEST: {
                return RpcRequest.class;
            }
            case RESPONSE: {
                return RpcResponse.class;
            }
            default: {
                // 心跳消息没有消息体
                return null;
            }
        }
    }

}
