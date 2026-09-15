package com.example.template.rpc.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 协议编解码器的离线单元测试：编码后再解码能否还原原始消息、粘包/半包场景下解码器行为是否正确、
 * 非法魔数是否触发连接关闭。全程用 {@link EmbeddedChannel} 驱动，不需要真实网络连接。
 */
class RpcMessageCodecTest {

    private final RpcSerializerRegistry serializerRegistry = new RpcSerializerRegistry();

    @Test
    void shouldRoundTripRequestMessageThroughEncodeAndDecode() {
        EmbeddedChannel channel = newChannel();
        RpcRequest request = new RpcRequest(
            1L, "com.example.Demo", "echo", new String[]{"java.lang.String"}, new Object[]{"hello"});
        RpcMessage outbound = RpcMessage.request(JacksonRpcSerializer.TYPE_CODE, request);

        channel.writeOutbound(outbound);
        ByteBuf encoded = channel.readOutbound();
        channel.writeInbound(encoded);
        RpcMessage decoded = channel.readInbound();

        assertEquals(1L, decoded.getRequestId());
        assertEquals(RpcMessageType.REQUEST, decoded.getMessageType());
        RpcRequest decodedRequest = (RpcRequest) decoded.getData();
        assertEquals("com.example.Demo", decodedRequest.getInterfaceName());
        assertEquals("echo", decodedRequest.getMethodName());
        assertEquals("hello", decodedRequest.getParameters()[0]);
    }

    @Test
    void shouldDecodeTwoFramesFromOneReadEvent() {
        EmbeddedChannel channel = newChannel();
        RpcMessage first = RpcMessage.heartbeat(1L, JacksonRpcSerializer.TYPE_CODE);
        RpcMessage second = RpcMessage.heartbeat(2L, JacksonRpcSerializer.TYPE_CODE);

        channel.writeOutbound(first);
        channel.writeOutbound(second);
        ByteBuf firstBytes = channel.readOutbound();
        ByteBuf secondBytes = channel.readOutbound();
        // 一次读事件里塞进两帧的字节，模拟TCP粘包
        ByteBuf sticky = Unpooled.wrappedBuffer(firstBytes, secondBytes);

        channel.writeInbound(sticky);
        RpcMessage decodedFirst = channel.readInbound();
        RpcMessage decodedSecond = channel.readInbound();

        assertEquals(1L, decodedFirst.getRequestId());
        assertEquals(2L, decodedSecond.getRequestId());
    }

    @Test
    void shouldWaitForRemainingBytesWhenFrameArrivesInTwoParts() {
        EmbeddedChannel channel = newChannel();
        RpcMessage message = RpcMessage.heartbeat(1L, JacksonRpcSerializer.TYPE_CODE);
        channel.writeOutbound(message);
        ByteBuf full = channel.readOutbound();

        // 把一个完整帧从中间切开，模拟半包：先到一半，解码器不应该产出任何消息
        int splitIndex = full.readableBytes() / 2;
        ByteBuf firstHalf = full.readSlice(splitIndex).retain();
        ByteBuf secondHalf = full.readSlice(full.readableBytes()).retain();

        channel.writeInbound(firstHalf);
        assertNull(channel.<RpcMessage>readInbound());

        channel.writeInbound(secondHalf);
        RpcMessage decoded = channel.readInbound();
        assertEquals(1L, decoded.getRequestId());
    }

    @Test
    void shouldCloseChannelWhenMagicNumberIsInvalid() {
        EmbeddedChannel channel = newChannel();
        ByteBuf badFrame = Unpooled.buffer();
        badFrame.writeInt(0xDEADBEEF); // 错误的魔数
        badFrame.writeByte(RpcConstants.VERSION);
        badFrame.writeByte(RpcMessageType.HEARTBEAT.getCode());
        badFrame.writeByte(JacksonRpcSerializer.TYPE_CODE);
        badFrame.writeLong(1L);
        badFrame.writeInt(0);

        channel.writeInbound(badFrame);

        assertFalse(channel.isOpen());
        assertNull(channel.<RpcMessage>readInbound());
    }

    private EmbeddedChannel newChannel() {
        return new EmbeddedChannel(
            new RpcMessageDecoder(serializerRegistry),
            new RpcMessageEncoder(serializerRegistry));
    }

}
