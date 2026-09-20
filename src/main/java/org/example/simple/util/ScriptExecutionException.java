package org.example.simple.util;

/**
 * 脚本初始化、编译、执行、结果转换或资源关闭失败。
 * 异常消息仅说明语言与阶段，原始引擎异常保留在 cause 中。
 */
public final class ScriptExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建脚本执行异常。
     *
     * @param message 中文错误说明
     * @param cause 原始失败原因
     */
    public ScriptExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
