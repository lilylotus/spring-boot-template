package org.example.simple.util.excel;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 独立 JVM 受限堆探针，验证高基数共享字符串读取和惰性导出不会累计全部行。
 */
public final class ExcelMemoryProbe {

    private static final int READ_ROWS = 80_000;
    private static final int WRITE_ROWS = 150_000;

    private ExcelMemoryProbe() {
    }

    /**
     * 在 Gradle 创建的 96 MiB 堆 JVM 中运行流式验证。
     *
     * @param arguments 未使用
     * @throws Exception 文件生成或校验失败时抛出
     */
    public static void main(String[] arguments) throws Exception {
        Path input = Files.createTempFile("xlsx-memory-input-", ".xlsx");
        Path output = Files.createTempFile("xlsx-memory-output-", ".xlsx");
        try {
            createHighCardinalityWorkbook(input, READ_ROWS);
            AtomicInteger read = new AtomicInteger();
            try (var stream = Files.newInputStream(input)) {
                ExcelUtils.readMaps(stream, (row, context) -> {
                    int index = read.getAndIncrement();
                    if (!row.get("value").equals("唯一值-" + index)) {
                        throw new AssertionError("共享字符串读取结果不一致，行=" + context.rowNumber());
                    }
                    return true;
                });
            }
            if (read.get() != READ_ROWS) {
                throw new AssertionError("流式读取行数不正确：" + read.get());
            }
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
                ExcelWriteOptions options = ExcelWriteOptions.builder()
                    .maxCells(WRITE_ROWS)
                    .build();
                ExcelUtils.writeMaps(stream, lazyRows(WRITE_ROWS), null, options);
            }
            if (Files.size(output) == 0) {
                throw new AssertionError("流式导出文件为空");
            }
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    private static Iterable<LinkedHashMap<String, ?>> lazyRows(int count) {
        return () -> new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public LinkedHashMap<String, ?> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                row.put("value", "导出值-" + index++);
                return row;
            }
        };
    }

    private static void createHighCardinalityWorkbook(Path path, int rowCount) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            writeEntry(zip, "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/sharedStrings.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
                  <Override PartName="/xl/styles.xml"
                    ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                </Types>
                """);
            writeEntry(zip, "_rels/.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                    Target="xl/workbook.xml"/>
                </Relationships>
                """);
            writeEntry(zip, "xl/workbook.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="数据" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
                """);
            writeEntry(zip, "xl/_rels/workbook.xml.rels", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                    Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings"
                    Target="sharedStrings.xml"/>
                  <Relationship Id="rId3"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles"
                    Target="styles.xml"/>
                </Relationships>
                """);
            writeEntry(zip, "xl/styles.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <fonts count="1"><font/></fonts>
                  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
                  <borders count="1"><border/></borders>
                  <cellStyleXfs count="1"><xf/></cellStyleXfs>
                  <cellXfs count="1"><xf xfId="0"/></cellXfs>
                </styleSheet>
                """);
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst "
                + "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\""
                + (rowCount + 1) + "\" uniqueCount=\"" + (rowCount + 1) + "\"><si><t>value</t></si>");
            for (int index = 0; index < rowCount; index++) {
                write(zip, "<si><t>唯一值-" + index + "</t></si>");
            }
            write(zip, "</sst>");
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            write(zip, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet "
                + "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            write(zip, "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>");
            for (int index = 0; index < rowCount; index++) {
                int row = index + 2;
                write(zip, "<row r=\"" + row + "\"><c r=\"A" + row + "\" t=\"s\"><v>"
                    + (index + 1) + "</v></c></row>");
            }
            write(zip, "</sheetData></worksheet>");
            zip.closeEntry();
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        write(zip, content);
        zip.closeEntry();
    }

    private static void write(OutputStream output, String content) throws Exception {
        output.write(content.getBytes(StandardCharsets.UTF_8));
    }
}
