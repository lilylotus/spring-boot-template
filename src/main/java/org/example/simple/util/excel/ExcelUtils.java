package org.example.simple.util.excel;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * XLSX 对象和顺序 Map 导入导出工具。
 * <p>
 * 仅支持 `.xlsx`。上传场景可把 {@code MultipartFile.getInputStream()} 的结果传给读取方法，
 * 工具不会关闭调用方的输入流或输出流。小数据可使用列表方法；大数据应使用逐行读取和惰性
 * {@link Iterable} 导出，避免调用方自行累计全部数据。
 * <pre>{@code
 * List<OrderRow> rows = ExcelUtils.readObjectList(input, OrderRow.class);
 * ExcelUtils.readObjects(input, OrderRow.class, options, (row, context) -> {
 *     service.save(row);
 *     return true;
 * });
 * ExcelUtils.writeObjects(output, repository::iterator, OrderRow.class, writeOptions);
 *
 * List<Map<String, Object>> columns = List.of(
 *     Map.of(ExcelColumnConfigKeys.FIELD, "customerName", ExcelColumnConfigKeys.VALUE, "客户名称")
 * );
 * List<LinkedHashMap<String, Object>> configuredMaps =
 *     ExcelUtils.readObjectList(input, columns);
 *
 * ExcelUtils.writeMaps(output, configuredMaps);
 * ExcelUtils.writeMapsStreaming(output, repository::mapIterator);
 * }</pre>
 * <p>
 * 每次调用具有独立资源限制，但多个并发大任务的资源占用会叠加，服务层仍应设置并发上限。
 * 输出流失败后可能已有不完整字节；需要原子文件替换时，应由调用方先写入临时文件再移动。
 */
public final class ExcelUtils {

    /** 静态工具类不允许实例化。 */
    private ExcelUtils() {
    }

    /**
     * 使用默认选项把第一个工作表读取为对象列表。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param type 带有 {@link ExcelColumn} 字段和无参构造器的对象类型
     * @param <T> 对象类型
     * @return 按工作表顺序排列的对象列表
     * @throws ExcelProcessingException 文件、映射、转换或资源限制错误
     */
    public static <T> List<T> readObjectList(InputStream input, Class<T> type) {
        return readObjectList(input, type, ExcelReadOptions.defaults());
    }

    /**
     * 使用指定选项把工作表读取为有界对象列表。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param type 带有 {@link ExcelColumn} 字段和无参构造器的对象类型
     * @param options 读取选项
     * @param <T> 对象类型
     * @return 按工作表顺序排列的对象列表
     * @throws ExcelProcessingException 文件、映射、转换或资源限制错误
     */
    public static <T> List<T> readObjectList(
        InputStream input,
        Class<T> type,
        ExcelReadOptions options) {
        return readObjectList(input, type, options, null);
    }

    /**
     * 使用默认选项和运行时列配置把工作表读取为有界顺序 Map 列表。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param columns 完整列配置，{@code field} 为返回键，{@code value} 为 Excel 标题
     * @return 每行一个按配置列顺序排列的 Map
     * @throws ExcelProcessingException 文件、配置、标题、转换或资源限制错误
     */
    public static List<LinkedHashMap<String, Object>> readObjectList(
        InputStream input,
        List<Map<String, Object>> columns) {
        return readObjectList(input, ExcelReadOptions.defaults(), columns);
    }

    /**
     * 使用指定选项和运行时列配置把工作表读取为有界顺序 Map 列表。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param options 读取选项
     * @param columns 完整列配置，{@code field} 为返回键，{@code value} 为 Excel 标题
     * @return 每行一个按配置列顺序排列的 Map
     * @throws ExcelProcessingException 文件、配置、标题、转换或资源限制错误
     */
    public static List<LinkedHashMap<String, Object>> readObjectList(
        InputStream input,
        ExcelReadOptions options,
        List<Map<String, Object>> columns) {
        return readObjectList(input, null, options, columns);
    }

    /**
     * 使用默认选项逐行读取对象，处理器返回 {@code false} 时正常提前停止。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param type 对象类型
     * @param handler 同步行处理器
     * @param <T> 对象类型
     * @throws ExcelProcessingException 文件、映射、转换、回调或资源限制错误
     */
    public static <T> void readObjects(InputStream input, Class<T> type, ExcelRowHandler<T> handler) {
        readObjects(input, type, ExcelReadOptions.defaults(), handler);
    }

    /**
     * 使用指定选项逐行读取对象，处理器返回 {@code false} 时正常提前停止。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param type 对象类型
     * @param options 读取选项
     * @param handler 同步行处理器
     * @param <T> 对象类型
     * @throws ExcelProcessingException 文件、映射、转换、回调或资源限制错误
     */
    public static <T> void readObjects(
        InputStream input,
        Class<T> type,
        ExcelReadOptions options,
        ExcelRowHandler<T> handler) {
        readObjects(input, type, options, null, handler);
    }

    /**
     * 使用默认选项和运行时列配置逐行读取顺序 Map。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param columns 完整列配置，{@code field} 为返回键，{@code value} 为 Excel 标题
     * @param handler 同步行处理器
     * @throws ExcelProcessingException 文件、配置、标题、转换、回调或资源限制错误
     */
    public static void readObjects(
        InputStream input,
        List<Map<String, Object>> columns,
        ExcelRowHandler<LinkedHashMap<String, Object>> handler) {
        readObjects(input, ExcelReadOptions.defaults(), columns, handler);
    }

    /**
     * 使用指定选项和运行时列配置逐行读取顺序 Map。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param options 读取选项
     * @param columns 完整列配置，{@code field} 为返回键，{@code value} 为 Excel 标题
     * @param handler 同步行处理器
     * @throws ExcelProcessingException 文件、配置、标题、转换、回调或资源限制错误
     */
    public static void readObjects(
        InputStream input,
        ExcelReadOptions options,
        List<Map<String, Object>> columns,
        ExcelRowHandler<LinkedHashMap<String, Object>> handler) {
        readObjects(input, null, options, columns, handler);
    }

    /**
     * 按对象类型或运行时列配置读取有界列表，列表仅在本方法中累计一次。
     *
     * @param input XLSX 输入流
     * @param type 对象类型；非空时优先使用对象映射
     * @param options 读取选项
     * @param columns Map 导入的完整列配置，仅在对象类型为空时使用
     * @param <T> 当前分支的行数据类型
     * @return 按工作表顺序排列的数据列表
     */
    private static <T> List<T> readObjectList(
        InputStream input,
        Class<T> type,
        ExcelReadOptions options,
        List<Map<String, Object>> columns) {
        List<T> result = new ArrayList<>();
        readObjects(input, type, options, columns, (value, context) -> {
            ensureListCapacity(result.size(), options);
            result.add(value);
            return true;
        });
        return result;
    }

    /**
     * 按对象类型或运行时列配置统一执行流式读取，对象类型非空时不解析运行时配置。
     *
     * @param input XLSX 输入流
     * @param type 对象类型；非空时优先使用对象映射
     * @param options 读取选项
     * @param columns Map 导入的完整列配置，仅在对象类型为空时使用
     * @param handler 同步行处理器
     * @param <T> 当前分支的行数据类型
     */
    private static <T> void readObjects(
        InputStream input,
        Class<T> type,
        ExcelReadOptions options,
        List<Map<String, Object>> columns,
        ExcelRowHandler<T> handler) {
        Objects.requireNonNull(options, "读取选项不能为 null");
        Objects.requireNonNull(handler, "行处理器不能为 null");
        ImportMapper<T> mapper = createImportMapper(type, columns);
        XlsxSaxReader.read(input, options, new XlsxSaxReader.RowConsumer() {
            /** 在读取数据行前按当前映射模式校验标题数量和列索引。 */
            @Override
            public void headers(List<String> headers, String sheetName) {
                mapper.validateHeaders(headers, sheetName);
            }

            /** 将标准化单元格行映射为当前模式的数据后交给调用方处理器。 */
            @Override
            public boolean accept(
                List<String> headers,
                List<ExcelCellValue> values,
                ExcelRowContext context) throws Exception {
                return handler.handle(mapper.map(headers, values, context), context);
            }
        });
    }

    /** 根据对象类型优先规则创建本次读取使用的行映射适配器。 */
    private static <T> ImportMapper<T> createImportMapper(
        Class<T> type,
        List<Map<String, Object>> columns) {
        if (type != null) {
            ExcelObjectMapper<T> mapper = ExcelObjectMapper.of(type);
            mapper.requireImportConstructor();
            return new ImportMapper<>() {
                /** 校验标题与对象注解列声明一致。 */
                @Override
                public void validateHeaders(List<String> headers, String sheetName) {
                    mapper.validateHeaders(headers, sheetName);
                }

                /** 将单元格行转换为目标对象。 */
                @Override
                public T map(
                    List<String> headers,
                    List<ExcelCellValue> values,
                    ExcelRowContext context) {
                    return mapper.map(headers, values, context);
                }
            };
        }
        return configuredImportMapper(columns);
    }

    /** 创建运行时列配置 Map 的行映射适配器，并把受控类型转换限制在内部边界。 */
    private static <T> ImportMapper<T> configuredImportMapper(List<Map<String, Object>> columns) {
        ExcelConfiguredMapMapper mapper = ExcelConfiguredMapMapper.of(columns);
        return new ImportMapper<>() {
            /** 校验标题与运行时列配置一致。 */
            @Override
            public void validateHeaders(List<String> headers, String sheetName) {
                mapper.validateHeaders(headers, sheetName);
            }

            /** 将单元格行转换为以配置 field 为键的顺序 Map。 */
            @Override
            public T map(
                List<String> headers,
                List<ExcelCellValue> values,
                ExcelRowContext context) {
                return castConfiguredMap(mapper.map(values, context));
            }
        };
    }

    /** 将配置 Map 行转换为私有统一方法声明的泛型行。 */
    @SuppressWarnings("unchecked")
    private static <T> T castConfiguredMap(LinkedHashMap<String, Object> row) {
        return (T) row;
    }

    /** 统一对象映射和配置 Map 映射所需的标题校验与行转换操作。 */
    private interface ImportMapper<T> {

        /** 校验工作表标题。 */
        void validateHeaders(List<String> headers, String sheetName);

        /** 将标准化单元格行转换为当前模式的数据。 */
        T map(List<String> headers, List<ExcelCellValue> values, ExcelRowContext context);
    }

    /**
     * 使用默认选项把第一个工作表读取为顺序 Map 列表。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @return 每行一个保持标题顺序的 Map
     * @throws ExcelProcessingException 文件、标题或资源限制错误
     */
    public static List<LinkedHashMap<String, String>> readMapList(InputStream input) {
        return readMapList(input, ExcelReadOptions.defaults());
    }

    /**
     * 使用指定选项把工作表读取为有界顺序 Map 列表。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param options 读取选项
     * @return 每行一个保持标题顺序的 Map
     * @throws ExcelProcessingException 文件、标题或资源限制错误
     */
    public static List<LinkedHashMap<String, String>> readMapList(
        InputStream input,
        ExcelReadOptions options) {
        List<LinkedHashMap<String, String>> result = new ArrayList<>();
        readMaps(input, options, (value, context) -> {
            ensureListCapacity(result.size(), options);
            result.add(value);
            return true;
        });
        return result;
    }

    /**
     * 使用默认选项逐行读取顺序 Map。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param handler 同步行处理器
     * @throws ExcelProcessingException 文件、标题、回调或资源限制错误
     */
    public static void readMaps(
        InputStream input,
        ExcelRowHandler<LinkedHashMap<String, String>> handler) {
        readMaps(input, ExcelReadOptions.defaults(), handler);
    }

    /**
     * 使用指定选项逐行读取顺序 Map。
     *
     * @param input XLSX 输入流，方法不会关闭
     * @param options 读取选项
     * @param handler 同步行处理器
     * @throws ExcelProcessingException 文件、标题、回调或资源限制错误
     */
    public static void readMaps(
        InputStream input,
        ExcelReadOptions options,
        ExcelRowHandler<LinkedHashMap<String, String>> handler) {
        Objects.requireNonNull(options, "读取选项不能为 null");
        Objects.requireNonNull(handler, "行处理器不能为 null");
        XlsxSaxReader.read(input, options, (headers, values, context) -> {
            LinkedHashMap<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                ExcelCellValue value = values.get(index);
                row.put(headers.get(index), value == null ? null : value.text());
            }
            return handler.handle(row, context);
        });
    }

    /**
     * 使用默认选项把对象数据惰性导出为 XLSX。
     *
     * @param output XLSX 输出流，方法不会关闭
     * @param data 可惰性迭代的数据源
     * @param type 对象类型
     * @param <T> 对象类型
     * @throws ExcelProcessingException 映射、写出或资源限制错误
     */
    public static <T> void writeObjects(
        OutputStream output,
        Iterable<? extends T> data,
        Class<T> type) {
        writeObjects(output, data, type, ExcelWriteOptions.defaults());
    }

    /**
     * 使用指定选项把对象数据惰性导出为 XLSX。
     *
     * @param output XLSX 输出流，方法不会关闭
     * @param data 可惰性迭代的数据源
     * @param type 对象类型
     * @param options 导出选项
     * @param <T> 对象类型
     * @throws ExcelProcessingException 映射、写出或资源限制错误
     */
    public static <T> void writeObjects(
        OutputStream output,
        Iterable<? extends T> data,
        Class<T> type,
        ExcelWriteOptions options) {
        Objects.requireNonNull(data, "导出数据不能为 null");
        Objects.requireNonNull(options, "导出选项不能为 null");
        XlsxStreamWriter.writeObjects(output, data, type, options);
    }

    /**
     * 从首行键顺序推断列，并使用默认选项导出 Map 列表。
     *
     * @param output XLSX 输出流，方法不会关闭
     * @param data Map 数据列表；未提供显式列定义时，首行必须具有稳定迭代顺序
     * @throws ExcelProcessingException 映射、写出或资源限制错误
     */
    public static void writeMaps(
        OutputStream output,
        List<? extends Map<String, Object>> data) {
        writeMaps(output, data, null, ExcelWriteOptions.defaults());
    }

    /**
     * 使用显式有序列定义和指定选项导出 Map 列表。
     * <p>
     * 方法直接把列表交给底层流式写出器，不会复制整份数据。未提供列定义时，首行必须是能够保证
     * 迭代顺序的 {@link java.util.SequencedMap}。
     *
     * @param output XLSX 输出流，方法不会关闭
     * @param data Map 数据列表
     * @param columns “数据键到显示标题”的有序映射；为 {@code null} 时从首行推断
     * @param options 导出选项
     * @throws ExcelProcessingException 映射、写出或资源限制错误
     */
    public static void writeMaps(
        OutputStream output,
        List<? extends Map<String, Object>> data,
        LinkedHashMap<String, String> columns,
        ExcelWriteOptions options) {
        Objects.requireNonNull(data, "导出数据不能为 null");
        Objects.requireNonNull(options, "导出选项不能为 null");
        XlsxStreamWriter.writeMaps(output, data, columns, options);
    }

    /**
     * 从首行键顺序推断列，并使用默认选项惰性导出 Map 数据。
     *
     * @param output XLSX 输出流，方法不会关闭
     * @param data 可惰性迭代的 Map 数据源；首行必须具有稳定迭代顺序
     * @throws ExcelProcessingException 映射、写出或资源限制错误
     */
    public static void writeMapsStreaming(
        OutputStream output,
        Iterable<? extends Map<String, ?>> data) {
        writeMapsStreaming(output, data, null, ExcelWriteOptions.defaults());
    }

    /**
     * 使用显式有序列定义和指定选项惰性导出 Map 数据。
     * <p>
     * 方法按需获取并写出数据行，不会在工具类中将全部数据累计为列表。提供列定义时允许使用普通
     * {@link java.util.HashMap}；未提供时，首行必须是能够保证迭代顺序的 {@link java.util.SequencedMap}。
     *
     * @param output XLSX 输出流，方法不会关闭
     * @param data 可惰性迭代的 Map 数据源
     * @param columns “数据键到显示标题”的有序映射；为 {@code null} 时从首行推断
     * @param options 导出选项
     * @throws ExcelProcessingException 映射、写出或资源限制错误
     */
    public static void writeMapsStreaming(
        OutputStream output,
        Iterable<? extends Map<String, ?>> data,
        LinkedHashMap<String, String> columns,
        ExcelWriteOptions options) {
        Objects.requireNonNull(data, "导出数据不能为 null");
        Objects.requireNonNull(options, "导出选项不能为 null");
        XlsxStreamWriter.writeMaps(output, data, columns, options);
    }

    /**
     * 在向便捷列表追加数据前检查行数上限，避免列表接口无界占用堆内存。
     *
     * @param currentSize 当前已累计的数据行数
     * @param options 读取资源限制
     * @throws ExcelProcessingException 当前行会使列表达到配置上限时抛出
     */
    private static void ensureListCapacity(int currentSize, ExcelReadOptions options) {
        if (currentSize >= options.getMaxListRows()) {
            throw new ExcelProcessingException(
                ExcelErrorType.RESOURCE_LIMIT,
                "列表数据行数超过限制，限制=" + options.getMaxListRows()
                    + "，请改用 readObjects 或 readMaps 逐行处理");
        }
    }

}
