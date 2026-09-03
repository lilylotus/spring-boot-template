package com.example.template.redis.lock;

/**
 * 在规定等待时间内未获取到分布式锁时抛出的异常。
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String message) {
        super(message);
    }
}
