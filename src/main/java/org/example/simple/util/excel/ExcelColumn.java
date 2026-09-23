package org.example.simple.util.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 Java 字段与 XLSX 列之间的映射。
 * <p>
 * 注解仅作用于实例字段。静态字段、瞬态字段和未标注字段不会参与导入导出。
 * 导入通过标题匹配字段，导出按照 {@link #order()} 和字段声明顺序稳定排序。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExcelColumn {

    /**
     * 返回工作表标题。
     *
     * @return 用于导入匹配和导出显示的标题
     */
    String value();

    /**
     * 返回导出列顺序，数值越小越靠前，同一对象内不能重复。
     *
     * @return 列顺序
     */
    int order() default Integer.MAX_VALUE;

    /**
     * 返回导入时是否要求单元格必须有值。
     *
     * @return 必填时返回 {@code true}
     */
    boolean required() default false;

    /**
     * 返回日期时间的解析和输出格式。
     *
     * @return 日期时间格式
     */
    String dateFormat() default "yyyy-MM-dd HH:mm:ss";
}
