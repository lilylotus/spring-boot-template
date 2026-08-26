package org.example.simple.rpc.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.example.simple.rpc.common.RpcErrorCode;
import org.example.simple.rpc.common.RpcException;
import org.example.simple.rpc.common.RpcResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RPC 客户端响应关联处理器测试。
 */
class RpcClientHandlerTest {

    private final DefaultEventLoop responseEventLoop = new DefaultEventLoop();

    @AfterEach
    void closeResponseEventLoop() {
        responseEventLoop.shutdownGracefully(0, 5, TimeUnit.SECONDS)
            .syncUninterruptibly();
    }

    @Test
    void outOfOrderResponsesCompleteMatchingCalls() {
        ConcurrentMap<String, Promise<RpcResponse>> pendingCalls = new ConcurrentHashMap<>();
        Promise<RpcResponse> firstPromise = responseEventLoop.newPromise();
        Promise<RpcResponse> secondPromise = responseEventLoop.newPromise();
        pendingCalls.put("请求-一", firstPromise);
        pendingCalls.put("请求-二", secondPromise);
        EmbeddedChannel channel = new EmbeddedChannel(new RpcClientHandler(pendingCalls, responseEventLoop));

        channel.writeInbound(RpcResponse.success("请求-二", "null"));
        channel.writeInbound(RpcResponse.success("请求-一", "null"));
        responseEventLoop.submit(() -> {
        }).syncUninterruptibly();

        assertEquals("请求-一", firstPromise.getNow().requestId());
        assertEquals("请求-二", secondPromise.getNow().requestId());
        assertTrue(pendingCalls.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test
    void disconnectedChannelFailsAllPendingCalls() {
        ConcurrentMap<String, Promise<RpcResponse>> pendingCalls = new ConcurrentHashMap<>();
        Promise<RpcResponse> promise = responseEventLoop.newPromise();
        pendingCalls.put("请求-断连", promise);
        EmbeddedChannel channel = new EmbeddedChannel(new RpcClientHandler(pendingCalls, responseEventLoop));

        channel.close();
        responseEventLoop.submit(() -> {
        }).syncUninterruptibly();

        RpcException cause = assertThrows(RpcException.class, promise::syncUninterruptibly);
        assertEquals(RpcErrorCode.CONNECTION_CLOSED, cause.getErrorCode());
        assertTrue(pendingCalls.isEmpty());
        channel.finishAndReleaseAll();
    }

    @Test
    void responseCompletionIsQueuedOnDefaultEventLoop() throws InterruptedException {
        ConcurrentMap<String, Promise<RpcResponse>> pendingCalls = new ConcurrentHashMap<>();
        Promise<RpcResponse> promise = responseEventLoop.newPromise();
        pendingCalls.put("请求-异步", promise);
        EmbeddedChannel channel = new EmbeddedChannel(new RpcClientHandler(pendingCalls, responseEventLoop));
        CountDownLatch eventLoopBlocked = new CountDownLatch(1);
        CountDownLatch releaseEventLoop = new CountDownLatch(1);
        responseEventLoop.execute(() -> {
            eventLoopBlocked.countDown();
            try {
                releaseEventLoop.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(eventLoopBlocked.await(1, TimeUnit.SECONDS));
        try {
            channel.writeInbound(RpcResponse.success("请求-异步", "null"));
            assertFalse(promise.isDone());
        } finally {
            releaseEventLoop.countDown();
        }

        promise.syncUninterruptibly();
        assertEquals("请求-异步", promise.getNow().requestId());
        channel.finishAndReleaseAll();
    }
}
