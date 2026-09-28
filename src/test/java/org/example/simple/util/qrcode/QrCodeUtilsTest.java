package org.example.simple.util.qrcode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QrCodeUtils 四种输出形态、文本场景和异常语义测试。 */
class QrCodeUtilsTest {

    @Test
    void generatesBase64ThatDecodesToOriginalText() throws Exception {
        String text = "https://example.com/order/A-01?ref=test";

        String base64 = QrCodeUtils.toBase64(text);
        byte[] png = Base64.getDecoder().decode(base64);

        assertEquals(text, decode(png));
    }

    @Test
    void generatesDataUriWithFixedPrefixMatchingBase64() {
        String text = "订单 A-01";

        String base64 = QrCodeUtils.toBase64(text);
        String dataUri = QrCodeUtils.toDataUri(text);

        assertTrue(dataUri.startsWith("data:image/png;base64,"));
        assertEquals(base64, dataUri.substring("data:image/png;base64,".length()));
    }

    @Test
    void chineseAndUrlTextRoundTripWithoutGarbling() throws Exception {
        String chinese = "中文提示文本，包含标点和数字 2026";
        String url = "https://example.com/path?a=1&b=中文";

        assertEquals(chinese, decode(Base64.getDecoder().decode(QrCodeUtils.toBase64(chinese))));
        assertEquals(url, decode(Base64.getDecoder().decode(QrCodeUtils.toBase64(url))));
    }

    @Test
    void allFourOutputFormsProduceIdenticalPngBytes(@TempDir Path tempDir) throws Exception {
        String text = "https://example.com/order/A-02";
        QrCodeOptions options = QrCodeOptions.defaults();

        byte[] fromBase64 = Base64.getDecoder().decode(QrCodeUtils.toBase64(text, options));

        ByteArrayOutputStream streamOutput = new ByteArrayOutputStream();
        QrCodeUtils.write(text, streamOutput, options);

        Path file = tempDir.resolve("order-a02.png");
        QrCodeUtils.writeToFile(text, file, options);

        assertArrayEquals(fromBase64, streamOutput.toByteArray());
        assertArrayEquals(fromBase64, Files.readAllBytes(file));
        assertEquals(text, decode(fromBase64));
    }

    @Test
    void blankTextIsAcceptedAndDecodesBack() throws Exception {
        String blank = "   ";

        String base64 = QrCodeUtils.toBase64(blank);

        assertEquals(blank, decode(Base64.getDecoder().decode(base64)));
    }

    @Test
    void rejectsNullAndEmptyText() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toBase64(null));
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toBase64(""));
        assertThrows(IllegalArgumentException.class,
            () -> QrCodeUtils.write("x", new ByteArrayOutputStream(), null));
    }

    @Test
    void rejectsTextExceedingCapacityWithEncodingErrorType() {
        String tooLong = "a".repeat(5_000);

        QrCodeException exception = assertThrows(
            QrCodeException.class,
            () -> QrCodeUtils.toBase64(tooLong));

        assertEquals(QrCodeErrorType.ENCODING, exception.getErrorType());
    }

    @Test
    void writesToFileCreatingParentDirectoriesAndOverwriting(@TempDir Path tempDir) throws Exception {
        Path nested = tempDir.resolve("nested").resolve("dir").resolve("qr.png");

        QrCodeUtils.writeToFile("first", nested);
        assertTrue(Files.exists(nested));
        assertEquals("first", decode(Files.readAllBytes(nested)));

        QrCodeUtils.writeToFile("second", nested);
        assertEquals("second", decode(Files.readAllBytes(nested)));

        long tempFileCount;
        try (var files = Files.list(nested.getParent())) {
            tempFileCount = files.filter(path -> path.getFileName().toString().contains("qrcode-")).count();
        }
        assertEquals(0, tempFileCount, "写出成功后不应遗留临时文件");
    }

    @Test
    void failsWithIoErrorAndLeavesNoFileWhenParentPathIsBlocked(@TempDir Path tempDir) throws IOException {
        // "blocker" 已经是一个普通文件，无法在其下创建目录，模拟写出目标不可用的失败路径。
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path target = blocker.resolve("qr.png");

        QrCodeException exception = assertThrows(
            QrCodeException.class,
            () -> QrCodeUtils.writeToFile("blocked", target));

        assertEquals(QrCodeErrorType.IO, exception.getErrorType());
        assertFalse(Files.exists(target));
    }

    @Test
    void wrapsOutputStreamFailureAsIoError() {
        OutputStream failing = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("模拟输出流写出失败");
            }
        };

        QrCodeException exception = assertThrows(
            QrCodeException.class,
            () -> QrCodeUtils.write("stream-failure", failing));

        assertEquals(QrCodeErrorType.IO, exception.getErrorType());
    }

    @Test
    void writeDoesNotCloseCallerStreamAndAllowsFurtherWrites() throws IOException {
        TrackingOutputStream output = new TrackingOutputStream();

        QrCodeUtils.write("stream-owned-by-caller", output);
        output.write('x');

        assertFalse(output.closed);
    }

    @Test
    void concurrentGenerationWithSharedOptionsDoesNotCrossContaminate() throws Exception {
        QrCodeOptions options = QrCodeOptions.defaults();
        int threadCount = 8;
        String[] results = new String[threadCount];
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            threads[i] = new Thread(() -> {
                try {
                    byte[] png = Base64.getDecoder().decode(
                        QrCodeUtils.toBase64("payload-" + index, options));
                    results[index] = decode(png);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
        for (Thread thread : threads) {
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        for (int i = 0; i < threadCount; i++) {
            assertEquals("payload-" + i, results[i]);
        }
    }

    /** 用本工具自身的解码入口把 PNG 字节解码回原始文本，供往返测试使用。 */
    static String decode(byte[] png) {
        return QrCodeUtils.decode(new ByteArrayInputStream(png));
    }

    /** 记录是否被关闭的输出流，用于验证工具不关闭调用方传入的流。 */
    static final class TrackingOutputStream extends ByteArrayOutputStream {

        boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
