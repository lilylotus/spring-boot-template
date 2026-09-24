package org.example.simple.util.excel;

/**
 * 运行时 Excel Map 导入列配置使用的键名常量。
 * <p>
 * 当导入未提供对象类型时，每个配置项使用一个 {@code Map<String, Object>} 表示，配置列表是本次导入的
 * 完整列清单。配置中的 {@link #FIELD} 用作返回 Map 的键，{@link #VALUE} 用作 Excel 标题。
 */
public final class ExcelColumnConfigKeys {

    /**
     * 返回 Map 的字段键，值类型必须为非空 {@link String}，没有默认值且必须提供。
     */
    public static final String FIELD = "field";

    /**
     * Excel 列标题，值类型必须为非空 {@link String}；省略时默认使用 {@link #FIELD} 的值。
     */
    public static final String VALUE = "value";

    /**
     * 列排序值，值类型必须为 {@link Integer}；省略时保持配置项的出现顺序。
     */
    public static final String ORDER = "order";

    /**
     * 导入单元格是否必填，值类型必须为 {@link Boolean}；省略时默认为 {@code false}。
     */
    public static final String REQUIRED = "required";

    /** 常量容器不允许实例化。 */
    private ExcelColumnConfigKeys() {
    }
}
