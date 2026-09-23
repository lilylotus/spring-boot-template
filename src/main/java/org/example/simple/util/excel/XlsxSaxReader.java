package org.example.simple.util.excel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 以 SAX 方式读取一个 XLSX 工作表，只保留当前行和有限元数据。
 * <p>
 * 输入先限量复制到调用级临时目录，再以只读 OPC 包打开；共享字符串由磁盘索引支持，避免整表常驻堆内存。
 */
final class XlsxSaxReader {

    /** 旧版 OLE2/XLS 文件的八字节文件头，用于给出明确的不支持提示。 */
    private static final byte[] OLE2_SIGNATURE = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
    };

    /** 静态工具类不允许实例化。 */
    private XlsxSaxReader() {
    }

    /**
     * 在资源限制内读取选定工作表，并把数据行同步发送给消费者。
     */
    static void read(InputStream input, ExcelReadOptions options, RowConsumer consumer) {
        if (input == null) {
            throw new IllegalArgumentException("输入流不能为 null");
        }
        try (ExcelTempWorkspace workspace = new ExcelTempWorkspace(options)) {
            Path upload = workspace.copyInput(input, options.getMaxInputBytes());
            rejectLegacyXls(upload);
            try (OPCPackage pack = OPCPackage.open(upload.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(pack);
                WorkbookInfo workbookInfo = readWorkbookInfo(reader, options.getMaxSheets());
                StylesTable styles = reader.getStylesTable();
                try (InputStream sharedInput = openSharedStrings(reader);
                     DiskSharedStrings sharedStrings = DiskSharedStrings.load(
                         sharedInput,
                         workspace,
                         options.getMaxCellCharacters())) {
                    readSelectedSheet(
                        reader,
                        styles,
                        sharedStrings,
                        workbookInfo.date1904(),
                        workbookInfo.sheetNames(),
                        options,
                        consumer);
                }
            } catch (ExcelProcessingException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ExcelProcessingException(ExcelErrorType.FORMAT, "输入不是有效的 XLSX 文件", exception);
            }
        }
    }

    /** 获取可选共享字符串部件；工作簿使用内联字符串时可能返回 {@code null}。 */
    private static InputStream openSharedStrings(XSSFReader reader) throws IOException, InvalidFormatException {
        return reader.getSharedStringsData();
    }

    /** 通过文件签名快速拒绝不受支持的旧版 XLS/OLE2 内容。 */
    private static void rejectLegacyXls(Path path) {
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] signature = input.readNBytes(OLE2_SIGNATURE.length);
            if (signature.length == OLE2_SIGNATURE.length) {
                boolean matches = true;
                for (int index = 0; index < signature.length; index++) {
                    matches &= signature[index] == OLE2_SIGNATURE[index];
                }
                if (matches) {
                    throw new ExcelProcessingException(
                        ExcelErrorType.FORMAT,
                        "不支持旧版 XLS 文件，仅支持 XLSX");
                }
            }
        } catch (ExcelProcessingException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ExcelProcessingException(ExcelErrorType.IO, "检查上传文件格式失败", exception);
        }
    }

    /** 读取工作簿日期制式和工作表名称，并在扫描阶段实施工作表数量限制。 */
    private static WorkbookInfo readWorkbookInfo(XSSFReader reader, int maxSheets) {
        try (InputStream workbook = reader.getWorkbookData()) {
            WorkbookPropertiesHandler handler = new WorkbookPropertiesHandler(maxSheets);
            var xmlReader = ExcelXmlSupport.newReader();
            xmlReader.setContentHandler(handler);
            xmlReader.parse(new InputSource(workbook));
            return new WorkbookInfo(handler.date1904, handler.sheetNames.size(), List.copyOf(handler.sheetNames));
        } catch (SAXException exception) {
            if (exception.getCause() instanceof ExcelProcessingException processingException) {
                throw processingException;
            }
            throw new ExcelProcessingException(ExcelErrorType.FORMAT, "读取 XLSX 工作簿属性失败", exception);
        } catch (Exception exception) {
            throw new ExcelProcessingException(ExcelErrorType.FORMAT, "读取 XLSX 工作簿属性失败", exception);
        }
    }

    /** 按名称优先、索引次之的规则定位并解析一个工作表。 */
    private static void readSelectedSheet(
        XSSFReader reader,
        StylesTable styles,
        DiskSharedStrings sharedStrings,
        boolean date1904,
        List<String> workbookSheetNames,
        ExcelReadOptions options,
        RowConsumer consumer) throws Exception {
        XSSFReader.SheetIterator sheets = reader.getSheetIterator();
        int index = 0;
        boolean selected = false;
        while (sheets.hasNext()) {
            try (InputStream sheet = sheets.next()) {
                String name = sheets.getSheetName();
                boolean matches = options.getSheetName() == null
                    ? index == options.getSheetIndex()
                    : options.getSheetName().equals(name);
                if (matches) {
                    selected = true;
                    parseSheet(sheet, name, styles, sharedStrings, date1904, options, consumer);
                }
            }
            if (selected) {
                return;
            }
            index++;
        }
        String requested = options.getSheetName() == null
            ? "索引 " + options.getSheetIndex()
            : "名称 " + options.getSheetName();
        throw new ExcelProcessingException(
            ExcelErrorType.MAPPING,
            "找不到指定工作表：" + requested + "，可用工作表=" + workbookSheetNames);
    }

    /** 使用安全 SAX 读取器解析选定工作表，并把正常提前停止与失败区分处理。 */
    private static void parseSheet(
        InputStream sheet,
        String sheetName,
        StylesTable styles,
        DiskSharedStrings sharedStrings,
        boolean date1904,
        ExcelReadOptions options,
        RowConsumer consumer) throws Exception {
        SheetHandler handler = new SheetHandler(
            sheetName, styles, sharedStrings, date1904, options, consumer);
        var xmlReader = ExcelXmlSupport.newReader();
        xmlReader.setContentHandler(handler);
        try {
            xmlReader.parse(new InputSource(sheet));
        } catch (StopReadingException ignored) {
            // 调用方返回 false 是正常提前结束，不视为异常。
        } catch (SAXException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ExcelProcessingException processingException) {
                throw processingException;
            }
            throw exception;
        }
        if (!handler.headerRead) {
            throw new ExcelProcessingException(
                ExcelErrorType.MAPPING,
                "工作表缺少标题行，工作表=" + sheetName + "，标题行=" + (options.getHeaderRowIndex() + 1));
        }
    }

    /** 接收已经通过标题校验且与标题数量对齐的数据行。 */
    @FunctionalInterface
    interface RowConsumer {
        /**
         * 在发送第一条数据前通知标题，默认无需额外校验。
         *
         * @param headers 不可变且顺序稳定的标题列表
         * @param sheetName 当前工作表名称
         */
        default void headers(List<String> headers, String sheetName) {
        }

        /**
         * 同步消费一行数据。
         *
         * @return {@code true} 继续读取，{@code false} 正常提前结束
         */
        boolean accept(List<String> headers, List<ExcelCellValue> values, ExcelRowContext context) throws Exception;
    }

    /** 工作表 XML 处理器，仅保留当前行、标题和 SAX 元素状态。 */
    private static final class SheetHandler extends DefaultHandler {

        /** 当前工作表名称，用于回调上下文和错误定位。 */
        private final String sheetName;
        /** 工作簿样式表，用于识别数字显示格式和日期。 */
        private final StylesTable styles;
        /** 磁盘支持的共享字符串查询表。 */
        private final DiskSharedStrings sharedStrings;
        /** 工作簿是否使用 1904 日期系统。 */
        private final boolean date1904;
        /** 当前读取调用的行列和字符资源限制。 */
        private final ExcelReadOptions options;
        /** 接收标题和标准化数据行的消费者。 */
        private final RowConsumer consumer;
        /** 按根区域设置生成 Excel 显示文本，避免服务器区域影响结果。 */
        private final DataFormatter formatter = new DataFormatter(Locale.ROOT, true);
        /** 当前值元素的分段字符缓冲区。 */
        private final StringBuilder content = new StringBuilder();
        /** 当前物理行按零基列索引展开的稀疏值列表。 */
        private final List<ExcelCellValue> rowValues = new ArrayList<>();
        /** 已校验的标题列表。 */
        private List<String> headers;
        /** 是否已经读取并通知标题行。 */
        private boolean headerRead;
        /** SAX 游标当前是否位于缓存值元素 {@code v} 内。 */
        private boolean inValue;
        /** SAX 游标当前是否位于内联字符串文本元素内。 */
        private boolean inInlineText;
        /** 当前单元格是否包含公式，用于判断非数字缓存值的逻辑类型。 */
        private boolean formula;
        /** 当前零基物理行号，初始值表示尚未进入行。 */
        private int currentRow = -1;
        /** 当前零基物理列号，初始值表示尚未进入单元格。 */
        private int currentColumn = -1;
        /** 当前单元格样式索引；没有样式时为 {@code -1}。 */
        private int currentStyle = -1;
        /** 当前单元格在工作表 XML 中的类型标记。 */
        private String currentType;
        /** 已读取的累计非空物理单元格数。 */
        private long cellCount;
        /** 已发送给消费者的数据行数。 */
        private int emittedRows;

        /** 创建工作表 SAX 状态机。 */
        private SheetHandler(
            String sheetName,
            StylesTable styles,
            DiskSharedStrings sharedStrings,
            boolean date1904,
            ExcelReadOptions options,
            RowConsumer consumer) {
            this.sheetName = sheetName;
            this.styles = styles;
            this.sharedStrings = sharedStrings;
            this.date1904 = date1904;
            this.options = options;
            this.consumer = consumer;
        }

        /** 根据行、单元格、公式和值元素更新当前 SAX 状态。 */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
            String name = localName.isEmpty() ? qName : localName;
            if ("row".equals(name)) {
                String rowReference = attributes.getValue("r");
                currentRow = rowReference == null ? currentRow + 1 : Integer.parseInt(rowReference) - 1;
                rowValues.clear();
            } else if ("c".equals(name)) {
                String reference = attributes.getValue("r");
                currentColumn = reference == null ? rowValues.size() : new CellReference(reference).getCol();
                if (currentColumn >= options.getMaxColumns()) {
                    throw sax(limit("列数", options.getMaxColumns(), currentColumn + 1));
                }
                currentType = attributes.getValue("t");
                String style = attributes.getValue("s");
                currentStyle = style == null ? -1 : Integer.parseInt(style);
                content.setLength(0);
                formula = false;
            } else if ("f".equals(name)) {
                formula = true;
            } else if ("v".equals(name)) {
                inValue = true;
                content.setLength(0);
            } else if ("t".equals(name) && "inlineStr".equals(currentType)) {
                inInlineText = true;
            }
        }

        /** 收集缓存值或内联文本片段，并在追加前实施字符数限制。 */
        @Override
        public void characters(char[] characters, int start, int length) throws SAXException {
            if (inValue || inInlineText) {
                if (content.length() + length > options.getMaxCellCharacters()) {
                    throw sax(limit(
                        "单元格字符数",
                        options.getMaxCellCharacters(),
                        content.length() + length));
                }
                content.append(characters, start, length);
            }
        }

        /** 在单元格和行结束时完成值转换与行分发。 */
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            String name = localName.isEmpty() ? qName : localName;
            if ("v".equals(name)) {
                inValue = false;
            } else if ("t".equals(name)) {
                inInlineText = false;
            } else if ("c".equals(name)) {
                addCell();
            } else if ("row".equals(name)) {
                finishRow();
            }
        }

        /** 将当前单元格转换后放入与物理列号对齐的行缓冲区，并累计资源用量。 */
        private void addCell() throws SAXException {
            while (rowValues.size() <= currentColumn) {
                rowValues.add(null);
            }
            String raw = content.toString();
            ExcelCellValue value = toCellValue(raw);
            if (value.text() != null && value.text().length() > options.getMaxCellCharacters()) {
                throw sax(limit("单元格字符数", options.getMaxCellCharacters(), value.text().length()));
            }
            rowValues.set(currentColumn, value);
            cellCount++;
            if (cellCount > options.getMaxCells()) {
                throw sax(limit("累计单元格数", options.getMaxCells(), cellCount));
            }
        }

        /** 根据 OOXML 类型、样式和公式缓存状态构造可供对象转换的单元格值。 */
        private ExcelCellValue toCellValue(String raw) {
            if ("s".equals(currentType)) {
                String text = sharedStrings.get(Integer.parseInt(raw));
                return textValue(text, raw);
            }
            if ("inlineStr".equals(currentType) || "str".equals(currentType)) {
                return textValue(raw, raw);
            }
            if ("b".equals(currentType)) {
                return new ExcelCellValue("1".equals(raw) ? "TRUE" : "FALSE", raw,
                    ExcelCellValue.Kind.BOOLEAN, false, date1904, currentColumn);
            }
            if ("e".equals(currentType)) {
                return new ExcelCellValue(raw, raw, ExcelCellValue.Kind.ERROR, false, date1904, currentColumn);
            }
            if (raw.isEmpty()) {
                return new ExcelCellValue("", "", ExcelCellValue.Kind.TEXT, false, date1904, currentColumn);
            }
            try {
                double numeric = Double.parseDouble(raw);
                int formatIndex = 0;
                String formatString = BuiltinFormats.getBuiltinFormat(0);
                if (currentStyle >= 0) {
                    XSSFCellStyle style = styles.getStyleAt(currentStyle);
                    formatIndex = style.getDataFormat();
                    formatString = style.getDataFormatString();
                    if (formatString == null) {
                        formatString = BuiltinFormats.getBuiltinFormat(formatIndex);
                    }
                }
                boolean date = DateUtil.isADateFormat(formatIndex, formatString);
                String display = formatter.formatRawCellContents(numeric, formatIndex, formatString, date1904);
                return new ExcelCellValue(display, raw, ExcelCellValue.Kind.NUMBER, date, date1904, currentColumn);
            } catch (NumberFormatException exception) {
                ExcelCellValue.Kind kind = formula ? ExcelCellValue.Kind.TEXT : ExcelCellValue.Kind.ERROR;
                return new ExcelCellValue(raw, raw, kind, false, date1904, currentColumn);
            }
        }

        /** 根据读取选项处理文本空白，同时保留未处理原始值。 */
        private ExcelCellValue textValue(String text, String raw) {
            String value = options.isTrimCellValues() ? text.trim() : text;
            return new ExcelCellValue(value, raw, ExcelCellValue.Kind.TEXT, false, date1904, currentColumn);
        }

        /**
         * 完成标题校验或标准化数据行，并处理空行、行数限制、额外列和提前停止。
         */
        private void finishRow() throws SAXException {
            if (currentRow == options.getHeaderRowIndex()) {
                headers = validateHeaders(rowValues);
                headerRead = true;
                try {
                    consumer.headers(headers, sheetName);
                } catch (ExcelProcessingException exception) {
                    throw sax(exception);
                } catch (RuntimeException exception) {
                    throw sax(new ExcelProcessingException(
                        ExcelErrorType.CALLBACK,
                        "校验 XLSX 标题失败，工作表=" + sheetName,
                        exception));
                }
                return;
            }
            if (currentRow < options.getDataStartRowIndex()) {
                return;
            }
            if (!headerRead) {
                throw sax(new ExcelProcessingException(
                    ExcelErrorType.MAPPING,
                    "读取数据前未找到标题行，工作表=" + sheetName));
            }
            if (isBlank(rowValues) && options.isSkipBlankRows()) {
                return;
            }
            emittedRows++;
            if (emittedRows > options.getMaxRows()) {
                throw sax(limit("数据行数", options.getMaxRows(), emittedRows));
            }
            List<ExcelCellValue> normalized = new ArrayList<>(headers.size());
            for (int column = 0; column < headers.size(); column++) {
                normalized.add(column < rowValues.size() ? rowValues.get(column) : null);
            }
            for (int column = headers.size(); column < rowValues.size(); column++) {
                if (rowValues.get(column) != null) {
                    throw sax(new ExcelProcessingException(
                        ExcelErrorType.MAPPING,
                        "数据列超出标题范围，工作表=" + sheetName + "，行=" + (currentRow + 1)
                            + "，列=" + (column + 1)));
                }
            }
            try {
                if (!consumer.accept(headers, normalized, new ExcelRowContext(sheetName, currentRow + 1))) {
                    throw new StopReadingException();
                }
            } catch (StopReadingException exception) {
                throw exception;
            } catch (ExcelProcessingException exception) {
                throw sax(exception);
            } catch (Exception exception) {
                throw sax(new ExcelProcessingException(
                    ExcelErrorType.CALLBACK,
                    "处理 XLSX 数据行失败，工作表=" + sheetName + "，行=" + (currentRow + 1),
                    exception,
                    sheetName,
                    currentRow + 1,
                    null,
                    null,
                    null));
            }
        }

        /** 校验标题非空且唯一，并生成不可变标题列表。 */
        private List<String> validateHeaders(List<ExcelCellValue> values) throws SAXException {
            if (values.isEmpty()) {
                throw sax(new ExcelProcessingException(ExcelErrorType.MAPPING, "标题行不能为空，工作表=" + sheetName));
            }
            List<String> result = new ArrayList<>(values.size());
            Set<String> unique = new HashSet<>();
            for (int column = 0; column < values.size(); column++) {
                ExcelCellValue cell = values.get(column);
                String header = cell == null ? null : cell.text();
                if (header != null && options.isTrimHeaders()) {
                    header = header.trim();
                }
                if (header == null || header.isEmpty()) {
                    throw sax(new ExcelProcessingException(
                        ExcelErrorType.MAPPING,
                        "标题不能为空，工作表=" + sheetName + "，列=" + (column + 1)));
                }
                if (!unique.add(header)) {
                    throw sax(new ExcelProcessingException(
                        ExcelErrorType.MAPPING,
                        "标题重复，工作表=" + sheetName + "，列=" + (column + 1) + "，标题=" + header));
                }
                result.add(header);
            }
            return List.copyOf(result);
        }

        /** 判断当前物理行是否没有任何非空显示文本。 */
        private static boolean isBlank(List<ExcelCellValue> values) {
            for (ExcelCellValue value : values) {
                if (value != null && value.text() != null && !value.text().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** 创建携带当前工作表和 SAX 行列位置的资源上限异常。 */
        private ExcelProcessingException limit(String name, long limit, long actual) {
            return new ExcelProcessingException(
                ExcelErrorType.RESOURCE_LIMIT,
                name + "超过限制，限制=" + limit + "，实际=" + actual + "，工作表=" + sheetName
                    + "，行=" + (currentRow + 1) + "，列=" + (currentColumn + 1));
        }

        /** 将领域异常包装为 SAXException，以便从 SAX 回调中安全中断解析。 */
        private static SAXException sax(ExcelProcessingException exception) {
            return new SAXException(exception);
        }
    }

    /** 读取到工作簿日期制式后提前结束。 */
    private static final class WorkbookPropertiesHandler extends DefaultHandler {
        /** 工作簿是否声明使用 1904 日期系统。 */
        private boolean date1904;
        /** 按工作簿顺序收集的工作表名称。 */
        private final List<String> sheetNames = new ArrayList<>();
        /** 允许扫描的最大工作表数。 */
        private final int maxSheets;

        /** 创建工作簿属性处理器。 */
        private WorkbookPropertiesHandler(int maxSheets) {
            this.maxSheets = maxSheets;
        }

        /** 读取日期制式和工作表名称，并尽早检查工作表数量。 */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
            String name = localName.isEmpty() ? qName : localName;
            if ("workbookPr".equals(name)) {
                String value = attributes.getValue("date1904");
                date1904 = "1".equals(value) || "true".equalsIgnoreCase(value);
            } else if ("sheet".equals(name)) {
                String sheetName = attributes.getValue("name");
                sheetNames.add(sheetName == null ? "" : sheetName);
                if (sheetNames.size() > maxSheets) {
                    throw new SAXException(new ExcelProcessingException(
                        ExcelErrorType.RESOURCE_LIMIT,
                        "工作表数量超过限制，限制=" + maxSheets
                            + "，实际至少=" + sheetNames.size()));
                }
            }
        }
    }

    /** 正常提前停止 SAX 读取的内部控制流异常，不表示解析失败。 */
    private static final class StopReadingException extends SAXException {
    }

    /**
     * 工作簿日期制式和工作表清单。
     *
     * @param date1904 是否使用 1904 日期系统
     * @param sheetCount 工作表数量
     * @param sheetNames 按工作簿顺序排列的工作表名称
     */
    private record WorkbookInfo(boolean date1904, int sheetCount, List<String> sheetNames) {
    }
}
