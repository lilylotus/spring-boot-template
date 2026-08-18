package com.example.template.log4j2;

import org.apache.logging.log4j.ThreadContext;

import java.util.Map;

public class MdcExecutorWrapper {

    public static Runnable wrap(Runnable task) {
        Map<String, String> contextMap = ThreadContext.getImmutableContext();
        return () -> {
            try {
                if (contextMap != null) {
                    ThreadContext.putAll(contextMap);
                }
                task.run();
            } finally {
                ThreadContext.clearAll();
            }
        };
    }
}

// 使用方式
//ExecutorService executor = Executors.newFixedThreadPool(10);
//executor.submit(MdcExecutorWrapper.wrap(() -> {
//    log.info("这条日志能正确带上traceId了");
//}));
