package org.example.simple.util.qrcode;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.example.simple.util.qrcode.QrCodeUtilsTest.decode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 中间提示文本叠加的功能与失败路径测试。 */
class QrCodeCenterTextTest {

    private static final String TEXT = "https://example.com/order/A-03";

    @Test
    void unsetCenterTextProducesByteIdenticalOutputToPlainCode() {
        String withoutOverlay = QrCodeUtils.toBase64(TEXT);
        String explicitNull = QrCodeUtils.toBase64(TEXT, QrCodeOptions.defaults());

        assertEquals(withoutOverlay, explicitNull);
    }

    @Test
    void overlaidCodeStillDecodesToOriginalText() throws Exception {
        QrCodeOptions options = QrCodeOptions.builder()
            .size(400)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("扫码")
            .build();

        String base64 = QrCodeUtils.toBase64(TEXT, options);
        byte[] png = Base64.getDecoder().decode(base64);

        assertEquals(TEXT, decode(png));
    }

    @Test
    void multilineCenterTextStillDecodes() throws Exception {
        QrCodeOptions options = QrCodeOptions.builder()
            .size(400)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("扫码\n报修")
            .build();

        byte[] png = Base64.getDecoder().decode(QrCodeUtils.toBase64(TEXT, options));

        assertEquals(TEXT, decode(png));
    }

    @Test
    void autoFontSizeAdaptsForLongerTextAndStillDecodes() throws Exception {
        QrCodeOptions shortLabel = QrCodeOptions.builder()
            .size(500)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("A")
            .build();
        QrCodeOptions longerLabel = QrCodeOptions.builder()
            .size(500)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("设备编号 A-2026-0001")
            .build();

        byte[] shortPng = Base64.getDecoder().decode(QrCodeUtils.toBase64(TEXT, shortLabel));
        byte[] longPng = Base64.getDecoder().decode(QrCodeUtils.toBase64(TEXT, longerLabel));

        assertEquals(TEXT, decode(shortPng));
        assertEquals(TEXT, decode(longPng));
    }

    @Test
    void rejectsExplicitFontSizeThatOverflowsBackboard() {
        QrCodeOptions options = QrCodeOptions.builder()
            .size(200)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("这是一段用于测试超出背板范围的较长提示文本")
            .centerTextFontSize(400)
            .build();

        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toBase64(TEXT, options));
    }

    @Test
    void rejectsAutoFitTextThatCannotFitEvenAtMinimumFontSize() {
        // 极小背板占比配合很长的单行文本：无论怎样缩小字号都无法把整行放入可用宽度。
        QrCodeOptions options = QrCodeOptions.builder()
            .size(21)
            .margin(0)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerTextAreaRatio(0.05)
            .centerText("这是一段远远超出最小背板可用宽度的提示文本内容")
            .build();

        assertThrows(IllegalArgumentException.class, () -> QrCodeUtils.toBase64(TEXT, options));
    }

    @Test
    void rejectsCharacterThatCurrentFontCannotDisplay() {
        QrCodeOptions options = QrCodeOptions.builder()
            .size(400)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            // U+10FFFD 是 Unicode 私用区的最后一个码点，绝大多数字体（包括测试环境的默认字体）都没有为它注册字形。
            .centerText("提示􏿽")
            .build();

        QrCodeException exception = assertThrows(
            QrCodeException.class,
            () -> QrCodeUtils.toBase64(TEXT, options));

        assertEquals(QrCodeErrorType.FONT, exception.getErrorType());
    }

    @Test
    void multipleOutputFormsWithCenterTextRemainByteIdentical() throws Exception {
        QrCodeOptions options = QrCodeOptions.builder()
            .size(400)
            .errorCorrection(QrCodeErrorCorrection.HIGH)
            .centerText("扫码")
            .build();

        String first = QrCodeUtils.toBase64(TEXT, options);
        String second = QrCodeUtils.toBase64(TEXT, options);

        assertEquals(first, second);
        assertTrue(second.length() > 0);
    }
}
