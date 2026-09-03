package com.example.template.redis.lock;

/**
 * 服务发现锁已过期、被其他持有者接手或自动续期失败时抛出的异常。
 */
public class LockOwnershipLostException extends RuntimeException {

    public LockOwnershipLostException(String message) {
        super(message);
    }
}
