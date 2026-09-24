package org.example.simple.util.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行时列配置 Map 导入及独立 API 重载测试。 */
class ExcelRuntimeColumnConfigTest {

    @Test
    void importsObjectsThroughClassOverload() throws Exception {
        byte[] bytes = workbook(List.of("名称", "备注"), List.of(List.of("甲", "正常")));

        AnnotatedRow row = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), AnnotatedRow.class).getFirst();

        assertEquals("甲", row.name);
        assertEquals("正常", row.note);
    }

    @Test
    void importsConfiguredMapsUsingFieldKeysAndEffectiveOrder() throws Exception {
        List<Map<String, Object>> columns = List.of(
            config("field", "note", "value", "备注", "order", 20),
            config("field", "name", "value", "客户名称", "order", 10));
        byte[] bytes = workbook(
            List.of("客户名称", "备注"),
            List.of(List.of("甲", "正常"), List.of("乙", "待确认")));

        List<LinkedHashMap<String, Object>> rows = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), columns);

        assertEquals(2, rows.size());
        assertEquals(List.of("name", "note"), new ArrayList<>(rows.getFirst().keySet()));
        assertEquals("甲", rows.getFirst().get("name"));
        assertEquals("正常", rows.getFirst().get("note"));
    }

    @Test
    void columnsOverloadReturnsConfiguredMapsWithoutGenericCast() throws Exception {
        List<Map<String, Object>> columns = List.of(
            config("field", "customerName", "value", "客户名称"));
        byte[] bytes = workbook(List.of("客户名称"), List.of(List.of("甲")));

        List<LinkedHashMap<String, Object>> rows = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), columns);

        assertInstanceOf(LinkedHashMap.class, rows.getFirst());
        assertEquals(Map.of("customerName", "甲"), rows.getFirst());
    }

    @Test
    void streamsConfiguredMapsWithoutAccumulatingRows() throws Exception {
        List<Map<String, Object>> columns = List.of(config("field", "name", "value", "姓名"));
        byte[] bytes = workbook(List.of("姓名"), List.of(List.of("甲"), List.of("乙")));
        List<String> names = new ArrayList<>();

        ExcelUtils.readObjects(new ByteArrayInputStream(bytes), columns, (row, context) -> {
            names.add((String) row.get("name"));
            return true;
        });

        assertEquals(List.of("甲", "乙"), names);
    }

    @Test
    void appliesTrimOptionAndMapsEmptyCellsToNull() throws Exception {
        List<Map<String, Object>> columns = List.of(
            config("field", "name", "value", "姓名"),
            config("field", "note", "value", "备注"));
        byte[] bytes = workbook(List.of("姓名", "备注"), List.of(List.of(" 甲 ", "")));

        LinkedHashMap<String, Object> trimmed = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), columns).getFirst();
        ExcelReadOptions preserveWhitespace = ExcelReadOptions.builder()
            .trimCellValues(false)
            .build();
        LinkedHashMap<String, Object> preserved = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), preserveWhitespace, columns).getFirst();

        assertEquals("甲", trimmed.get("name"));
        assertEquals(" 甲 ", preserved.get("name"));
        assertNull(trimmed.get("note"));
        assertNull(preserved.get("note"));
    }

    @Test
    void requiredConfiguredColumnRejectsWhitespaceEvenWhenTrimIsDisabled() throws Exception {
        List<Map<String, Object>> columns = List.of(
            config("field", "name", "value", "姓名", "required", true));
        byte[] bytes = workbook(List.of("姓名"), List.of(List.of("   ")));
        ExcelReadOptions options = ExcelReadOptions.builder().trimCellValues(false).build();

        ExcelProcessingException exception = assertThrows(
            ExcelProcessingException.class,
            () -> ExcelUtils.readObjectList(new ByteArrayInputStream(bytes), options, columns));

        assertEquals(ExcelErrorType.CONVERSION, exception.getErrorType());
        assertEquals("name", exception.getFieldName());
        assertEquals("姓名", exception.getHeader());
        assertEquals(2, exception.getRowNumber());
    }

    @Test
    void rejectsHeadersWithWrongCountOrIndex() throws Exception {
        List<Map<String, Object>> columns = List.of(
            config("field", "name", "value", "姓名"),
            config("field", "note", "value", "备注"));

        assertHeaderFailure(workbook(List.of("备注", "姓名"), List.of(List.of("值", "甲"))), columns);
        assertHeaderFailure(workbook(List.of("姓名"), List.of(List.of("甲"))), columns);
        assertHeaderFailure(
            workbook(List.of("姓名", "备注", "额外"), List.of(List.of("甲", "值", "额外值"))),
            columns);
    }

    @Test
    void rejectsInvalidRuntimeColumnConfigurations() {
        assertInvalid(null, "columns");
        assertInvalid(List.of(), "columns");
        assertInvalid(List.of(config()), "配置项=1", "键=field");
        assertInvalid(List.of(config("field", 1)), "配置项=1", "键=field");
        assertInvalid(List.of(config("field", "  ")), "配置项=1", "键=field");
        assertInvalid(List.of(config("field", "name", "value", 1)), "配置项=1", "键=value");
        assertInvalid(List.of(config("field", "name", "order", "1")), "配置项=1", "键=order");
        assertInvalid(List.of(config("field", "name", "required", "true")), "配置项=1", "键=required");
        assertInvalid(List.of(config("field", "name", "dateFormat", "yyyy-MM-dd")), "键=dateFormat");
        assertInvalid(
            List.of(config("field", "name"), config("field", "name")),
            "配置项=2",
            "键=field");
        assertInvalid(
            List.of(
                config("field", "first", "value", "重复"),
                config("field", "second", "value", "重复")),
            "配置项=2",
            "键=value");
        assertInvalid(
            List.of(config("field", "first", "order", 1), config("field", "second", "order", 1)),
            "配置项=2",
            "键=order");
    }

    private static void assertHeaderFailure(
        byte[] bytes,
        List<Map<String, Object>> columns) {
        ExcelProcessingException exception = assertThrows(
            ExcelProcessingException.class,
            () -> ExcelUtils.readObjectList(new ByteArrayInputStream(bytes), columns));
        assertEquals(ExcelErrorType.MAPPING, exception.getErrorType());
    }

    private static void assertInvalid(
        List<Map<String, Object>> columns,
        String... expectedMessages) {
        ExcelProcessingException exception = assertThrows(
            ExcelProcessingException.class,
            () -> ExcelConfiguredMapMapper.of(columns));
        assertEquals(ExcelErrorType.MAPPING, exception.getErrorType());
        for (String expectedMessage : expectedMessages) {
            assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
        }
    }

    private static byte[] workbook(List<String> headers, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var headerRow = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                headerRow.createCell(index).setCellValue(headers.get(index));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var dataRow = sheet.createRow(rowIndex + 1);
                List<String> values = rows.get(rowIndex);
                for (int columnIndex = 0; columnIndex < values.size(); columnIndex++) {
                    dataRow.createCell(columnIndex).setCellValue(values.get(columnIndex));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static Map<String, Object> config(Object... entries) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    /** 带注解的对象类型优先级测试数据。 */
    static final class AnnotatedRow {
        @ExcelColumn(value = "名称", order = 1, required = true)
        private String name;
        @ExcelColumn(value = "备注", order = 2)
        private String note;
    }
}
