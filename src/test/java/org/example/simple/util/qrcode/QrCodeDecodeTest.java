package org.example.simple.util.qrcode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** QrCodeUtils.decode 三个重载的功能与失败路径测试。 */
class QrCodeDecodeTest {

    private static final String TEXT = "https://example.com/order/A-04?ref=decode-test";

    @Test
    void decodesFromBase64() {
        String base64 = QrCodeUtils.toBase64(TEXT);

        assertEquals(TEXT, QrCodeUtils.decode(base64));
    }

    @Test
    void decodesFromDataUriByStrippingPrefix() {
        String dataUri = QrCodeUtils.toDataUri(TEXT);
        String rawBase64 = dataUri.substring("data:image/png;base64,".length());

        assertEquals(TEXT, QrCodeUtils.decode(dataUri));
        assertEquals(QrCodeUtils.decode(dataUri), QrCodeUtils.decode(rawBase64));
    }

    @Test
    void decodesFromInputStreamAndFileProducedByWriteMethods(@TempDir Path tempDir) throws IOException {
        ByteArrayOutputStream streamOutput = new ByteArrayOutputStream();
        QrCodeUtils.write(TEXT, streamOutput);
        Path file = tempDir.resolve("decode-test.png");
        QrCodeUtils.writeToFile(TEXT, file);

        assertEquals(TEXT, QrCodeUtils.decode(new ByteArrayInputStream(streamOutput.toByteArray())));
        assertEquals(TEXT, QrCodeUtils.decode(file));
    }

    @Test
    void allFourSourcesAgreeOnSameTextAndOptions() throws IOException {
        QrCodeOptions options = QrCodeOptions.defaults();
        String base64 = QrCodeUtils.toBase64(TEXT, options);
        String dataUri = QrCodeUtils.toDataUri(TEXT, options);
        ByteArrayOutputStream streamOutput = new ByteArrayOutputStream();
        QrCodeUtils.write(TEXT, streamOutput, options);

        assertEquals(TEXT, QrCodeUtils.decode(base64));
        assertEquals(TEXT, QrCodeUtils.decode(dataUri));
        assertEquals(TEXT, QrCodeUtils.decode(new ByteArrayInputStream(streamOutput.toByteArray())));
    }

    @Test
    void decodesCodeWithCenterTextOverlayAndCustomColors() {
        QrCodeOptions options = QrCodeOptions.builder()
            .size(400)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("扫码")
            .foregroundColor(0xFF1F2937)
            .backgroundColor(0xFFFFFFFF)
            .build();

        String base64 = QrCodeUtils.toBase64(TEXT, options);

        assertEquals(TEXT, QrCodeUtils.decode(base64));
    }

    @Test
    void rejectsNullDecodeInputs() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.decode((String) null));
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.decode((InputStream) null));
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.decode((Path) null));
    }

    @Test
    void rejectsEmptyAndMalformedBase64Text() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.decode(""));
        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.decode("not-valid-base64!!"));
    }

    @Test
    void rejectsBytesThatAreNotAnImage() {
        String notAnImage = Base64.getEncoder().encodeToString("plain text, not a PNG".getBytes());

        QrCodeException exception = assertThrows(
            QrCodeException.class,
            () -> QrCodeUtils.decode(notAnImage));

        assertEquals(QrCodeErrorType.DECODING, exception.getErrorType());
    }

    @Test
    void rejectsImageWithoutRecognizableQrCode() throws IOException {
        // 生成一张不含任何二维码的纯色 PNG。
        java.awt.image.BufferedImage blank =
            new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(blank, "png", buffer);
        String base64 = Base64.getEncoder().encodeToString(buffer.toByteArray());

        QrCodeException exception = assertThrows(
            QrCodeException.class,
            () -> QrCodeUtils.decode(base64));

        assertEquals(QrCodeErrorType.DECODING, exception.getErrorType());
    }

    @Test
    void decodeDoesNotCloseCallerInputStream() {
        byte[] png = Base64.getDecoder().decode(QrCodeUtils.toBase64(TEXT));
        TrackingInputStream input = new TrackingInputStream(png);

        QrCodeUtils.decode(input);

        assertFalse(input.closed);
    }

    /** 记录是否被关闭的输入流，用于验证 decode 不关闭调用方传入的流。 */
    static final class TrackingInputStream extends ByteArrayInputStream {

        boolean closed;

        TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
