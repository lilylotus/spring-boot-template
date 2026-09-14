package org.example.simple.rpc.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

/** 在拆帧前统计半包字节，并约束已经编码的待写消息。 */
public final class BudgetHandler extends ChannelDuplexHandler {
    private final ByteBudget budget;
    private final long writeLimit;
    private long unframed;
    private long pendingWrites;
    public BudgetHandler(ByteBudget budget, long writeLimit) { this.budget = budget; this.writeLimit = writeLimit; }
    @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
        int size = ((ByteBuf) msg).readableBytes();
        if (!budget.acquire(size)) { ReferenceCountUtil.release(msg); ctx.close(); return; }
        unframed += size;
        ctx.fireChannelRead(msg);
    }
    /** 将已拆帧字节所有权转交给异步工作项。 */
    public void framed(int size) { unframed -= size; }
    public void consumed(int size) { budget.release(size); }
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        int size = ((ByteBuf) msg).readableBytes();
        if (size > writeLimit - pendingWrites || !budget.acquire(size)) {
            ReferenceCountUtil.release(msg);
            promise.tryFailure(new IllegalStateException("待写报文超过内存预算"));
            ctx.close(); return;
        }
        pendingWrites += size;
        promise.addListener(done -> { pendingWrites -= size; budget.release(size); });
        ctx.write(msg, promise);
    }
    @Override public void channelInactive(ChannelHandlerContext ctx) {
        budget.release(unframed); unframed = 0;
        ctx.fireChannelInactive();
    }
    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
}
