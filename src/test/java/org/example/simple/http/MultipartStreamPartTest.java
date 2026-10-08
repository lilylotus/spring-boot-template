package org.example.simple.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link MultipartStreamPart} 参数校验测试。
 */
class MultipartStreamPartTest {

    @Test
    void createsInstanceWithValidArguments() {
        InputStream stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
        MultipartStreamPart part = new MultipartStreamPart("file", stream, "data.bin", "application/octet-stream");

        assertEquals("file", part.fieldName());
        assertEquals(stream, part.inputStream());
        assertEquals("data.bin", part.fileName());
        assertEquals("application/octet-stream", part.contentType());
    }

    @Test
    void rejectsInvalidArguments() {
        InputStream stream = new ByteArrayInputStream(new byte[] {1});

        assertThrows(
            IllegalArgumentException.class,
            () -> new MultipartStreamPart(" ", stream, "data.bin", "application/octet-stream"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MultipartStreamPart(null, stream, "data.bin", "application/octet-stream"));
        assertThrows(
            NullPointerException.class,
            () -> new MultipartStreamPart("file", null, "data.bin", "application/octet-stream"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MultipartStreamPart("file", stream, " ", "application/octet-stream"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MultipartStreamPart("file", stream, "data.bin", "错误媒体类型"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MultipartStreamPart("file", stream, "data.bin", " "));
    }
}
