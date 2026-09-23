package org.example.simple.util.excel;

/**
 * XLSX 处理错误类别，便于调用方区分配置、数据、资源和基础设施错误。
 * <p>
 * 该分类用于程序化判断，不建议调用方解析异常消息文本。
 */
public enum ExcelErrorType {

    /** 输入内容不是有效或受支持的 XLSX。 */
    FORMAT,
    /** 调用方配置无效。 */
    CONFIGURATION,
    /** 标题、字段或列定义不符合映射契约。 */
    MAPPING,
    /** 单元格内容不能转换为目标类型。 */
    CONVERSION,
    /** 输入或输出超过资源上限。 */
    RESOURCE_LIMIT,
    /** 读取或写出发生 I/O 错误。 */
    IO,
    /** 调用方逐行处理器执行失败。 */
    CALLBACK
}
