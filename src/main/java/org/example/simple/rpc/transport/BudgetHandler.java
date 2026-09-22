package org.example.simple.rpc.transport;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;

/** 在拆帧前统计半包字节，并约束已经编码的待写消息。 */
public final class BudgetHandler extends ChannelDuplexHandler {
    /** 实例级共享的字节预算，入站半包与出站待写报文都在此登记。 */
    private final ByteBudget budget;
    /** 单连接允许堆积的待写字节上限，超过即判定对端消费过慢并关闭连接。 */
    private final long writeLimit;
    /** 本连接已准入但尚未完成拆帧的字节数，连接失效时整体归还预算；仅在本连接的事件循环线程访问。 */
    private long unframed;
    /** 本连接已提交写入但尚未完成的字节数；仅在本连接的事件循环线程访问。 */
    private long pendingWrites;

    /**
     * 创建字节预算处理器。
     *
     * @param budget 实例级共享的字节预算
     * @param writeLimit 单连接待写字节上限
     */
    public BudgetHandler(ByteBudget budget, long writeLimit) {
        this.budget = budget;
        this.writeLimit = writeLimit;
    }

    /**
     * 在拆帧之前先为入站字节申请预算，申请失败即释放缓冲并关闭连接。
     *
     * <p>放在拆帧器之前是关键：半包数据在拆出完整帧之前也会占用内存，若只统计完整帧，
     * 攻击方可以只发帧头再挂住连接，从而绕过内存约束。
     *
     * @param ctx 通道处理上下文
     * @param msg 入站字节缓冲
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        int size = ((ByteBuf) msg).readableBytes();
        if (!budget.acquire(size)) {
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }
        unframed += size;
        ctx.fireChannelRead(msg);
    }

    /**
     * 将已拆帧字节所有权转交给异步工作项。
     *
     * <p>字节仍占用全局预算，但不再由连接负责归还，改由工作项在处理完成后调用 {@link #consumed(int)} 归还。
     *
     * @param size 转交的字节数
     */
    public void framed(int size) {
        unframed -= size;
    }

    /**
     * 归还异步工作项已处理完的报文字节。
     *
     * @param size 归还的字节数
     */
    public void consumed(int size) {
        budget.release(size);
    }

    /**
     * 为出站报文申请预算并登记待写字节，写入完成后自动归还。
     *
     * <p>超过单连接待写上限或全局预算时直接失败该次写入并关闭连接，避免对端不读数据时
     * 待写报文在本端无限堆积。
     *
     * @param ctx 通道处理上下文
     * @param msg 已编码的出站字节缓冲
     * @param promise 写入结果承诺
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        int size = ((ByteBuf) msg).readableBytes();
        if (size > writeLimit - pendingWrites || !budget.acquire(size)) {
            ReferenceCountUtil.release(msg);
            promise.tryFailure(new IllegalStateException("待写报文超过内存预算"));
            ctx.close();
            return;
        }
        pendingWrites += size;
        promise.addListener(
                done -> {
                    pendingWrites -= size;
                    budget.release(size);
                });
        ctx.write(msg, promise);
    }

    /**
     * 连接失效时归还尚未拆帧的字节，避免预算泄漏。
     *
     * @param ctx 通道处理上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        budget.release(unframed);
        unframed = 0;
        ctx.fireChannelInactive();
    }

    /**
     * 本处理器出现异常时直接关闭连接，异常连接的字节归还交由 {@link #channelInactive} 完成。
     *
     * @param ctx 通道处理上下文
     * @param cause 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
