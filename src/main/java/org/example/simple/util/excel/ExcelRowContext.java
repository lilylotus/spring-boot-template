package org.example.simple.util.excel;

/**
 * 当前导入数据行的位置，用于逐行处理时记录业务错误或执行进度。
 *
 * @param sheetName 工作表名称
 * @param rowNumber 面向用户的一基行号
 */
public record ExcelRowContext(String sheetName, int rowNumber) {
}
