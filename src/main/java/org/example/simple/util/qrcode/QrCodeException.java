package org.example.simple.util.qrcode;

/**
 * 二维码生成过程中的执行失败，区别于参数校验失败使用的 {@link IllegalArgumentException}。
 * <p>
 * 消息使用中文，且不包含调用方传入的完整待编码文本或提示文本，避免把业务敏感内容写入异常信息。
 */
public class QrCodeException extends RuntimeException {

    /** 错误类别，用于程序化判断。 */
    private final QrCodeErrorType errorType;

    /**
     * 创建不带原因的异常。
     *
     * @param errorType 错误类别
     * @param message 中文错误描述
     */
    public QrCodeException(QrCodeErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    /**
     * 创建保留原始原因的异常。
     *
     * @param errorType 错误类别
     * @param message 中文错误描述
     * @param cause 底层原因
     */
    public QrCodeException(QrCodeErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    /**
     * 返回错误类别。
     *
     * @return 错误类别
     */
    public QrCodeErrorType getErrorType() {
        return errorType;
    }
}
