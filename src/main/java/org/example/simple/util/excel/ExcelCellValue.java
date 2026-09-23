package org.example.simple.util.excel;

/**
 * SAX 读取阶段的单元格值，同时保留显示文本和精确转换所需的原始值。
 *
 * @param text 按 Excel 格式得到的显示文本，文本单元格可能已按读取选项修剪
 * @param raw 工作表 XML 中的原始值，用于避免数值转换时丢失精度
 * @param kind 缓存值的逻辑类型
 * @param dateFormatted 数字单元格是否应用日期格式
 * @param date1904 工作簿是否使用 1904 日期系统
 * @param columnIndex 单元格所在的零基列索引
 */
record ExcelCellValue(
    String text,
    String raw,
    Kind kind,
    boolean dateFormatted,
    boolean date1904,
    int columnIndex) {

    /** 单元格缓存结果类型，用于后续选择安全的类型转换路径。 */
    enum Kind {
        /** 普通文本或公式文本缓存值。 */
        TEXT,
        /** 保留原始十进制文本的数字值。 */
        NUMBER,
        /** 布尔值。 */
        BOOLEAN,
        /** Excel 错误值或无法识别的缓存值。 */
        ERROR
    }
}
