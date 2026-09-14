package org.example.simple.rpc.common;

import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcMessageCodecTest {
    private ByteBuf encoded(long id) {
        EmbeddedChannel encoder = new EmbeddedChannel(new RpcMessageEncoder(1024));
        encoder.writeOutbound(new RpcFrame(RpcProtocol.REQUEST, (byte) 1, id, new byte[]{1, 2, 3}));
        ByteBuf result = encoder.readOutbound(); encoder.finishAndReleaseAll(); return result;
    }
    @Test void decodesEveryHeaderAndBodySplitPoint() {
        for (int split = 1; split < 22; split++) {
            EmbeddedChannel decoder = new EmbeddedChannel(new RpcMessageDecoder(1024, SerializerRegistry.defaults()));
            ByteBuf bytes = encoded(7);
            assertFalse(decoder.writeInbound(bytes.readRetainedSlice(split)));
            assertTrue(decoder.writeInbound(bytes));
            RpcFrame frame = decoder.readInbound();
            assertEquals(7, frame.requestId()); assertArrayEquals(new byte[]{1, 2, 3}, frame.body());
            decoder.finishAndReleaseAll();
        }
    }
    @Test void decodesConcatenatedFrames() {
        EmbeddedChannel decoder = new EmbeddedChannel(new RpcMessageDecoder(1024, SerializerRegistry.defaults()));
        decoder.writeInbound(Unpooled.wrappedBuffer(encoded(1), encoded(2)));
        assertEquals(1, ((RpcFrame) decoder.readInbound()).requestId());
        assertEquals(2, ((RpcFrame) decoder.readInbound()).requestId());
        decoder.finishAndReleaseAll();
    }
    @Test void rejectsInvalidMagicVersionTypeSerializerAndLength() {
        for (int offset : new int[]{0, 4, 5, 6, 15}) {
            EmbeddedChannel decoder = new EmbeddedChannel(new RpcMessageDecoder(1024, SerializerRegistry.defaults()));
            ByteBuf bytes = encoded(1); bytes.setByte(offset, 127);
            assertThrows(Exception.class, () -> decoder.writeInbound(bytes));
            decoder.finishAndReleaseAll();
        }
    }
}
