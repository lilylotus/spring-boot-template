package com.example.template.outbox;

/**
 * Outbox模式相关代码统一的运行时异常，涵盖事件序列化失败等场景。
 */
public class OutboxException extends RuntimeException {

    public OutboxException(String message, Throwable cause) {
        super(message, cause);
    }

}
