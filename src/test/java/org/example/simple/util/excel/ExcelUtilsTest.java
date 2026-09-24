package org.example.simple.util.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XLSX 对象和顺序 Map 导入导出的端到端测试。 */
class ExcelUtilsTest {

    @TempDir
    java.nio.file.Path temporaryDirectory;

    @Test
    void readExcelReadMapList() {
        try (InputStream inputStream = Files.newInputStream(Path.of("D:\\worktemporary\\month09\\xxx.xlsx"))) {
            List<LinkedHashMap<String, String>> rows = ExcelUtils.readMapList(inputStream);
            for (LinkedHashMap<String, String> row : rows) {
                System.out.println(row);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static class UserExcel {

        @ExcelColumn(value = "姓名", order = 1, required = true)
        private String userName;
        @ExcelColumn(value = "登录名", order = 2, required = true)
        private String loginName;
        @ExcelColumn(value = "部门", order = 3, required = true)
        private String deptName;
        @ExcelColumn(value = "岗位", order = 4, required = true)
        private String jobName;
        @ExcelColumn(value = "日期", order = 5, dateFormat = "yyyy-MM-dd", required = true)
        private LocalDateTime workDay;
        @ExcelColumn(value = "星期", order = 6, required = true)
        private String weekly;
        @ExcelColumn(value = "异常类型", order = 7, required = true)
        private String errorType;
        @ExcelColumn(value = "异常说明", order = 8, required = true)
        private String errorDescription;
        @ExcelColumn(value = "当日工时", order = 9, required = true)
        private float workHours;
        @ExcelColumn(value = "在岗时长", order = 10, required = true)
        private float onDutyHours;
        @ExcelColumn(value = "在岗状态", order = 11, required = true)
        private String onDutyStatus;

        public UserExcel() {
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getLoginName() {
            return loginName;
        }

        public void setLoginName(String loginName) {
            this.loginName = loginName;
        }

        public String getDeptName() {
            return deptName;
        }

        public void setDeptName(String deptName) {
            this.deptName = deptName;
        }

        public String getJobName() {
            return jobName;
        }

        public void setJobName(String jobName) {
            this.jobName = jobName;
        }

        public LocalDateTime getWorkDay() {
            return workDay;
        }

        public void setWorkDay(LocalDateTime workDay) {
            this.workDay = workDay;
        }

        public String getWeekly() {
            return weekly;
        }

        public void setWeekly(String weekly) {
            this.weekly = weekly;
        }

        public String getErrorType() {
            return errorType;
        }

        public void setErrorType(String errorType) {
            this.errorType = errorType;
        }

        public String getErrorDescription() {
            return errorDescription;
        }

        public void setErrorDescription(String errorDescription) {
            this.errorDescription = errorDescription;
        }

        public float getWorkHours() {
            return workHours;
        }

        public void setWorkHours(float workHours) {
            this.workHours = workHours;
        }

        public float getOnDutyHours() {
            return onDutyHours;
        }

        public void setOnDutyHours(float onDutyHours) {
            this.onDutyHours = onDutyHours;
        }

        public String getOnDutyStatus() {
            return onDutyStatus;
        }

        public void setOnDutyStatus(String onDutyStatus) {
            this.onDutyStatus = onDutyStatus;
        }
    }

    @Test
    void readExcelReadObjectList() {
        try (InputStream inputStream = Files.newInputStream(Path.of("D:\\worktemporary\\month09\\xxx.xlsx"))) {
            List<UserExcel> rows = ExcelUtils.readObjectList(inputStream, UserExcel.class);
            for (UserExcel row : rows) {
                System.out.println(row);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void roundTripsObjectsWithSupportedTypesAndKeepsStreamsOpen() throws Exception {
        DemoRow first = new DemoRow();
        first.id = 9_007_199_254_740_993L;
        first.name = "=危险文本";
        first.amount = new BigDecimal("123456789012345.6789");
        first.active = true;
        first.state = State.ENABLED;
        first.day = LocalDate.of(2026, 9, 23);
        first.createdAt = LocalDateTime.of(2026, 9, 23, 14, 30, 12);

        TrackingOutputStream output = new TrackingOutputStream();
        ExcelUtils.writeObjects(output, List.of(first), DemoRow.class);
        assertFalse(output.closed);

        TrackingInputStream input = new TrackingInputStream(output.toByteArray());
        List<DemoRow> rows = ExcelUtils.readObjectList(input, DemoRow.class);
        assertFalse(input.closed);
        assertEquals(1, rows.size());
        DemoRow actual = rows.getFirst();
        assertEquals(first.id, actual.id);
        assertEquals(first.name, actual.name);
        assertEquals(first.amount, actual.amount);
        assertEquals(first.active, actual.active);
        assertEquals(first.state, actual.state);
        assertEquals(first.day, actual.day);
        assertEquals(first.createdAt, actual.createdAt);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(CellType.STRING, workbook.getSheetAt(0).getRow(1).getCell(0).getCellType());
            assertEquals(CellType.STRING, workbook.getSheetAt(0).getRow(1).getCell(1).getCellType());
            assertEquals("=危险文本", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals(CellType.STRING, workbook.getSheetAt(0).getRow(1).getCell(2).getCellType());
        }
    }

    @Test
    void readsAndWritesOrderedMapsWithExplicitAndInferredColumns() throws Exception {
        LinkedHashMap<String, Object> first = new LinkedHashMap<>();
        first.put("code", "A-01");
        first.put("name", "名称");
        first.put("count", 3);
        LinkedHashMap<String, Object> second = new LinkedHashMap<>();
        second.put("code", "A-02");
        second.put("count", 4);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExcelUtils.writeMaps(output, List.of(first, second));

        List<LinkedHashMap<String, String>> rows = ExcelUtils.readMapList(
            new ByteArrayInputStream(output.toByteArray()));
        assertEquals(List.of("code", "name", "count"), new ArrayList<>(rows.getFirst().keySet()));
        assertEquals("A-01", rows.getFirst().get("code"));
        assertEquals("3", rows.getFirst().get("count"));
        assertNull(rows.get(1).get("name"));

        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("code", "编码");
        columns.put("name", "名称");
        ByteArrayOutputStream emptyOutput = new ByteArrayOutputStream();
        ExcelUtils.writeMaps(emptyOutput, List.of(), columns, ExcelWriteOptions.defaults());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(emptyOutput.toByteArray()))) {
            assertEquals("编码", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("名称", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    void writesUnorderedMapsWithExplicitColumnsAndRejectsColumnInference() throws Exception {
        Map<String, Object> row = new HashMap<>();
        row.put("name", "名称");
        row.put("code", "A-01");
        LinkedHashMap<String, String> columns = new LinkedHashMap<>();
        columns.put("code", "编码");
        columns.put("name", "名称");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExcelUtils.writeMaps(output, List.of(row), columns, ExcelWriteOptions.defaults());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals("编码", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("A-01", workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
            assertEquals("名称", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
        }

        ExcelProcessingException exception = assertThrows(
            ExcelProcessingException.class,
            () -> ExcelUtils.writeMaps(new ByteArrayOutputStream(), List.of(row)));
        assertEquals(ExcelErrorType.MAPPING, exception.getErrorType());
        assertTrue(exception.getMessage().contains("显式有序列定义"));
    }

    @Test
    void consumesStreamingMapRowsLazily() throws Exception {
        AtomicInteger consumed = new AtomicInteger();
        Iterable<Map<String, ?>> rows = () -> new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < 3;
            }

            @Override
            public Map<String, ?> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("index", index++);
                consumed.incrementAndGet();
                return row;
            }
        };

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, consumed.get());
        ExcelUtils.writeMapsStreaming(output, rows);
        assertEquals(3, consumed.get());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(4, workbook.getSheetAt(0).getPhysicalNumberOfRows());
        }
    }

    @Test
    void readsSelectedSheetAndCustomDataRegion() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("忽略").createRow(0).createCell(0).setCellValue("无关");
            var sheet = workbook.createSheet("目标");
            sheet.createRow(1).createCell(0).setCellValue(" code ");
            sheet.getRow(1).createCell(1).setCellValue("name");
            sheet.createRow(3).createCell(0).setCellValue("X1");
            sheet.getRow(3).createCell(1).setCellValue("张三");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        ExcelReadOptions options = ExcelReadOptions.builder()
            .sheetName("目标")
            .headerRowIndex(1)
            .dataStartRowIndex(3)
            .build();
        List<LinkedHashMap<String, String>> rows = ExcelUtils.readMapList(
            new ByteArrayInputStream(bytes), options);
        assertEquals("X1", rows.getFirst().get("code"));
        assertEquals("张三", rows.getFirst().get("name"));
    }

    @Test
    void trimsTextCellValuesByDefaultAndCanPreserveWhitespace() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("名称");
            sheet.createRow(1).createCell(0).setCellValue("  张三  ");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        List<TextRow> defaultObjects = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), TextRow.class);
        List<LinkedHashMap<String, String>> defaultMaps = ExcelUtils.readMapList(
            new ByteArrayInputStream(bytes));
        assertEquals("张三", defaultObjects.getFirst().name);
        assertEquals("张三", defaultMaps.getFirst().get("名称"));

        ExcelReadOptions preserveWhitespace = ExcelReadOptions.builder()
            .trimCellValues(false)
            .build();
        List<TextRow> originalObjects = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), TextRow.class, preserveWhitespace);
        List<LinkedHashMap<String, String>> originalMaps = ExcelUtils.readMapList(
            new ByteArrayInputStream(bytes), preserveWhitespace);
        assertEquals("  张三  ", originalObjects.getFirst().name);
        assertEquals("  张三  ", originalMaps.getFirst().get("名称"));
    }

    @Test
    void supportsEarlyStopAndWrapsCallbackFailure() throws Exception {
        byte[] bytes = createSimpleWorkbook(5);
        AtomicInteger count = new AtomicInteger();
        ExcelUtils.readMaps(new ByteArrayInputStream(bytes), (row, context) -> count.incrementAndGet() < 2);
        assertEquals(2, count.get());

        IllegalStateException failure = new IllegalStateException("业务失败");
        ExcelProcessingException exception = assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readMaps(new ByteArrayInputStream(bytes), (row, context) -> {
                throw failure;
            }));
        assertEquals(ExcelErrorType.CALLBACK, exception.getErrorType());
        assertEquals(failure, exception.getCause());
        assertEquals(2, exception.getRowNumber());
    }

    @Test
    void enforcesListInputAndCellLimits() throws Exception {
        byte[] bytes = createSimpleWorkbook(3);
        ExcelReadOptions listLimit = ExcelReadOptions.builder().maxListRows(2).build();
        ExcelProcessingException listException = assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readMapList(new ByteArrayInputStream(bytes), listLimit));
        assertEquals(ExcelErrorType.RESOURCE_LIMIT, listException.getErrorType());
        assertTrue(listException.getMessage().contains("readMaps"));

        ExcelReadOptions inputLimit = ExcelReadOptions.builder().maxInputBytes(10).build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream(bytes), inputLimit)).getErrorType());

        ExcelReadOptions cellLimit = ExcelReadOptions.builder().maxCells(2).build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream(bytes), cellLimit)).getErrorType());
    }

    @Test
    void enforcesRowColumnTextSheetAndTemporaryLimits() throws Exception {
        byte[] rows = createSimpleWorkbook(2);
        ExcelReadOptions rowLimit = ExcelReadOptions.builder().maxRows(1).build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream(rows), rowLimit)).getErrorType());

        ExcelReadOptions textLimit = ExcelReadOptions.builder().maxCellCharacters(3).build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream(rows), textLimit)).getErrorType());

        byte[] twoColumns;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var header = workbook.createSheet().createRow(0);
            header.createCell(0).setCellValue("一");
            header.createCell(1).setCellValue("二");
            workbook.write(output);
            twoColumns = output.toByteArray();
        }
        ExcelReadOptions columnLimit = ExcelReadOptions.builder().maxColumns(1).build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(
                    new ByteArrayInputStream(twoColumns), columnLimit)).getErrorType());

        ExcelReadOptions tempLimit = ExcelReadOptions.builder()
            .tempDirectory(temporaryDirectory)
            .maxTempBytes(10)
            .build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream(rows), tempLimit)).getErrorType());
        try (var paths = java.nio.file.Files.list(temporaryDirectory)) {
            assertEquals(0, paths.count());
        }

        byte[] multipleSheets;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("一").createRow(0).createCell(0).setCellValue("value");
            workbook.createSheet("二").createRow(0).createCell(0).setCellValue("value");
            workbook.write(output);
            multipleSheets = output.toByteArray();
        }
        ExcelReadOptions readSheetLimit = ExcelReadOptions.builder().maxSheets(1).build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(
                    new ByteArrayInputStream(multipleSheets), readSheetLimit)).getErrorType());

        ExcelWriteOptions sheetLimit = ExcelWriteOptions.builder()
            .maxRowsPerSheet(1)
            .maxSheets(1)
            .build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.writeMaps(
                    new ByteArrayOutputStream(),
                    List.of(singleMap("a"), singleMap("b")),
                    null,
                    sheetLimit)).getErrorType());

        List<Map<String, Object>> manyRows = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            manyRows.add(singleMap("临时数据-" + index));
        }
        ExcelWriteOptions writeTempLimit = ExcelWriteOptions.builder()
            .maxTempBytes(1)
            .build();
        assertEquals(ExcelErrorType.RESOURCE_LIMIT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.writeMaps(
                    new ByteArrayOutputStream(),
                    manyRows,
                    null,
                    writeTempLimit)).getErrorType());
    }

    @Test
    void rejectsXlsCorruptFilesDuplicateHeadersAndMissingSheets() throws Exception {
        byte[] xls;
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet().createRow(0).createCell(0).setCellValue("标题");
            workbook.write(output);
            xls = output.toByteArray();
        }
        ExcelProcessingException xlsException = assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readMapList(new ByteArrayInputStream(xls)));
        assertTrue(xlsException.getMessage().contains("仅支持 XLSX"));
        assertEquals(ExcelErrorType.FORMAT,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream("not-xlsx".getBytes()))).getErrorType());

        byte[] duplicate;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue("标题");
            row.createCell(1).setCellValue("标题");
            workbook.write(output);
            duplicate = output.toByteArray();
        }
        assertEquals(ExcelErrorType.MAPPING,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readMapList(new ByteArrayInputStream(duplicate))).getErrorType());
        ExcelReadOptions missing = ExcelReadOptions.builder().sheetName("不存在").build();
        assertTrue(assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readMapList(new ByteArrayInputStream(createSimpleWorkbook(1)), missing))
            .getMessage().contains("可用工作表"));
    }

    @Test
    void reportsObjectConversionContextAndTruncatesLongValues() throws Exception {
        String longValue = "x".repeat(200);
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("错误表");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("编号");
            header.createCell(1).setCellValue("名称");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(longValue);
            row.createCell(1).setCellValue("普通名称");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        ExcelProcessingException exception = assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readObjectList(new ByteArrayInputStream(bytes), RequiredRow.class));
        assertEquals(ExcelErrorType.CONVERSION, exception.getErrorType());
        assertEquals("错误表", exception.getSheetName());
        assertEquals(2, exception.getRowNumber());
        assertEquals(1, exception.getColumnNumber());
        assertEquals("id", exception.getFieldName());
        assertFalse(exception.getMessage().contains(longValue));
    }

    @Test
    void splitsSheetsAndRejectsUnknownMapKeys() throws Exception {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("index", index);
            values.add(row);
        }
        ExcelWriteOptions options = ExcelWriteOptions.builder().maxRowsPerSheet(2).maxSheets(3).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExcelUtils.writeMaps(output, values, null, options);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("index", workbook.getSheetAt(2).getRow(0).getCell(0).getStringCellValue());
        }

        LinkedHashMap<String, Object> first = new LinkedHashMap<>();
        first.put("known", 1);
        LinkedHashMap<String, Object> second = new LinkedHashMap<>();
        second.put("known", 2);
        second.put("unknown", 3);
        assertEquals(ExcelErrorType.MAPPING,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.writeMaps(new ByteArrayOutputStream(), List.of(first, second))).getErrorType());

        TrackingOutputStream tracking = new TrackingOutputStream();
        assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.writeMaps(tracking, List.of(first, second)));
        assertFalse(tracking.closed);
    }

    @Test
    void readsCachedFormulaWithoutEvaluatingIt() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("结果");
            sheet.createRow(1).createCell(0).setCellFormula("1+2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(sheet.getRow(1).getCell(0));
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertEquals("3", ExcelUtils.readMapList(new ByteArrayInputStream(bytes)).getFirst().get("结果"));
    }

    @Test
    void cleansReadTemporaryFilesAfterEarlyStopAndCallbackFailure() throws Exception {
        byte[] bytes = createSimpleWorkbook(3);
        ExcelReadOptions options = ExcelReadOptions.builder()
            .tempDirectory(temporaryDirectory)
            .build();
        ExcelUtils.readMaps(new ByteArrayInputStream(bytes), options, (row, context) -> false);
        assertTemporaryDirectoryEmpty();

        assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readMaps(new ByteArrayInputStream(bytes), options, (row, context) -> {
                throw new IllegalStateException("预期失败");
            }));
        assertTemporaryDirectoryEmpty();
    }

    private static byte[] createSimpleWorkbook(int rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("数据");
            sheet.createRow(0).createCell(0).setCellValue("value");
            for (int index = 0; index < rows; index++) {
                sheet.createRow(index + 1).createCell(0).setCellValue("v-" + index);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static LinkedHashMap<String, Object> singleMap(String value) {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("value", value);
        return row;
    }

    private void assertTemporaryDirectoryEmpty() throws IOException {
        try (var paths = java.nio.file.Files.list(temporaryDirectory)) {
            assertEquals(0, paths.count());
        }
    }

    /** 对象往返测试数据。 */
    static final class DemoRow {
        @ExcelColumn(value = "编号", order = 1)
        private long id;
        @ExcelColumn(value = "名称", order = 2)
        private String name;
        @ExcelColumn(value = "金额", order = 3)
        private BigDecimal amount;
        @ExcelColumn(value = "启用", order = 4)
        private boolean active;
        @ExcelColumn(value = "状态", order = 5)
        private State state;
        @ExcelColumn(value = "日期", order = 6, dateFormat = "yyyy-MM-dd")
        private LocalDate day;
        @ExcelColumn(value = "创建时间", order = 7)
        private LocalDateTime createdAt;
    }

    /** 必填与转换错误测试数据。 */
    static final class RequiredRow {
        @ExcelColumn(value = "编号", required = true)
        private int id;
        @ExcelColumn("名称")
        private String name;
    }

    /** 文本空白处理测试数据。 */
    static final class TextRow {
        @ExcelColumn("名称")
        private String name;
    }

    /** 枚举转换测试值。 */
    enum State {
        ENABLED,
        DISABLED
    }

    /** 记录输入流是否被关闭。 */
    private static final class TrackingInputStream extends FilterInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(new ByteArrayInputStream(bytes));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** 记录输出流是否被关闭。 */
    private static final class TrackingOutputStream extends FilterOutputStream {
        private final ByteArrayOutputStream delegate;
        private boolean closed;

        private TrackingOutputStream() {
            this(new ByteArrayOutputStream());
        }

        private TrackingOutputStream(ByteArrayOutputStream delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
