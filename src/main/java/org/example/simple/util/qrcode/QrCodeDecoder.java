package org.example.simple.util.qrcode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

/**
 * 二维码 PNG 字节到文本的解码实现。
 * <p>
 * 只请求 QR_CODE 格式并开启 {@code TRY_HARDER}，不传字符集解码提示：本工具生成的二维码在
 * 使用非默认字符集时，{@code Encoder} 会自动写入 ECI 标记，解码器按标记自解译即可正确还原文本，
 * 该行为只保证“本工具生成、本工具解码”的场景。{@link MultiFormatReader} 内部会吸收具体解码器
 * 抛出的 {@code FormatException}/{@code ChecksumException}，找不到码或找到但内容非法时
 * 统一只抛出 {@link NotFoundException}。
 */
final class QrCodeDecoder {

    /** 静态工具类不允许实例化。 */
    private QrCodeDecoder() {
    }

    /**
     * 把 PNG 字节解码为其编码的原始文本。
     *
     * @param pngBytes 待解码的图片字节
     * @return 解码得到的原始文本
     * @throws QrCodeException 字节不是可识别的图片，或图片中未找到可识别的二维码，或找到但内容无法通过校验时抛出
     */
    static String decode(byte[] pngBytes) {
        BufferedImage image = readImage(pngBytes);
        QrCodeImageLuminanceSource source = new QrCodeImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        try {
            Result result = new MultiFormatReader().decode(bitmap, decodeHints());
            return result.getText();
        } catch (NotFoundException exception) {
            throw new QrCodeException(
                QrCodeErrorType.DECODING,
                "图片中未找到可识别的 QR_CODE 二维码，或内容无法通过校验",
                exception);
        }
    }

    /** 把字节解析为图片，字节不是可识别的图片格式时抛出解码类别的工具异常。 */
    private static BufferedImage readImage(byte[] pngBytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (image == null) {
                throw new QrCodeException(QrCodeErrorType.DECODING, "待解码字节不是可识别的图片格式");
            }
            return image;
        } catch (IOException exception) {
            throw new QrCodeException(QrCodeErrorType.DECODING, "解析待解码图片失败", exception);
        }
    }

    /** 只识别 QR_CODE 格式，并开启 TRY_HARDER 提高对自定义颜色和中间文本叠加图片的识别成功率。 */
    private static Map<DecodeHintType, Object> decodeHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return hints;
    }
}
