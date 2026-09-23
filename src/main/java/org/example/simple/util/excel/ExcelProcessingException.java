package org.example.simple.util.excel;

/**
 * XLSX 处理统一异常，携带可以安全记录的工作表和单元格上下文。
 */
public class ExcelProcessingException extends RuntimeException {

    /** 错误类别。 */
    private final ExcelErrorType errorType;
    /** 工作表名称，未知时为 {@code null}。 */
    private final String sheetName;
    /** 面向用户的一基行号，未知时为 {@code null}。 */
    private final Integer rowNumber;
    /** 面向用户的一基列号，未知时为 {@code null}。 */
    private final Integer columnNumber;
    /** 列标题，未知时为 {@code null}。 */
    private final String header;
    /** Java 字段名，未知时为 {@code null}。 */
    private final String fieldName;

    /**
     * 创建不带单元格上下文的处理异常。
     *
     * @param errorType 错误类别
     * @param message 中文错误说明
     */
    public ExcelProcessingException(ExcelErrorType errorType, String message) {
        this(errorType, message, null, null, null, null, null, null);
    }

    /**
     * 创建带底层原因的处理异常。
     *
     * @param errorType 错误类别
     * @param message 中文错误说明
     * @param cause 底层异常
     */
    public ExcelProcessingException(ExcelErrorType errorType, String message, Throwable cause) {
        this(errorType, message, cause, null, null, null, null, null);
    }

    /**
     * 创建包含完整安全上下文的处理异常。
     *
     * @param errorType 错误类别
     * @param message 中文错误说明
     * @param cause 底层异常
     * @param sheetName 工作表名称
     * @param rowNumber 一基行号
     * @param columnNumber 一基列号
     * @param header 列标题
     * @param fieldName Java 字段名
     */
    public ExcelProcessingException(
        ExcelErrorType errorType,
        String message,
        Throwable cause,
        String sheetName,
        Integer rowNumber,
        Integer columnNumber,
        String header,
        String fieldName) {
        super(message, cause);
        this.errorType = errorType;
        this.sheetName = sheetName;
        this.rowNumber = rowNumber;
        this.columnNumber = columnNumber;
        this.header = header;
        this.fieldName = fieldName;
    }

    /**
     * 返回错误类别。
     *
     * @return 错误类别
     */
    public ExcelErrorType getErrorType() {
        return errorType;
    }

    /**
     * 返回工作表名称。
     *
     * @return 工作表名称，未知时为 {@code null}
     */
    public String getSheetName() {
        return sheetName;
    }

    /**
     * 返回面向用户的一基行号。
     *
     * @return 一基行号，未知时为 {@code null}
     */
    public Integer getRowNumber() {
        return rowNumber;
    }

    /**
     * 返回面向用户的一基列号。
     *
     * @return 一基列号，未知时为 {@code null}
     */
    public Integer getColumnNumber() {
        return columnNumber;
    }

    /**
     * 返回列标题。
     *
     * @return 列标题，未知时为 {@code null}
     */
    public String getHeader() {
        return header;
    }

    /**
     * 返回 Java 字段名。
     *
     * @return Java 字段名，未知时为 {@code null}
     */
    public String getFieldName() {
        return fieldName;
    }
}
