package org.example.simple.rpc.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单连接字节预算处理器测试。
 * <p>
 * 覆盖拆帧前的半包累积计费、已拆帧字节向编解码队列的所有权转交、
 * 待写字节硬上限、以及准入失败、断连等释放路径；同时验证写缓冲水位
 * 只影响可写状态，不充当内存硬上限。
 */
class BudgetHandlerTest {

    @Test
    void accumulatesInboundBytesBeforeFramingAndReleasesOnClose() {
        ByteBudget budget = new ByteBudget(1024);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 1024));

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[32]));
        assertEquals(32, budget.used());

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[8]));
        assertEquals(40, budget.used());

        channel.finishAndReleaseAll();
        assertEquals(0, budget.used());
    }

    @Test
    void closesConnectionAndReleasesBufferWhenInboundBudgetExhausted() {
        ByteBudget budget = new ByteBudget(16);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 1024));
        ByteBuf oversized = Unpooled.buffer().writeBytes(new byte[32]);

        channel.writeInbound(oversized);

        assertEquals(0, oversized.refCnt());
        assertFalse(channel.isActive());
        assertEquals(0, budget.used());
        channel.finishAndReleaseAll();
    }

    @Test
    void framedBytesAreTransferredToWorkerAndReleasedOnlyOnce() {
        ByteBudget budget = new ByteBudget(1024);
        BudgetHandler handler = new BudgetHandler(budget, 1024);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[40]));
        assertEquals(40, budget.used());

        // 拆帧后字节由编解码工作项持有，断连不能重复释放这部分预算。
        handler.framed(40);
        handler.consumed(40);
        assertEquals(0, budget.used());

        channel.finishAndReleaseAll();
        assertEquals(0, budget.used());
    }

    @Test
    void failsWriteExceedingChannelHardLimitAndReleasesBuffer() {
        ByteBudget budget = new ByteBudget(1024);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 16));
        ByteBuf oversized = Unpooled.buffer().writeBytes(new byte[32]);

        ChannelFuture write = channel.write(oversized);

        assertTrue(write.isDone());
        assertFalse(write.isSuccess());
        assertEquals(0, oversized.refCnt());
        assertEquals(0, budget.used());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void failsWriteWhenEndpointBudgetExhausted() {
        ByteBudget budget = new ByteBudget(16);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 1024));
        ByteBuf payload = Unpooled.buffer().writeBytes(new byte[32]);

        ChannelFuture write = channel.write(payload);

        assertTrue(write.isDone());
        assertFalse(write.isSuccess());
        assertEquals(0, payload.refCnt());
        assertEquals(0, budget.used());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesWriteBudgetAfterFlushCompletes() {
        ByteBudget budget = new ByteBudget(1024);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 1024));

        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[64]));

        assertEquals(0, budget.used());
        assertTrue(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void refusesFurtherWritesWhenSlowConsumerFillsChannelHardLimit() {
        ByteBudget budget = new ByteBudget(4096);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 128));

        // 不 flush 即模拟不消费的对端：已提交的写入一直停留在出站缓冲中。
        ChannelFuture first = channel.write(Unpooled.wrappedBuffer(new byte[64]));
        ChannelFuture second = channel.write(Unpooled.wrappedBuffer(new byte[64]));
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        ByteBuf refused = Unpooled.buffer().writeBytes(new byte[64]);
        ChannelFuture third = channel.write(refused);

        assertTrue(third.isDone());
        assertFalse(third.isSuccess());
        assertEquals(0, refused.refCnt());
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test
    void waterMarkMarksChannelUnwritableButIsNotHardLimit() {
        ByteBudget budget = new ByteBudget(4096);
        EmbeddedChannel channel = new EmbeddedChannel(new BudgetHandler(budget, 4096));
        channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(16, 32));

        ChannelFuture first = channel.write(Unpooled.wrappedBuffer(new byte[64]));
        assertFalse(channel.isWritable());

        // 超过高水位后写入仍被接受：水位只改变可写状态，硬上限才拒绝。
        ChannelFuture second = channel.write(Unpooled.wrappedBuffer(new byte[64]));
        assertFalse(second.isDone());
        assertTrue(channel.isActive());
        assertEquals(128, budget.used());

        channel.flush();
        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(0, budget.used());
        channel.finishAndReleaseAll();
    }
}
