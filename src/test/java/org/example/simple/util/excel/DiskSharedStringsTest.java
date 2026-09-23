package org.example.simple.util.excel;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 磁盘共享字符串索引、富文本拼接和资源清理测试。 */
class DiskSharedStringsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesPlainRichAndRepeatedStringsOnDisk() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<si><t>普通</t></si><si><r><t>富</t></r><r><t>文本</t></r></si>"
            + "<si><t>普通</t><rPh><t>putong</t></rPh></si></sst>";
        ExcelReadOptions options = ExcelReadOptions.builder()
            .tempDirectory(temporaryDirectory)
            .maxTempBytes(1024 * 1024)
            .build();
        Path workspacePath;
        try (ExcelTempWorkspace workspace = new ExcelTempWorkspace(options)) {
            workspacePath = findWorkspace();
            try (DiskSharedStrings strings = DiskSharedStrings.load(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), workspace, 32_767)) {
                assertEquals("普通", strings.get(0));
                assertEquals("富文本", strings.get(1));
                assertEquals("普通", strings.get(2));
                assertEquals("普通", strings.get(0));
            }
        }
        assertFalse(Files.exists(workspacePath));
    }

    @Test
    void rejectsSharedStringsBeyondTemporaryBudget() {
        String xml = "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<si><t>内容超过预算</t></si></sst>";
        ExcelReadOptions options = ExcelReadOptions.builder()
            .tempDirectory(temporaryDirectory)
            .maxTempBytes(10)
            .build();
        try (ExcelTempWorkspace workspace = new ExcelTempWorkspace(options)) {
            ExcelProcessingException exception = assertThrows(ExcelProcessingException.class,
                () -> DiskSharedStrings.load(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), workspace, 32_767));
            assertEquals(ExcelErrorType.RESOURCE_LIMIT, exception.getErrorType());
        }
    }

    private Path findWorkspace() throws Exception {
        try (var paths = Files.list(temporaryDirectory)) {
            return paths.findFirst().orElseThrow();
        }
    }
}
