package com.example.template.response;

import org.apache.logging.log4j.ThreadContext;

/**
 * 统一响应构造工具，负责填充约定的成功或失败状态，并从当前日志上下文复制链路追踪标识。
 * <p>
 * 控制器和全局异常处理器应通过本工具创建响应，避免不同调用位置遗漏 {@code traceId} 或
 * {@code timestamp} 字段。
 */
public final class RestResultUtils {

    private static final int SUCCESS_CODE = 0;
    private static final String SUCCESS_MESSAGE = "成功";
    private static final String TRACE_ID_KEY = "traceId";

    private RestResultUtils() {
    }

    /**
     * 创建不携带业务数据的成功响应。
     *
     * @return 统一成功响应
     */
    public static RestResult<Void> success() {
        return success(null);
    }

    /**
     * 创建携带指定业务数据的成功响应。
     *
     * @param data 需要返回的业务数据
     * @param <T> 业务数据类型
     * @return 统一成功响应
     */
    public static <T> RestResult<T> success(T data) {
        return create(SUCCESS_CODE, data, SUCCESS_MESSAGE);
    }

    /**
     * 创建不携带业务数据的失败响应。
     *
     * @param code 错误业务码，通常使用对应的 HTTP 状态码
     * @param message 面向调用方的错误说明
     * @param <T> 调用位置期望的业务数据类型
     * @return 统一失败响应
     */
    public static <T> RestResult<T> failure(int code, String message) {
        return create(code, null, message);
    }

    /**
     * 集中填充所有公共字段，确保成功与失败响应使用相同的链路标识和时间戳规则。
     *
     * @param code 业务状态码
     * @param data 业务数据
     * @param message 结果说明
     * @param <T> 业务数据类型
     * @return 字段完整的统一响应
     */
    private static <T> RestResult<T> create(int code, T data, String message) {
        return new RestResult<>(code, data, message, currentTraceId(), System.currentTimeMillis());
    }

    /**
     * 读取当前线程的链路标识；空白值按缺失处理，避免向调用方返回没有诊断意义的标识。
     *
     * @return 当前链路标识，不存在时返回 {@code null}
     */
    private static String currentTraceId() {
        String traceId = ThreadContext.get(TRACE_ID_KEY);
        return traceId == null || traceId.isBlank() ? null : traceId;
    }
}
