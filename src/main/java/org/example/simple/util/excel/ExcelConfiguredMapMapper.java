package org.example.simple.util.excel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将运行时列配置解析为不可变绑定，并把 XLSX 数据行映射为以 {@code field} 为键的顺序 Map。
 */
final class ExcelConfiguredMapMapper {

    /** 运行时 Map 导入支持的全部配置键。 */
    private static final Set<String> SUPPORTED_KEYS = Set.of(
        ExcelColumnConfigKeys.FIELD,
        ExcelColumnConfigKeys.VALUE,
        ExcelColumnConfigKeys.ORDER,
        ExcelColumnConfigKeys.REQUIRED);

    /** 按生效列顺序排列的不可变列绑定。 */
    private final List<ColumnBinding> columns;

    /**
     * 解析并校验本次 Map 导入的完整列配置。
     *
     * @param configurations 运行时完整列配置
     */
    private ExcelConfiguredMapMapper(List<Map<String, Object>> configurations) {
        columns = parseColumns(configurations);
    }

    /**
     * 创建运行时 Map 导入映射器。
     *
     * @param configurations 运行时完整列配置，不能为空
     * @return 已完成校验的映射器
     * @throws ExcelProcessingException 配置不合法时抛出
     */
    static ExcelConfiguredMapMapper of(List<Map<String, Object>> configurations) {
        return new ExcelConfiguredMapMapper(configurations);
    }

    /**
     * 校验工作表标题数量、名称和列索引与运行时配置完全一致。
     *
     * @param headers 已标准化的标题列表
     * @param sheetName 当前工作表名称
     * @throws ExcelProcessingException 标题与配置不一致时抛出
     */
    void validateHeaders(List<String> headers, String sheetName) {
        if (headers.size() != columns.size()) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "标题列数量与运行时配置不一致，工作表=" + sheetName + "，期望列数=" + columns.size()
                    + "，实际列数=" + headers.size(),
                null,
                sheetName,
                null,
                null,
                null,
                null);
        }
        for (int index = 0; index < columns.size(); index++) {
            ColumnBinding column = columns.get(index);
            String actualHeader = headers.get(index);
            if (!column.header().equals(actualHeader)) {
                throw new ExcelProcessingException(
                    ExcelErrorType.MAPPING,
                    "标题列与运行时配置不一致，工作表=" + sheetName + "，列=" + (index + 1)
                        + "，期望标题=" + column.header() + "，实际标题=" + actualHeader,
                    null,
                    sheetName,
                    null,
                    index + 1,
                    actualHeader,
                    column.field());
            }
        }
    }

    /**
     * 将一行标准化单元格映射为按配置列顺序排列的 Map。
     *
     * @param values 与标题数量对齐的单元格值
     * @param context 当前数据行位置
     * @return 以配置 {@code field} 为键的顺序 Map
     * @throws ExcelProcessingException 必填单元格为空时抛出
     */
    LinkedHashMap<String, Object> map(List<ExcelCellValue> values, ExcelRowContext context) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            ColumnBinding column = columns.get(index);
            ExcelCellValue cell = values.get(index);
            String text = cell == null ? null : cell.text();
            if (column.required() && (text == null || text.isBlank())) {
                throw new ExcelProcessingException(
                    ExcelErrorType.CONVERSION,
                    "必填单元格不能为空，工作表=" + context.sheetName() + "，行=" + context.rowNumber()
                        + "，列=" + (index + 1) + "，标题=" + column.header() + "，字段=" + column.field(),
                    null,
                    context.sheetName(),
                    context.rowNumber(),
                    index + 1,
                    column.header(),
                    column.field());
            }
            result.put(column.field(), text == null || text.isEmpty() ? null : text);
        }
        return result;
    }

    /** 解析全部配置项，并执行跨列唯一性校验和稳定排序。 */
    private static List<ColumnBinding> parseColumns(List<Map<String, Object>> configurations) {
        if (configurations == null || configurations.isEmpty()) {
            throw new ExcelProcessingException(ExcelErrorType.MAPPING, "运行时列配置 columns 不能为空");
        }
        List<ColumnBinding> result = new ArrayList<>(configurations.size());
        Set<String> fields = new HashSet<>();
        Set<String> headers = new HashSet<>();
        Set<Integer> explicitOrders = new HashSet<>();
        for (int index = 0; index < configurations.size(); index++) {
            Map<String, Object> configuration = configurations.get(index);
            if (configuration == null) {
                throw configError(index, "配置项", "配置项不能为 null");
            }
            validateKeys(configuration, index);
            String field = requiredString(configuration, ExcelColumnConfigKeys.FIELD, index);
            String header = optionalString(configuration, ExcelColumnConfigKeys.VALUE, field, index);
            Integer explicitOrder = optionalInteger(configuration, ExcelColumnConfigKeys.ORDER, index);
            boolean required = optionalBoolean(configuration, ExcelColumnConfigKeys.REQUIRED, false, index);
            if (!fields.add(field)) {
                throw configError(index, ExcelColumnConfigKeys.FIELD, "字段键重复：" + field);
            }
            if (!headers.add(header)) {
                throw configError(index, ExcelColumnConfigKeys.VALUE, "列标题重复：" + header);
            }
            if (explicitOrder != null && !explicitOrders.add(explicitOrder)) {
                throw configError(index, ExcelColumnConfigKeys.ORDER, "显式列顺序重复：" + explicitOrder);
            }
            int order = explicitOrder == null ? Integer.MAX_VALUE : explicitOrder;
            result.add(new ColumnBinding(field, header, order, required, index));
        }
        result.sort((left, right) -> {
            int order = Integer.compare(left.order(), right.order());
            return order != 0 ? order : Integer.compare(left.configurationIndex(), right.configurationIndex());
        });
        return List.copyOf(result);
    }

    /** 拒绝未知键，避免拼写错误被静默忽略。 */
    private static void validateKeys(Map<String, Object> configuration, int index) {
        for (Object key : configuration.keySet()) {
            if (!(key instanceof String text) || !SUPPORTED_KEYS.contains(text)) {
                throw configError(index, String.valueOf(key), "不支持的配置键");
            }
        }
    }

    /** 读取必填的非空字符串配置。 */
    private static String requiredString(Map<String, Object> configuration, String key, int index) {
        if (!configuration.containsKey(key)) {
            throw configError(index, key, "缺少必填配置");
        }
        return stringValue(configuration.get(key), key, index);
    }

    /** 读取可选的非空字符串配置。 */
    private static String optionalString(
        Map<String, Object> configuration,
        String key,
        String defaultValue,
        int index) {
        return configuration.containsKey(key)
            ? stringValue(configuration.get(key), key, index)
            : defaultValue;
    }

    /** 校验并标准化字符串配置。 */
    private static String stringValue(Object value, String key, int index) {
        if (!(value instanceof String text)) {
            throw configError(index, key, "值类型必须为 String");
        }
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            throw configError(index, key, "值不能为空");
        }
        return normalized;
    }

    /** 读取可选整数配置。 */
    private static Integer optionalInteger(Map<String, Object> configuration, String key, int index) {
        if (!configuration.containsKey(key)) {
            return null;
        }
        Object value = configuration.get(key);
        if (!(value instanceof Integer integer)) {
            throw configError(index, key, "值类型必须为 Integer");
        }
        return integer;
    }

    /** 读取可选布尔配置。 */
    private static boolean optionalBoolean(
        Map<String, Object> configuration,
        String key,
        boolean defaultValue,
        int index) {
        if (!configuration.containsKey(key)) {
            return defaultValue;
        }
        Object value = configuration.get(key);
        if (!(value instanceof Boolean bool)) {
            throw configError(index, key, "值类型必须为 Boolean");
        }
        return bool;
    }

    /** 创建包含配置项位置和键名的映射异常。 */
    private static ExcelProcessingException configError(int index, String key, String reason) {
        return new ExcelProcessingException(
            ExcelErrorType.MAPPING,
            "运行时列配置错误，配置项=" + (index + 1) + "，键=" + key + "，原因=" + reason);
    }

    /**
     * 运行时 Map 列绑定。
     *
     * @param field 返回 Map 的键
     * @param header Excel 列标题
     * @param order 排序值
     * @param required 是否必填
     * @param configurationIndex 配置项原始位置
     */
    private record ColumnBinding(
        String field,
        String header,
        int order,
        boolean required,
        int configurationIndex) {
    }
}
