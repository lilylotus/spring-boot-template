package org.example.simple.util.qrcode;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

/**
 * 二维码模块到像素的渲染实现。
 * <p>
 * 直接使用 ZXing 的 {@link Encoder} 而不是更高层的 {@code QRCodeWriter#encode}，
 * 以便获得渲染时使用的每模块像素边长，供 {@link QrCodeCenterTextPainter}
 * 把中间提示文本背板对齐到模块网格；同时按调用方指定的前景色和背景色着色，
 * 而不是使用 ZXing 默认的黑白输出。
 */
final class QrCodeRenderer {

    /** 静态工具类不允许实例化。 */
    private QrCodeRenderer() {
    }

    /**
     * 渲染结果：图片本体及渲染时实际使用的每模块像素边长。
     *
     * @param image 渲染出的图片，尚未叠加中间提示文本
     * @param modulePixels 每个二维码模块对应的像素边长
     */
    record Rendered(BufferedImage image, int modulePixels) {
    }

    /**
     * 把文本编码为二维码模块矩阵并渲染为按调用方颜色着色的图片。
     *
     * @param text 待编码文本，调用方需先完成非空校验
     * @param options 生成选项
     * @return 渲染结果
     * @throws QrCodeException 文本超出所选纠错等级容量或无法按所选字符集编码时抛出
     */
    static Rendered render(String text, QrCodeOptions options) {
        QRCode code = encode(text, options);
        ByteMatrix matrix = code.getMatrix();
        int inputWidth = matrix.getWidth();
        int inputHeight = matrix.getHeight();
        int quietZone = options.getMargin();
        int qrWidth = inputWidth + quietZone * 2;
        int qrHeight = inputHeight + quietZone * 2;
        // 输出边长取请求边长与二维码自身所需最小边长中的较大值：
        // 每模块至少占一个像素时才可能被扫描识别，与 ZXing QRCodeWriter#renderResult 的取整策略一致，
        // 因此实际输出边长可能因取整略大于调用方请求的 size。
        int outputSize = Math.max(options.getSize(), Math.max(qrWidth, qrHeight));
        int multiple = Math.min(outputSize / qrWidth, outputSize / qrHeight);
        int leftPadding = (outputSize - inputWidth * multiple) / 2;
        int topPadding = (outputSize - inputHeight * multiple) / 2;

        BufferedImage image = new BufferedImage(outputSize, outputSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(options.getBackgroundColor(), true));
            graphics.fillRect(0, 0, outputSize, outputSize);
            graphics.setColor(new Color(options.getForegroundColor(), true));
            for (int inputY = 0, outputY = topPadding; inputY < inputHeight; inputY++, outputY += multiple) {
                for (int inputX = 0, outputX = leftPadding; inputX < inputWidth; inputX++, outputX += multiple) {
                    if (matrix.get(inputX, inputY) == 1) {
                        graphics.fillRect(outputX, outputY, multiple, multiple);
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
        return new Rendered(image, multiple);
    }

    /** 调用 ZXing 编码器生成模块矩阵，捕获容量或编码失败并包装为工具异常。 */
    private static QRCode encode(String text, QrCodeOptions options) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, options.getCharset().name());
            ErrorCorrectionLevel level = options.getErrorCorrection().toZxing();
            return Encoder.encode(text, level, hints);
        } catch (WriterException exception) {
            throw new QrCodeException(
                QrCodeErrorType.ENCODING,
                "文本无法编码为二维码，文本长度=" + text.length() + "，纠错等级=" + options.getErrorCorrection(),
                exception);
        }
    }
}
