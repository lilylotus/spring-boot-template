package com.example.template.rpc.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单客户端进程内唯一的 requestId 生成器。requestId 只需要在同一个客户端进程内保证唯一即可
 * (用于 {@code pendingRequests} 映射表的 key)，不需要跨进程全局唯一，简单自增计数器足够。
 */
public class RpcRequestIdGenerator {

    private final AtomicLong sequence = new AtomicLong(0);

    /**
     * 生成下一个 requestId。
     *
     * @return 单调递增、进程内唯一的 requestId
     */
    public long next() {
        return sequence.incrementAndGet();
    }

}
