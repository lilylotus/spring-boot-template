package org.example.simple.util.excel;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.DateUtil;

/**
 * 对象列元数据、实例化和单元格类型转换器。
 * <p>
 * 每个目标类型的反射结果由 {@link ClassValue} 缓存，既避免逐行扫描字段，也允许类卸载时自动回收元数据。
 */
final class ExcelObjectMapper<T> {

    /** 按目标类隔离的映射元数据缓存。 */
    private static final ClassValue<ExcelObjectMapper<?>> CACHE = new ClassValue<>() {
        /** 首次访问目标类时构建并校验映射元数据。 */
        @Override
        protected ExcelObjectMapper<?> computeValue(Class<?> type) {
            return new ExcelObjectMapper<>(type);
        }
    };

    /** 可直接转换的字段类型集合；枚举类型在集合外单独判断。 */
    private static final Set<Class<?>> SUPPORTED_TYPES = Set.of(
        String.class,
        boolean.class,
        Boolean.class,
        byte.class,
        Byte.class,
        short.class,
        Short.class,
        int.class,
        Integer.class,
        long.class,
        Long.class,
        float.class,
        Float.class,
        double.class,
        Double.class,
        char.class,
        Character.class,
        BigDecimal.class,
        BigInteger.class,
        LocalDate.class,
        LocalDateTime.class,
        Date.class);

    /** 导入对象使用的可访问无参构造器；仅导出类型可以没有该构造器。 */
    private final Constructor<T> constructor;
    /** 按导出顺序排列的不可变列绑定。 */
    private final List<ColumnBinding> columns;
    /** 标题到字段绑定的查找表，用于导入时按标题映射。 */
    private final Map<String, ColumnBinding> columnsByHeader;

    /** 扫描并缓存指定类型的构造器和列元数据。 */
    private ExcelObjectMapper(Class<T> type) {
        constructor = findConstructor(type);
        columns = scanColumns(type);
        columnsByHeader = new HashMap<>();
        for (ColumnBinding column : columns) {
            columnsByHeader.put(column.header(), column);
        }
    }

    /**
     * 获取指定目标类型的缓存映射器。
     *
     * @param type 导入或导出的对象类型
     * @param <T> 对象类型
     * @return 目标类型对应的映射器
     */
    static <T> ExcelObjectMapper<T> of(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("对象类型不能为 null");
        }
        @SuppressWarnings("unchecked")
        ExcelObjectMapper<T> mapper = (ExcelObjectMapper<T>) CACHE.get(type);
        return mapper;
    }

    /** 确认目标类型具备导入所需的可访问无参构造器。 */
    void requireImportConstructor() {
        if (constructor == null) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "导入对象必须提供可访问的无参构造器");
        }
    }

    /** 返回已经按导出顺序排序的不可变列绑定。 */
    List<ColumnBinding> columns() {
        return columns;
    }

    /**
     * 创建目标对象并把当前行中已映射的单元格写入字段。
     *
     * @param headers 已校验的标题列表
     * @param values 与标题对齐的单元格值
     * @param context 当前工作表和行位置
     * @return 填充后的目标对象
     */
    T map(List<String> headers, List<ExcelCellValue> values, ExcelRowContext context) {
        validateHeaders(headers, context.sheetName());
        T target;
        try {
            target = constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "创建导入对象失败，类型=" + constructor.getDeclaringClass().getName(),
                exception);
        }
        for (int index = 0; index < headers.size(); index++) {
            ColumnBinding binding = columnsByHeader.get(headers.get(index));
            if (binding == null) {
                continue;
            }
            ExcelCellValue cell = values.get(index);
            Object converted = convert(cell, binding, context, index);
            try {
                binding.field().set(target, converted);
            } catch (IllegalAccessException exception) {
                throw cellError(
                    ExcelErrorType.MAPPING,
                    "写入对象字段失败",
                    exception,
                    context,
                    index,
                    binding,
                    cell == null ? null : cell.text());
            }
        }
        return target;
    }

    /** 校验当前标题集合包含对象声明的全部必填列。 */
    void validateHeaders(List<String> headers, String sheetName) {
        Set<String> available = new HashSet<>(headers);
        for (ColumnBinding column : columns) {
            if (column.required() && !available.contains(column.header())) {
                throw new ExcelProcessingException(
                    ExcelErrorType.MAPPING,
                    "缺少必填标题，工作表=" + sheetName + "，标题=" + column.header()
                        + "，字段=" + column.field().getName());
            }
        }
    }

    /**
     * 根据字段类型和单元格逻辑类型选择转换路径，并补充精确的错误位置。
     */
    private static Object convert(
        ExcelCellValue cell,
        ColumnBinding binding,
        ExcelRowContext context,
        int columnIndex) {
        String text = cell == null ? null : cell.text();
        Class<?> type = binding.field().getType();
        if (text == null || text.isBlank()) {
            if (binding.required() || type.isPrimitive()) {
                throw cellError(
                    ExcelErrorType.CONVERSION,
                    "必填单元格不能为空",
                    null,
                    context,
                    columnIndex,
                    binding,
                    text);
            }
            return null;
        }
        if (cell.kind() == ExcelCellValue.Kind.ERROR) {
            throw cellError(
                ExcelErrorType.CONVERSION,
                "单元格包含 Excel 错误值",
                null,
                context,
                columnIndex,
                binding,
                text);
        }
        try {
            if (type == String.class) {
                return text;
            }
            if (type == boolean.class || type == Boolean.class) {
                return parseBoolean(text);
            }
            if (type == char.class || type == Character.class) {
                if (text.length() != 1) {
                    throw new IllegalArgumentException("字符值必须恰好包含一个字符");
                }
                return text.charAt(0);
            }
            if (type == LocalDate.class || type == LocalDateTime.class || type == Date.class) {
                return parseDate(cell, binding.dateFormat(), type);
            }
            if (type.isEnum()) {
                return parseEnum(type, text);
            }
            return parseNumber(cell.kind() == ExcelCellValue.Kind.NUMBER ? cell.raw() : text, type);
        } catch (RuntimeException exception) {
            throw cellError(
                ExcelErrorType.CONVERSION,
                "单元格不能转换为 " + type.getSimpleName(),
                exception,
                context,
                columnIndex,
                binding,
                text);
        }
    }

    /**
     * 使用 {@link BigDecimal} 作为数字转换中间值，确保整数范围和小数部分得到严格校验。
     */
    private static Object parseNumber(String value, Class<?> type) {
        BigDecimal decimal = new BigDecimal(value);
        if (type == BigDecimal.class) {
            return decimal;
        }
        if (type == BigInteger.class) {
            return decimal.toBigIntegerExact();
        }
        if (type == byte.class || type == Byte.class) {
            return decimal.byteValueExact();
        }
        if (type == short.class || type == Short.class) {
            return decimal.shortValueExact();
        }
        if (type == int.class || type == Integer.class) {
            return decimal.intValueExact();
        }
        if (type == long.class || type == Long.class) {
            return decimal.longValueExact();
        }
        if (type == float.class || type == Float.class) {
            float result = decimal.floatValue();
            if (!Float.isFinite(result)) {
                throw new ArithmeticException("数值超出 float 范围");
            }
            return result;
        }
        if (type == double.class || type == Double.class) {
            double result = decimal.doubleValue();
            if (!Double.isFinite(result)) {
                throw new ArithmeticException("数值超出 double 范围");
            }
            return result;
        }
        throw new IllegalArgumentException("不支持的数字类型：" + type.getName());
    }

    /**
     * 解析 Excel 日期序列值或按列格式解析文本日期，并转换为目标日期类型。
     */
    private static Object parseDate(ExcelCellValue cell, String pattern, Class<?> type) {
        LocalDateTime dateTime;
        if (cell.kind() == ExcelCellValue.Kind.NUMBER && cell.dateFormatted()) {
            Date date = DateUtil.getJavaDate(Double.parseDouble(cell.raw()), cell.date1904());
            dateTime = LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
        } else {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            try {
                dateTime = LocalDateTime.parse(cell.text(), formatter);
            } catch (DateTimeParseException exception) {
                LocalDate date = LocalDate.parse(cell.text(), formatter);
                dateTime = date.atStartOfDay();
            }
        }
        if (type == LocalDate.class) {
            return dateTime.toLocalDate();
        }
        if (type == LocalDateTime.class) {
            return dateTime;
        }
        return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** 解析常用中英文布尔文本和数字布尔值。 */
    private static boolean parseBoolean(String value) {
        return switch (value.trim().toLowerCase(LocaleSupport.ROOT)) {
            case "true", "1", "是", "yes" -> true;
            case "false", "0", "否", "no" -> false;
            default -> throw new IllegalArgumentException("无法识别布尔值");
        };
    }

    /** 按枚举常量名称精确解析枚举值。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object parseEnum(Class<?> type, String value) {
        return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), value);
    }

    /**
     * 查找并开放无参构造器；不存在或无法开放时返回 {@code null}，以允许该类型仅用于导出。
     */
    private static <T> Constructor<T> findConstructor(Class<T> type) {
        try {
            Constructor<T> result = type.getDeclaredConstructor();
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 从父类到子类扫描字段并生成稳定列顺序，避免继承层级改变标题匹配规则。
     */
    private static List<ColumnBinding> scanColumns(Class<?> type) {
        List<ColumnBinding> result = new ArrayList<>();
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        int declarationOrder = 0;
        Set<String> headers = new HashSet<>();
        Set<Integer> explicitOrders = new HashSet<>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                ExcelColumn annotation = field.getAnnotation(ExcelColumn.class);
                if (annotation == null || Modifier.isStatic(field.getModifiers())
                    || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                validateColumn(field, annotation, headers, explicitOrders);
                try {
                    field.setAccessible(true);
                } catch (RuntimeException exception) {
                    throw new ExcelProcessingException(
                        ExcelErrorType.MAPPING,
                        "对象字段不可访问，字段=" + field.getName(),
                        exception);
                }
                result.add(new ColumnBinding(
                    field,
                    annotation.value().trim(),
                    annotation.order(),
                    annotation.required(),
                    annotation.dateFormat(),
                    declarationOrder++));
            }
        }
        if (result.isEmpty()) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "对象没有使用 @ExcelColumn 声明的字段，类型=" + type.getName());
        }
        result.sort((left, right) -> {
            int order = Integer.compare(left.order(), right.order());
            return order != 0 ? order : Integer.compare(left.declarationOrder(), right.declarationOrder());
        });
        return List.copyOf(result);
    }

    /** 校验单个字段的标题、顺序、类型和日期格式定义。 */
    private static void validateColumn(
        Field field,
        ExcelColumn annotation,
        Set<String> headers,
        Set<Integer> explicitOrders) {
        String header = annotation.value().trim();
        if (header.isEmpty()) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "@ExcelColumn 标题不能为空，字段=" + field.getName());
        }
        if (!headers.add(header)) {
            throw new ExcelProcessingException(ExcelErrorType.MAPPING, "对象列标题重复，标题=" + header);
        }
        if (annotation.order() != Integer.MAX_VALUE && !explicitOrders.add(annotation.order())) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "对象列顺序重复，顺序=" + annotation.order());
        }
        Class<?> fieldType = field.getType();
        if (!SUPPORTED_TYPES.contains(fieldType) && !fieldType.isEnum()) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "不支持的对象字段类型，字段=" + field.getName() + "，类型=" + fieldType.getName());
        }
        if (annotation.dateFormat().isBlank()) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "日期格式不能为空，字段=" + field.getName());
        }
    }

    /** 创建包含工作表、行列、标题、字段和脱敏值摘要的转换异常。 */
    private static ExcelProcessingException cellError(
        ExcelErrorType type,
        String reason,
        Throwable cause,
        ExcelRowContext context,
        int columnIndex,
        ColumnBinding binding,
        String value) {
        String summary = summarize(value);
        String message = reason + "，工作表=" + context.sheetName() + "，行=" + context.rowNumber()
            + "，列=" + (columnIndex + 1) + "，标题=" + binding.header()
            + "，字段=" + binding.field().getName() + "，值=" + summary;
        return new ExcelProcessingException(
            type,
            message,
            cause,
            context.sheetName(),
            context.rowNumber(),
            columnIndex + 1,
            binding.header(),
            binding.field().getName());
    }

    /**
     * 转义换行并截断原始值，避免异常消息泄漏或携带过大的单元格内容。
     */
    private static String summarize(String value) {
        if (value == null) {
            return "null";
        }
        int length = value.length();
        String escaped = value.replace("\r", "\\r").replace("\n", "\\n");
        return escaped.length() <= 128 ? escaped : escaped.substring(0, 128) + "…(原始字符数=" + length + ")";
    }

    /**
     * 对象字段与 XLSX 列的不可变绑定。
     *
     * @param field 可访问的对象字段
     * @param header 用于导入匹配和导出显示的标题
     * @param order 显式导出顺序
     * @param required 导入时是否必填
     * @param dateFormat 日期解析和导出格式
     * @param declarationOrder 从父类到子类计算的稳定声明顺序
     */
    record ColumnBinding(
        Field field,
        String header,
        int order,
        boolean required,
        String dateFormat,
        int declarationOrder) {
    }

    /** 避免在 switch 表达式中重复创建 Locale。 */
    private static final class LocaleSupport {
        /** 与运行环境无关的大小写转换区域设置。 */
        private static final java.util.Locale ROOT = java.util.Locale.ROOT;

        /** 常量容器不允许实例化。 */
        private LocaleSupport() {
        }
    }
}
