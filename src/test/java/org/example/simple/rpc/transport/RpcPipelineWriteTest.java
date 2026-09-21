package org.example.simple.rpc.transport;

import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.Test;

import org.example.simple.rpc.common.RpcFrame;
import org.example.simple.rpc.common.RpcProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨线程写入的字节预算准入测试。
 * <p>
 * 覆盖预算耗尽时的拒绝与关闭、写入完成后的预算归还，以及连接已关闭时的失败路径，
 * 保证过载时不会把响应无界地缓存在内存中。
 */
class RpcPipelineWriteTest {

    private static RpcFrame response(int size) {
        return new RpcFrame(RpcProtocol.RESPONSE, (byte) 1, 1L, new byte[size]);
    }

    @Test
    void failsAndClosesChannelWhenBudgetIsExhausted() {
        ByteBudget budget = new ByteBudget(16);
        EmbeddedChannel channel = new EmbeddedChannel();

        ChannelFuture write = RpcPipeline.write(channel, response(32), budget);

        assertTrue(write.isDone());
        assertFalse(write.isSuccess());
        assertFalse(channel.isActive());
        assertEquals(0, budget.used());
        channel.finishAndReleaseAll();
    }

    @Test
    void releasesBudgetAfterWriteCompletes() {
        ByteBudget budget = new ByteBudget(1024);
        EmbeddedChannel channel = new EmbeddedChannel();

        ChannelFuture write = RpcPipeline.write(channel, response(64), budget);

        assertTrue(write.isSuccess());
        assertEquals(0, budget.used());
        channel.finishAndReleaseAll();
    }

    @Test
    void failsAndReleasesBudgetWhenChannelIsAlreadyClosed() {
        ByteBudget budget = new ByteBudget(1024);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.close().awaitUninterruptibly();

        ChannelFuture write = RpcPipeline.write(channel, response(64), budget);

        assertTrue(write.isDone());
        assertFalse(write.isSuccess());
        assertEquals(0, budget.used());
        channel.finishAndReleaseAll();
    }
}
