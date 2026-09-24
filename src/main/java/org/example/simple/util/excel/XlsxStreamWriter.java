package org.example.simple.util.excel;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.util.TempFile;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * 基于 SXSSF 的有界行窗口导出器。
 * <p>
 * 数据按迭代器惰性拉取，达到每表行数时自动分表；样式按工作簿复用，避免逐单元格创建样式导致资源膨胀。
 */
final class XlsxStreamWriter {

    /** 静态工具类不允许实例化。 */
    private XlsxStreamWriter() {
    }

    /**
     * 根据对象列绑定惰性写出对象数据。
     */
    static <T> void writeObjects(
        OutputStream output,
        Iterable<? extends T> data,
        Class<T> type,
        ExcelWriteOptions options) {
        ExcelObjectMapper<T> mapper = ExcelObjectMapper.of(type);
        List<ExcelObjectMapper.ColumnBinding> bindings = mapper.columns();
        List<String> headers = bindings.stream().map(ExcelObjectMapper.ColumnBinding::header).toList();
        write(output, data.iterator(), headers, value -> objectValues(value, bindings), options);
    }

    /**
     * 根据显式列定义或首行键顺序惰性写出 Map 数据。
     */
    static void writeMaps(
        OutputStream output,
        Iterable<? extends Map<String, ?>> data,
        LinkedHashMap<String, String> explicitColumns,
        ExcelWriteOptions options) {
        Iterator<? extends Map<String, ?>> iterator = data.iterator();
        Map<String, ?> first = iterator.hasNext() ? requireMap(iterator.next()) : null;
        LinkedHashMap<String, String> columns = explicitColumns == null
            ? inferColumns(first)
            : validateColumns(explicitColumns);
        List<String> keys = List.copyOf(columns.keySet());
        Set<String> keySet = Set.copyOf(keys);
        List<String> headers = List.copyOf(columns.values());
        Iterator<Map<String, ?>> combined = prepend(first, iterator);
        write(output, combined, headers, value -> mapValues(value, keys, keySet), options);
    }

    /**
     * 在当前调用专用的 POI 临时文件策略中执行工作簿写出，并统一转换底层异常。
     */
    private static <T> void write(
        OutputStream output,
        Iterator<? extends T> iterator,
        List<String> headers,
        RowValueProvider<T> provider,
        ExcelWriteOptions options) {
        Objects.requireNonNull(output, "输出流不能为 null");
        Objects.requireNonNull(iterator, "数据迭代器不能为 null");
        try (ExcelWriteTempStrategy tempStrategy = new ExcelWriteTempStrategy(options.getMaxTempBytes())) {
            TempFile.withStrategy(tempStrategy, () -> {
                writeWorkbook(output, iterator, headers, provider, options, tempStrategy);
                return null;
            });
        } catch (ExcelProcessingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ExcelProcessingException(
                ExcelErrorType.IO,
                "获取或写出 XLSX 数据失败，输出流中可能已包含不完整文件",
                exception);
        }
    }

    /**
     * 创建 SXSSF 工作簿、按限制自动分表并将迭代器数据顺序写入输出流。
     */
    private static <T> void writeWorkbook(
        OutputStream output,
        Iterator<? extends T> iterator,
        List<String> headers,
        RowValueProvider<T> provider,
        ExcelWriteOptions options,
        ExcelWriteTempStrategy tempStrategy) {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(options.getRowWindowSize())) {
            workbook.setCompressTempFiles(options.isCompressTempFiles());
            Styles styles = new Styles(workbook);
            SheetState state = createSheet(workbook, options, 1, headers, styles.headerStyle);
            long cellCount = 0;
            while (iterator.hasNext()) {
                T item = iterator.next();
                if (item == null) {
                    throw new ExcelProcessingException(ExcelErrorType.MAPPING, "导出数据行不能为 null");
                }
                if (state.dataRows == options.getMaxRowsPerSheet()) {
                    int nextSheet = state.sheetNumber + 1;
                    if (nextSheet > options.getMaxSheets()) {
                        throw limit("工作表数量", options.getMaxSheets(), nextSheet);
                    }
                    state = createSheet(workbook, options, nextSheet, headers, styles.headerStyle);
                }
                List<CellOutput> values = provider.values(item);
                if (values.size() != headers.size()) {
                    throw new ExcelProcessingException(ExcelErrorType.MAPPING, "导出行的值数量与标题数量不一致");
                }
                cellCount += values.size();
                if (cellCount > options.getMaxCells()) {
                    throw limit("累计数据单元格数", options.getMaxCells(), cellCount);
                }
                Row row = state.sheet.createRow(state.dataRows + 1);
                for (int column = 0; column < values.size(); column++) {
                    CellOutput value = values.get(column);
                    if (value.value() != null) {
                        writeCell(row.createCell(column), value, styles, options, state, column);
                    }
                }
                state.dataRows++;
                if (state.dataRows % options.getRowWindowSize() == 0) {
                    tempStrategy.checkLimit();
                }
            }
            flushRows(workbook);
            tempStrategy.checkLimit();
            workbook.write(output);
            tempStrategy.checkLimit();
        } catch (ExcelProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ExcelProcessingException(
                ExcelErrorType.IO,
                "写出 XLSX 失败，输出流中可能已包含不完整文件",
                exception);
        }
    }

    /** 将每个工作表剩余的内存行刷新到临时 XML，以便在最终写出前检查磁盘预算。 */
    private static void flushRows(SXSSFWorkbook workbook) throws IOException {
        var sheets = workbook.sheetIterator();
        while (sheets.hasNext()) {
            ((SXSSFSheet) sheets.next()).flushRows();
        }
    }

    /**
     * 创建名称安全且唯一的分表，并在存在列定义时写入标题行。
     */
    private static SheetState createSheet(
        SXSSFWorkbook workbook,
        ExcelWriteOptions options,
        int number,
        List<String> headers,
        CellStyle headerStyle) {
        String suffix = number == 1 ? "" : "_" + number;
        String base = options.getSheetName();
        int maximumBaseLength = Math.max(1, 31 - suffix.length());
        if (base.length() > maximumBaseLength) {
            base = base.substring(0, maximumBaseLength);
        }
        String safeName = WorkbookUtil.createSafeSheetName(base + suffix);
        SXSSFSheet sheet = workbook.createSheet(safeName);
        if (!headers.isEmpty()) {
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                Cell cell = header.createCell(column);
                cell.setCellValue(headers.get(column));
                cell.setCellStyle(headerStyle);
            }
        }
        return new SheetState(sheet, number);
    }

    /**
     * 按 Java 值类型选择 Excel 单元格类型；可能损失精度的整数和十进制值按文本写出。
     */
    private static void writeCell(
        Cell cell,
        CellOutput output,
        Styles styles,
        ExcelWriteOptions options,
        SheetState state,
        int column) {
        Object value = output.value();
        String text = value instanceof Enum<?> enumeration ? enumeration.name() : String.valueOf(value);
        if ((value instanceof CharSequence || value instanceof Character || value instanceof Enum<?>)
            && text.length() > options.getMaxCellCharacters()) {
            throw new ExcelProcessingException(
                ExcelErrorType.RESOURCE_LIMIT,
                "单元格字符数超过限制，限制=" + options.getMaxCellCharacters() + "，实际=" + text.length()
                    + "，工作表=" + state.sheet.getSheetName() + "，行=" + (state.dataRows + 2)
                    + "，列=" + (column + 1));
        }
        if (value instanceof Boolean booleanValue) {
            cell.setCellValue(booleanValue);
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Float || value instanceof Double) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Long || value instanceof BigInteger) {
            setText(cell, text, styles.textStyle);
        } else if (value instanceof BigDecimal decimal) {
            if (decimal.precision() <= 15) {
                cell.setCellValue(decimal.doubleValue());
            } else {
                setText(cell, decimal.toPlainString(), styles.textStyle);
            }
        } else if (value instanceof Date date) {
            cell.setCellValue(date);
            cell.setCellStyle(styles.dateStyle(output.dateFormat()));
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()));
            cell.setCellStyle(styles.dateStyle(output.dateFormat()));
        } else if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant()));
            cell.setCellStyle(styles.dateStyle(output.dateFormat()));
        } else {
            setText(cell, text, styles.textStyle);
        }
    }

    /** 以文本类型写入单元格，确保公式样式字符串不会被 Excel 解释为公式。 */
    private static void setText(Cell cell, String value, CellStyle style) {
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    /** 按列绑定顺序读取对象字段值并携带日期格式。 */
    private static <T> List<CellOutput> objectValues(
        T target,
        List<ExcelObjectMapper.ColumnBinding> bindings) {
        List<CellOutput> values = new ArrayList<>(bindings.size());
        for (ExcelObjectMapper.ColumnBinding binding : bindings) {
            Field field = binding.field();
            try {
                values.add(new CellOutput(field.get(target), binding.dateFormat()));
            } catch (IllegalAccessException exception) {
                throw new ExcelProcessingException(
                    ExcelErrorType.MAPPING,
                    "读取对象字段失败，字段=" + field.getName(),
                    exception);
            }
        }
        return values;
    }

    /** 按固定键顺序生成 Map 行值，并拒绝未定义键以防静默丢列。 */
    private static List<CellOutput> mapValues(Map<String, ?> map, List<String> keys, Set<String> keySet) {
        for (String key : map.keySet()) {
            if (!keySet.contains(key)) {
                throw new ExcelProcessingException(ExcelErrorType.MAPPING, "Map 包含未定义的键：" + key);
            }
        }
        List<CellOutput> values = new ArrayList<>(keys.size());
        for (String key : keys) {
            values.add(new CellOutput(map.get(key), "yyyy-MM-dd HH:mm:ss"));
        }
        return values;
    }

    /** 从首行键和值相同的标题定义中推断稳定列顺序。 */
    private static LinkedHashMap<String, String> inferColumns(Map<String, ?> first) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (first != null) {
            if (!(first instanceof SequencedMap<?, ?>)) {
                throw new ExcelProcessingException(
                    ExcelErrorType.MAPPING,
                    "无法从不保证迭代顺序的 Map 推断导出列，请提供显式有序列定义");
            }
            for (String key : first.keySet()) {
                result.put(key, key);
            }
        }
        return validateColumns(result);
    }

    /** 复制并校验显式列定义，避免调用方在导出期间修改映射。 */
    private static LinkedHashMap<String, String> validateColumns(LinkedHashMap<String, String> columns) {
        Objects.requireNonNull(columns, "Map 列定义不能为 null");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Set<String> headers = new HashSet<>();
        for (Map.Entry<String, String> entry : columns.entrySet()) {
            String key = entry.getKey();
            String header = entry.getValue();
            if (key == null || key.isBlank() || header == null || header.isBlank()) {
                throw new ExcelProcessingException(ExcelErrorType.MAPPING, "Map 列键和标题不能为空");
            }
            if (!headers.add(header)) {
                throw new ExcelProcessingException(ExcelErrorType.MAPPING, "Map 导出标题重复：" + header);
            }
            result.put(key, header);
        }
        return result;
    }

    /** 确认 Map 数据行非空。 */
    private static Map<String, ?> requireMap(Map<String, ?> map) {
        if (map == null) {
            throw new ExcelProcessingException(ExcelErrorType.MAPPING, "Map 导出数据行不能为 null");
        }
        return map;
    }

    /**
     * 将为推断列而预读的首行重新拼接到剩余迭代器前，不复制后续数据。
     */
    private static Iterator<Map<String, ?>> prepend(
        Map<String, ?> first,
        Iterator<? extends Map<String, ?>> rest) {
        return new Iterator<>() {
            /** 首行尚未返回时为 {@code true}。 */
            private boolean firstPending = first != null;

            /** 判断预读首行或剩余迭代器是否还有数据。 */
            @Override
            public boolean hasNext() {
                return firstPending || rest.hasNext();
            }

            /** 优先返回预读首行，随后委托给原始迭代器。 */
            @Override
            public Map<String, ?> next() {
                if (firstPending) {
                    firstPending = false;
                    return first;
                }
                return requireMap(rest.next());
            }
        };
    }

    /** 创建格式统一的导出资源上限异常。 */
    private static ExcelProcessingException limit(String name, long limit, long actual) {
        return new ExcelProcessingException(
            ExcelErrorType.RESOURCE_LIMIT,
            name + "超过限制，限制=" + limit + "，实际=" + actual);
    }

    /** 从对象或 Map 中取得一行与标题对齐的单元格值。 */
    @FunctionalInterface
    private interface RowValueProvider<T> {
        /** 将一条数据转换为按列排列的输出值。 */
        List<CellOutput> values(T value);
    }

    /**
     * 单元格输出值及可选日期格式。
     *
     * @param value 待写出的 Java 值
     * @param dateFormat 日期值使用的 Excel 格式
     */
    private record CellOutput(Object value, String dateFormat) {
    }

    /** 当前工作表写出状态，在自动分表时整体替换。 */
    private static final class SheetState {
        /** 当前 SXSSF 工作表。 */
        private final SXSSFSheet sheet;
        /** 从一开始计数的分表序号。 */
        private final int sheetNumber;
        /** 当前工作表已写出的数据行数，不包含标题。 */
        private int dataRows;

        /** 创建空数据状态。 */
        private SheetState(SXSSFSheet sheet, int sheetNumber) {
            this.sheet = sheet;
            this.sheetNumber = sheetNumber;
        }
    }

    /** 工作簿级有限样式集合，防止为每个单元格重复创建样式。 */
    private static final class Styles {
        /** 样式所属工作簿。 */
        private final SXSSFWorkbook workbook;
        /** 加粗标题样式。 */
        private final CellStyle headerStyle;
        /** 强制文本格式的单元格样式。 */
        private final CellStyle textStyle;
        /** 按日期格式字符串缓存的有限样式集合。 */
        private final Map<String, CellStyle> dateStyles = new LinkedHashMap<>();

        /** 初始化标题、文本基础样式。 */
        private Styles(SXSSFWorkbook workbook) {
            this.workbook = workbook;
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            textStyle = workbook.createCellStyle();
            DataFormat dataFormat = workbook.createDataFormat();
            textStyle.setDataFormat(dataFormat.getFormat("@"));
        }

        /** 获取或创建指定日期格式的工作簿级样式。 */
        private CellStyle dateStyle(String pattern) {
            return dateStyles.computeIfAbsent(pattern, value -> {
                CellStyle style = workbook.createCellStyle();
                style.setDataFormat(workbook.createDataFormat().getFormat(value));
                return style;
            });
        }
    }
}
