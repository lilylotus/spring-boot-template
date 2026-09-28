package org.example.simple.util.qrcode;

import java.awt.image.BufferedImage;

import com.google.zxing.LuminanceSource;

/**
 * 从 {@link BufferedImage} 取灰度值的最小 {@link LuminanceSource} 实现。
 * <p>
 * ZXing 的 {@code javase} 模块提供了功能更完整的 {@code BufferedImageLuminanceSource}
 * （支持裁剪、旋转，面向相机取景场景），但会带入 {@code jai-imageio-core}、{@code jcommander}
 * 等与本能力无关的传递依赖。本工具只需要解码已经是正向、完整的二维码图片，不做图像预处理，
 * 因此只实现 {@link #getRow(int, byte[])} 和 {@link #getMatrix()} 两个抽象方法，
 * 裁剪和旋转沿用基类默认的“不支持”实现。
 */
final class QrCodeImageLuminanceSource extends LuminanceSource {

    /** 逐像素缓存的灰度矩阵，构造时一次性计算完成。 */
    private final byte[] luminances;

    /**
     * 从图片计算灰度矩阵。
     *
     * @param image 待解码的图片
     */
    QrCodeImageLuminanceSource(BufferedImage image) {
        super(image.getWidth(), image.getHeight());
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        this.luminances = new byte[width * height];
        for (int index = 0; index < pixels.length; index++) {
            luminances[index] = toGrayscale(pixels[index]);
        }
    }

    /** {@inheritDoc} */
    @Override
    public byte[] getRow(int y, byte[] row) {
        int width = getWidth();
        byte[] target = row == null || row.length < width ? new byte[width] : row;
        System.arraycopy(luminances, y * width, target, 0, width);
        return target;
    }

    /** {@inheritDoc} */
    @Override
    public byte[] getMatrix() {
        return luminances.clone();
    }

    /**
     * 按 ITU-R BT.601 加权系数把 ARGB 像素转换为灰度值，与 ZXing {@code javase} 模块使用的系数一致。
     *
     * @param argb ARGB 像素
     * @return 灰度值（0-255，以有符号字节存储）
     */
    private static byte toGrayscale(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int gray = (299 * r + 587 * g + 114 * b) / 1000;
        return (byte) gray;
    }
}
