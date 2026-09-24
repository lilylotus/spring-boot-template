package org.example.simple.util.excel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明 Java 字段与 XLSX 列之间的映射。
 * <p>
 * 注解仅作用于实例字段。静态字段、瞬态字段和未标注字段不会参与导入导出。
 * 导入要求标题数量和列顺序与声明一致，导入导出均按照 {@link #order()} 和字段声明顺序稳定排序。
 * 对象导入重载使用本注解完成映射；仅接收运行时列配置的 Map 导入重载与本注解相互独立，
 * 不会覆盖或合并对象字段声明。
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
     * 返回导入导出列顺序，数值越小越靠前，同一对象内不能重复。
     * 未显式指定顺序的字段按照继承层级和字段声明顺序排列在显式顺序字段之后。
     *
     * @return 列顺序
     */
    int order() default Integer.MAX_VALUE;

    /**
     * 返回导入时是否要求单元格必须有值。
     * 此属性不影响标题结构，所有声明列的标题均必须存在于对应索引。
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
