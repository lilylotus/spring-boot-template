package org.example.simple.util.excel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XLSX 读写配置默认值和边界测试。 */
class ExcelOptionsTest {

    @Test
    void providesBoundedReadDefaults() {
        ExcelReadOptions options = ExcelReadOptions.defaults();
        assertEquals(0, options.getSheetIndex());
        assertEquals(0, options.getHeaderRowIndex());
        assertEquals(1, options.getDataStartRowIndex());
        assertTrue(options.isSkipBlankRows());
        assertTrue(options.isTrimHeaders());
        assertTrue(options.isTrimCellValues());
        assertEquals(100_000, options.getMaxListRows());
        assertEquals(16_384, options.getMaxColumns());
        assertEquals(100, options.getMaxSheets());
        assertTrue(options.getMaxInputBytes() > 0);
        assertTrue(options.getMaxTempBytes() > options.getMaxInputBytes());
        assertFalse(ExcelReadOptions.builder().trimCellValues(false).build().isTrimCellValues());
    }

    @Test
    void rejectsInvalidReadBounds() {
        assertThrows(IllegalArgumentException.class,
            () -> ExcelReadOptions.builder().sheetIndex(-1).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelReadOptions.builder().dataStartRowIndex(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelReadOptions.builder().maxInputBytes(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelReadOptions.builder().maxColumns(16_385).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelReadOptions.builder().maxSheets(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelReadOptions.builder().maxCellCharacters(32_768).build());
    }

    @Test
    void providesBoundedWriteDefaultsAndRejectsInvalidValues() {
        ExcelWriteOptions options = ExcelWriteOptions.defaults();
        assertEquals("数据", options.getSheetName());
        assertEquals(100, options.getRowWindowSize());
        assertTrue(options.isCompressTempFiles());
        assertTrue(options.getMaxTempBytes() > 0);
        assertThrows(IllegalArgumentException.class,
            () -> ExcelWriteOptions.builder().sheetName(" ").build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelWriteOptions.builder().maxRowsPerSheet(1_048_576).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelWriteOptions.builder().rowWindowSize(0).build());
        assertThrows(IllegalArgumentException.class,
            () -> ExcelWriteOptions.builder().maxTempBytes(0).build());
    }
}
