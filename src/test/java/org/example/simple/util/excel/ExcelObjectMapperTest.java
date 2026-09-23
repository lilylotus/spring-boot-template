package org.example.simple.util.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XLSX 对象元数据、继承字段和内置类型转换测试。 */
class ExcelObjectMapperTest {

    @Test
    void convertsRemainingSupportedTypesAndInheritedFields() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            List<String> headers = List.of(
                "父字段", "字节", "短整数", "整数", "浮点", "双精度", "字符", "大整数", "旧日期");
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("父值");
            row.createCell(1).setCellValue(127);
            row.createCell(2).setCellValue(32000);
            row.createCell(3).setCellValue(123456);
            row.createCell(4).setCellValue(1.25);
            row.createCell(5).setCellValue(2.5);
            row.createCell(6).setCellValue("中");
            row.createCell(7).setCellValue("900719925474099312345");
            row.createCell(8).setCellValue("2026-09-23");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        AllTypesRow actual = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), AllTypesRow.class).getFirst();
        assertEquals("父值", ((ParentRow) actual).parentValue);
        assertEquals((byte) 127, actual.byteValue);
        assertEquals((short) 32000, actual.shortValue);
        assertEquals(123456, actual.intValue);
        assertEquals(1.25F, actual.floatValue);
        assertEquals(2.5D, actual.doubleValue);
        assertEquals('中', actual.character);
        assertEquals(new BigInteger("900719925474099312345"), actual.bigInteger);
        assertNotNull(actual.legacyDate);
    }

    @Test
    void validatesMetadataBeforeProcessingRows() throws Exception {
        byte[] missingRequired;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet().createRow(0).createCell(0).setCellValue("其他");
            workbook.write(output);
            missingRequired = output.toByteArray();
        }
        assertEquals(ExcelErrorType.MAPPING,
            assertThrows(ExcelProcessingException.class,
                () -> ExcelUtils.readObjectList(
                    new ByteArrayInputStream(missingRequired), RequiredHeaderRow.class)).getErrorType());
        assertThrows(ExcelProcessingException.class, () -> ExcelObjectMapper.of(DuplicateHeaderRow.class));
        assertThrows(ExcelProcessingException.class, () -> ExcelObjectMapper.of(DuplicateOrderRow.class));
        assertThrows(ExcelProcessingException.class, () -> ExcelObjectMapper.of(UnsupportedTypeRow.class));
        assertThrows(ExcelProcessingException.class, () -> ExcelObjectMapper.of(NoColumnsRow.class));
        assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readObjectList(
                new ByteArrayInputStream(missingRequired), NoDefaultConstructorRow.class));
    }

    @Test
    void rejectsHeaderCountsAndIndexesThatDifferFromDeclarations() {
        ExcelObjectMapper<OrderedChildRow> mapper = ExcelObjectMapper.of(OrderedChildRow.class);
        String sheetName = "数据表";

        ExcelProcessingException missing = assertThrows(
            ExcelProcessingException.class,
            () -> mapper.validateHeaders(List.of("子级显式", "父级显式", "父级默认"), sheetName));
        assertEquals(ExcelErrorType.MAPPING, missing.getErrorType());
        assertEquals(sheetName, missing.getSheetName());
        assertTrue(missing.getMessage().contains("期望列数=4"));
        assertTrue(missing.getMessage().contains("实际列数=3"));

        ExcelProcessingException extra = assertThrows(
            ExcelProcessingException.class,
            () -> mapper.validateHeaders(
                List.of("子级显式", "父级显式", "父级默认", "子级默认", "额外列"), sheetName));
        assertEquals(ExcelErrorType.MAPPING, extra.getErrorType());
        assertTrue(extra.getMessage().contains("期望列数=4"));
        assertTrue(extra.getMessage().contains("实际列数=5"));

        ExcelProcessingException reordered = assertThrows(
            ExcelProcessingException.class,
            () -> mapper.validateHeaders(
                List.of("父级显式", "子级显式", "父级默认", "子级默认"), sheetName));
        assertEquals(ExcelErrorType.MAPPING, reordered.getErrorType());
        assertEquals(sheetName, reordered.getSheetName());
        assertEquals(1, reordered.getColumnNumber());
        assertEquals("父级显式", reordered.getHeader());
        assertEquals("childOrdered", reordered.getFieldName());
        assertTrue(reordered.getMessage().contains("期望标题=子级显式"));
        assertTrue(reordered.getMessage().contains("实际标题=父级显式"));
    }

    @Test
    void importsWhenHeadersMatchExplicitImplicitAndInheritedOrder() throws Exception {
        byte[] bytes = createTextWorkbook(
            List.of("子级显式", "父级显式", "父级默认", "子级默认"),
            List.of("子级显式值", "父级显式值", "父级默认值", "子级默认值"));

        OrderedChildRow actual = ExcelUtils.readObjectList(
            new ByteArrayInputStream(bytes), OrderedChildRow.class).getFirst();

        assertEquals("子级显式值", actual.childOrdered);
        assertEquals("父级显式值", ((OrderedParentRow) actual).parentOrdered);
        assertEquals("父级默认值", ((OrderedParentRow) actual).parentDefault);
        assertEquals("子级默认值", actual.childDefault);
    }

    @Test
    void rejectsNumericRangeOverflow() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("字节");
            sheet.createRow(1).createCell(0).setCellValue(128);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        ExcelProcessingException exception = assertThrows(ExcelProcessingException.class,
            () -> ExcelUtils.readObjectList(new ByteArrayInputStream(bytes), ByteOnlyRow.class));
        assertEquals(ExcelErrorType.CONVERSION, exception.getErrorType());
    }

    @Test
    void treatsWhitespaceOnlyTextAsEmptyDuringObjectConversion() throws Exception {
        ExcelReadOptions preserveWhitespace = ExcelReadOptions.builder()
            .trimCellValues(false)
            .build();

        byte[] requiredBytes = createTextWorkbook("必填", "\u3000");
        ExcelProcessingException requiredException = assertThrows(
            ExcelProcessingException.class,
            () -> ExcelUtils.readObjectList(
                new ByteArrayInputStream(requiredBytes),
                RequiredHeaderRow.class,
                preserveWhitespace));
        assertEquals(ExcelErrorType.CONVERSION, requiredException.getErrorType());
        assertEquals("value", requiredException.getFieldName());

        byte[] optionalBytes = createTextWorkbook("可空", "\u2003");
        OptionalTextRow optional = ExcelUtils.readObjectList(
            new ByteArrayInputStream(optionalBytes),
            OptionalTextRow.class,
            preserveWhitespace).getFirst();
        assertNull(optional.value);
    }

    private static byte[] createTextWorkbook(String header, String value) throws Exception {
        return createTextWorkbook(List.of(header), List.of(value));
    }

    private static byte[] createTextWorkbook(List<String> headers, List<String> values) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var headerRow = sheet.createRow(0);
            var dataRow = sheet.createRow(1);
            for (int index = 0; index < headers.size(); index++) {
                headerRow.createCell(index).setCellValue(headers.get(index));
                dataRow.createCell(index).setCellValue(values.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    /** 继承字段测试基类。 */
    static class ParentRow {
        @ExcelColumn(value = "父字段", order = 1)
        private String parentValue;
    }

    /** 其余内置类型测试对象。 */
    static final class AllTypesRow extends ParentRow {
        @ExcelColumn(value = "字节", order = 2)
        private byte byteValue;
        @ExcelColumn(value = "短整数", order = 3)
        private short shortValue;
        @ExcelColumn(value = "整数", order = 4)
        private int intValue;
        @ExcelColumn(value = "浮点", order = 5)
        private float floatValue;
        @ExcelColumn(value = "双精度", order = 6)
        private double doubleValue;
        @ExcelColumn(value = "字符", order = 7)
        private char character;
        @ExcelColumn(value = "大整数", order = 8)
        private BigInteger bigInteger;
        @ExcelColumn(value = "旧日期", order = 9, dateFormat = "yyyy-MM-dd")
        private Date legacyDate;
    }

    /** 缺失必填标题测试对象。 */
    static final class RequiredHeaderRow {
        @ExcelColumn(value = "必填", required = true)
        private String value;
    }

    /** 可空文本判空测试对象。 */
    static final class OptionalTextRow {
        @ExcelColumn("可空")
        private String value;
    }

    /** 重复标题测试对象。 */
    static final class DuplicateHeaderRow {
        @ExcelColumn("重复")
        private String first;
        @ExcelColumn("重复")
        private String second;
    }

    /** 重复显式顺序测试对象。 */
    static final class DuplicateOrderRow {
        @ExcelColumn(value = "一", order = 1)
        private String first;
        @ExcelColumn(value = "二", order = 1)
        private String second;
    }

    /** 不支持类型测试对象。 */
    static final class UnsupportedTypeRow {
        @ExcelColumn("日期")
        private LocalDate[] values;
    }

    /** 没有列声明的测试对象。 */
    static final class NoColumnsRow {
        private String value;
    }

    /** 没有无参构造器的导入对象。 */
    static final class NoDefaultConstructorRow {
        @ExcelColumn("其他")
        private final String value;

        private NoDefaultConstructorRow(String value) {
            this.value = value;
        }
    }

    /** 数字范围测试对象。 */
    static final class ByteOnlyRow {
        @ExcelColumn("字节")
        private byte value;
    }

    /** 标题顺序测试父类，包含显式顺序和默认顺序字段。 */
    static class OrderedParentRow {
        @ExcelColumn(value = "父级显式", order = 10)
        private String parentOrdered;
        @ExcelColumn("父级默认")
        private String parentDefault;
    }

    /** 标题顺序测试子类，用于验证显式、默认和继承字段的组合排序。 */
    static final class OrderedChildRow extends OrderedParentRow {
        @ExcelColumn(value = "子级显式", order = 1)
        private String childOrdered;
        @ExcelColumn("子级默认")
        private String childDefault;
    }
}
