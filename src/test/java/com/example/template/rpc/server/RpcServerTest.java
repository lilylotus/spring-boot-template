package com.example.template.rpc.server;

import com.example.template.rpc.protocol.JacksonRpcSerializer;
import com.example.template.rpc.protocol.RpcMessage;
import com.example.template.rpc.protocol.RpcRequest;
import com.example.template.rpc.protocol.RpcResponse;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.EventExecutor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link RpcServer}/{@link RpcServerHandler} 线程模型与请求分发相关的离线单元测试。
 */
class RpcServerTest {

    @Test
    void shouldCreateEventLoopGroupsWithExplicitThreadCount() throws InterruptedException {
        RpcServer server = new RpcServer(0, 4);
        try {
            assertEquals(1, countExecutors(server.getBossGroup()));
            assertEquals(4, countExecutors(server.getWorkerGroup()));
        } finally {
            server.shutdownGracefully();
        }
    }

    @Test
    void shouldReturnFailureResponseWhenServiceNotRegistered() {
        ServiceRegistry emptyRegistry = new ServiceRegistry();
        EmbeddedChannel channel = new EmbeddedChannel(new RpcServerHandler(emptyRegistry, directExecutor()));

        RpcRequest request = new RpcRequest(1L, "com.example.NotRegistered", "echo", new String[0], new Object[0]);
        channel.writeInbound(RpcMessage.request(JacksonRpcSerializer.TYPE_CODE, request));

        RpcMessage outbound = channel.readOutbound();
        RpcResponse response = (RpcResponse) outbound.getData();
        assertFalse(response.isSuccess());
    }

    @Test
    void shouldTriggerCallerRunsPolicyWhenQueueIsFull() throws InterruptedException {
        ThreadPoolExecutor pool = RpcServer.buildBusinessThreadPool(1, 1, 60L, 1);
        CountDownLatch blockLatch = new CountDownLatch(1);
        try {
            // 占满唯一的核心线程
            pool.submit(awaitTask(blockLatch));
            // 填满容量为1的队列
            pool.submit(awaitTask(blockLatch));

            AtomicReference<String> executingThreadName = new AtomicReference<>();
            String testThreadName = Thread.currentThread().getName();
            // 核心线程忙、队列已满，第三个任务触发CallerRunsPolicy，应该在提交者(测试)线程上同步执行
            pool.execute(() -> executingThreadName.set(Thread.currentThread().getName()));

            assertEquals(testThreadName, executingThreadName.get());
            assertNotEquals("rpc-business-1", executingThreadName.get());
        } finally {
            blockLatch.countDown();
            pool.shutdownNow();
        }
    }

    private Runnable awaitTask(CountDownLatch latch) {
        return () -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private int countExecutors(Iterable<EventExecutor> group) {
        int count = 0;
        for (EventExecutor ignored : group) {
            count++;
        }
        return count;
    }

    /** 让业务逻辑在提交者(测试)线程上同步执行，避免测试里再引入跨线程等待的复杂度。 */
    private ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void shutdown() {
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                return java.util.List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }

}
