package org.example.simple.rpc.common;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.StringNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC 长度帧编解码器测试。
 */
class RpcMessageCodecTest {

    private static final int MAX_MESSAGE_LENGTH = 1024;

    private final JacksonJsonSerializer serializer = new JacksonJsonSerializer();

    @Test
    void splitMessageIsDecodedOnlyAfterCompleteArrival() {
        RpcRequest request = request("请求-拆包");
        ByteBuf frame = encode(request);
        int splitIndex = frame.readableBytes() / 2;
        ByteBuf firstPart = frame.readRetainedSlice(splitIndex);
        ByteBuf secondPart = frame.readRetainedSlice(frame.readableBytes());
        frame.release();
        EmbeddedChannel decoder = decoder(MAX_MESSAGE_LENGTH);

        assertFalse(decoder.writeInbound(firstPart));
        assertNull(decoder.readInbound());
        assertTrue(decoder.writeInbound(secondPart));
        assertEquals(request, decoder.readInbound());

        decoder.finishAndReleaseAll();
    }

    @Test
    void concatenatedMessagesAreDecodedInOrder() {
        RpcRequest first = request("请求-一");
        RpcRequest second = request("请求-二");
        ByteBuf combined = Unpooled.wrappedBuffer(encode(first), encode(second));
        EmbeddedChannel decoder = decoder(MAX_MESSAGE_LENGTH);

        assertTrue(decoder.writeInbound(combined));
        assertEquals(first, decoder.readInbound());
        assertEquals(second, decoder.readInbound());

        decoder.finishAndReleaseAll();
    }

    @Test
    void malformedJsonMessageIsRejected() {
        byte[] invalidJson = "{错误".getBytes();
        ByteBuf frame = Unpooled.buffer(Integer.BYTES + invalidJson.length)
            .writeInt(invalidJson.length)
            .writeBytes(invalidJson);
        EmbeddedChannel decoder = decoder(MAX_MESSAGE_LENGTH);

        assertThrows(DecoderException.class, () -> decoder.writeInbound(frame));

        decoder.finishAndReleaseAll();
    }

    @Test
    void declaredLengthOverLimitIsRejected() {
        EmbeddedChannel decoder = decoder(16);
        ByteBuf frameHeader = Unpooled.buffer(Integer.BYTES).writeInt(17);

        assertThrows(TooLongFrameException.class, () -> decoder.writeInbound(frameHeader));

        decoder.finishAndReleaseAll();
    }

    private RpcRequest request(String requestId) {
        return new RpcRequest(
            requestId,
            "回显服务",
            "回显",
            List.of(String.class.getName()),
            List.of(StringNode.valueOf("内容")));
    }

    private ByteBuf encode(RpcRequest request) {
        EmbeddedChannel encoder = new EmbeddedChannel(
            new RpcMessageEncoder<>(RpcRequest.class, serializer, MAX_MESSAGE_LENGTH));
        assertTrue(encoder.writeOutbound(request));
        ByteBuf frame = encoder.readOutbound();
        encoder.finishAndReleaseAll();
        return frame;
    }

    private EmbeddedChannel decoder(int maxMessageLength) {
        return new EmbeddedChannel(new RpcMessageDecoder<>(RpcRequest.class, serializer, maxMessageLength));
    }
}
