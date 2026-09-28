package org.example.simple.util.qrcode;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QrCodeOptions 默认值、边界校验及自定义边长、透明背景效果测试。 */
class QrCodeOptionsTest {

    @Test
    void providesDocumentedDefaults() {
        QrCodeOptions options = QrCodeOptions.defaults();

        assertEquals(300, options.getSize());
        assertEquals(1, options.getMargin());
        assertEquals(QrCodeErrorCorrection.MEDIUM, options.getErrorCorrection());
        assertEquals(StandardCharsets.UTF_8, options.getCharset());
        assertEquals(0xFF000000, options.getForegroundColor());
        assertEquals(0xFFFFFFFF, options.getBackgroundColor());
        assertEquals(null, options.getCenterText());
        assertEquals(Font.SANS_SERIF, options.getCenterTextFontName());
        assertEquals(0, options.getCenterTextFontSize());
        assertEquals(0xFF000000, options.getCenterTextColor());
        assertEquals(0xFFFFFFFF, options.getCenterTextBackgroundColor());
        assertEquals(0.22, options.getCenterTextAreaRatio());
    }

    @Test
    void rejectsOutOfRangeSizeAndMargin() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().size(20).build());
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().size(4097).build());
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().margin(-1).build());
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().margin(33).build());
    }

    @Test
    void rejectsNullErrorCorrectionAndCharset() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().errorCorrection(null).build());
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().charset(null).build());
    }

    @Test
    void largerSizeProducesSquareImageNotSmallerThanRequested() throws Exception {
        QrCodeOptions options = QrCodeOptions.builder().size(600).build();

        byte[] png = Base64.getDecoder().decode(QrCodeUtils.toBase64("size-test", options));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

        assertEquals(image.getWidth(), image.getHeight());
        assertTrue(image.getWidth() >= 600);
    }

    @Test
    void transparentBackgroundKeepsAlphaChannel() throws Exception {
        QrCodeOptions options = QrCodeOptions.builder()
            .backgroundColor(0x00FFFFFF)
            .build();

        byte[] png = Base64.getDecoder().decode(QrCodeUtils.toBase64("transparent-test", options));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

        assertTrue(image.getColorModel().hasAlpha());
        int corner = image.getRGB(0, 0);
        assertEquals(0, (corner >>> 24), "背景像素的 alpha 通道应保持透明");
    }

    @Test
    void rejectsBlankCenterTextAndBlankFontName() {
        assertThrows(IllegalArgumentException.class, () -> QrCodeOptions.builder().centerText("   ").build());
        assertThrows(IllegalArgumentException.class,
            () -> QrCodeOptions.builder().centerText("ok").centerTextFontName(" ").build());
    }

    @Test
    void rejectsOutOfRangeCenterTextFontSizeAndAreaRatio() {
        assertThrows(IllegalArgumentException.class,
            () -> QrCodeOptions.builder().centerTextFontSize(5).build());
        assertThrows(IllegalArgumentException.class,
            () -> QrCodeOptions.builder().centerTextFontSize(513).build());
        assertThrows(IllegalArgumentException.class,
            () -> QrCodeOptions.builder().centerTextAreaRatio(0.04).build());
        assertThrows(IllegalArgumentException.class,
            () -> QrCodeOptions.builder().centerTextAreaRatio(0.31).build());
    }

    @Test
    void rejectsCenterTextCombinedWithLowErrorCorrection() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> QrCodeOptions.builder()
                .centerText("提示")
                .errorCorrection(QrCodeErrorCorrection.LOW)
                .build());

        assertTrue(exception.getMessage().contains("LOW"));
    }

    @Test
    void allowsCenterTextWithHighErrorCorrection() {
        QrCodeOptions options = QrCodeOptions.builder()
            .centerText("提示")
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .build();

        assertEquals("提示", options.getCenterText());
    }
}
