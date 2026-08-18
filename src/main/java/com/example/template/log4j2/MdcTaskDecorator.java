package com.example.template.log4j2;

import org.apache.logging.log4j.ThreadContext;
import org.jspecify.annotations.NonNull;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public @NonNull Runnable decorate(@NonNull Runnable runnable) {
        // 在主线程提交任务的这一刻，捕获当前MDC上下文快照
        Map<String, String> contextMap = ThreadContext.getImmutableContext();
        return () -> {
            try {
                // 子线程执行前，把父线程的MDC内容设置进来
                if (contextMap != null) {
                    ThreadContext.putAll(contextMap);
                }
                runnable.run();
            } finally {
                // 子线程执行完清理，避免线程池复用导致MDC污染下一个任务
                ThreadContext.clearAll();
            }
        };
    }
}
