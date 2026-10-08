package org.example.simple.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link MultipartContentTypeEnum} 常量取值测试。
 */
class MultipartContentTypeEnumTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void getContentTypeReturnsExpectedMimeString() {
        assertEquals("application/octet-stream", MultipartContentTypeEnum.OCTET_STREAM.getContentType());
        assertEquals("text/plain", MultipartContentTypeEnum.TEXT_PLAIN.getContentType());
        assertEquals("application/json", MultipartContentTypeEnum.JSON.getContentType());
        assertEquals("application/xml", MultipartContentTypeEnum.XML.getContentType());
        assertEquals("application/pdf", MultipartContentTypeEnum.PDF.getContentType());
        assertEquals("application/zip", MultipartContentTypeEnum.ZIP.getContentType());
        assertEquals("image/jpeg", MultipartContentTypeEnum.JPEG.getContentType());
        assertEquals("image/png", MultipartContentTypeEnum.PNG.getContentType());
        assertEquals("image/gif", MultipartContentTypeEnum.GIF.getContentType());
    }

    @Test
    void contentTypeValuesAreUnique() {
        Set<String> contentTypes = new HashSet<>();
        for (MultipartContentTypeEnum value : MultipartContentTypeEnum.values()) {
            assertFalse(contentTypes.contains(value.getContentType()), "重复的媒体类型: " + value.getContentType());
            contentTypes.add(value.getContentType());
        }
        assertEquals(MultipartContentTypeEnum.values().length, contentTypes.size());
    }

    @Test
    void descriptionIsNonBlankForEveryConstant() {
        for (MultipartContentTypeEnum value : MultipartContentTypeEnum.values()) {
            assertFalse(value.getDescription().isBlank(), value.name() + " 的描述不能为空白");
        }
    }

    @Test
    void contentTypePassesMultipartFilePartValidation() throws IOException {
        Path file = temporaryDirectory.resolve("文件.bin");
        Files.writeString(file, "内容");
        for (MultipartContentTypeEnum value : MultipartContentTypeEnum.values()) {
            assertDoesNotThrow(() -> MultipartFilePart.of("file", file, value.getContentType()));
        }
    }
}
