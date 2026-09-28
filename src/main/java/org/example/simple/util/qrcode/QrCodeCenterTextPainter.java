package org.example.simple.util.qrcode;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 在渲染完成的二维码图片中间叠加提示文本。
 * <p>
 * 先铺一块居中背板再绘制文字：文字笔画直接画在模块上会被解码器误认为模块，
 * 铺一块纯色背板则等价于让中心区域整块“缺失”，落在二维码纠错能力的适用范围内。
 * 背板边界对齐到模块网格，避免中心出现半个模块的残留色块。
 */
final class QrCodeCenterTextPainter {

    /** 背板内文字可用区域占背板边长的比例，其余部分作为内边距。 */
    private static final double INNER_PADDING_RATIO = 0.85;

    /** 静态工具类不允许实例化。 */
    private QrCodeCenterTextPainter() {
    }

    /**
     * 在图片中间叠加提示文本，未配置提示文本时不做任何修改。
     *
     * @param image 已渲染完二维码模块的图片，就地修改
     * @param modulePixels 每个二维码模块对应的像素边长，用于把背板对齐到模块网格
     * @param options 生成选项
     * @throws IllegalArgumentException 提示文本在当前字号或自动适配下限下仍无法放入背板时抛出
     * @throws QrCodeException 提示文本包含当前字体无法显示的字符时抛出
     */
    static void paint(BufferedImage image, int modulePixels, QrCodeOptions options) {
        String centerText = options.getCenterText();
        if (centerText == null) {
            return;
        }
        int imageSize = image.getWidth();
        int backboardPixels = resolveBackboardPixels(imageSize, modulePixels, options.getCenterTextAreaRatio());
        int origin = (imageSize - backboardPixels) / 2;
        String[] lines = centerText.split("\r?\n", -1);
        checkDisplayable(options.getCenterTextFontName(), String.join("", lines));

        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 背板色 alpha 为 0 表示不铺背板，只画文字，该组合仅适用于本身留白足够的场景。
            if ((options.getCenterTextBackgroundColor() >>> 24) != 0) {
                graphics.setColor(new Color(options.getCenterTextBackgroundColor(), true));
                graphics.fillRect(origin, origin, backboardPixels, backboardPixels);
            }

            int availableWidth = (int) (backboardPixels * INNER_PADDING_RATIO);
            int availableHeight = (int) (backboardPixels * INNER_PADDING_RATIO);
            Font font = resolveFont(graphics, lines, options, availableWidth, availableHeight);

            graphics.setFont(font);
            graphics.setColor(new Color(options.getCenterTextColor(), true));
            int centerX = origin + backboardPixels / 2;
            int centerY = origin + backboardPixels / 2;
            drawCenteredLines(graphics, lines, centerX, centerY);
        } finally {
            graphics.dispose();
        }
    }

    /**
     * 计算对齐到模块网格的背板边长：先按占比得到理想像素边长，
     * 再按模块边长向外取整到整数个模块，最后不超过图片边长。
     */
    private static int resolveBackboardPixels(int imageSize, int modulePixels, double areaRatio) {
        int idealPixels = (int) Math.round(imageSize * areaRatio);
        int modules = Math.max(1, (int) Math.ceil((double) idealPixels / modulePixels));
        return Math.min(modules * modulePixels, imageSize);
    }

    /** 校验字体能否显示提示文本的每个字符，避免绘制出缺字的“豆腐块”。 */
    private static void checkDisplayable(String fontName, String textWithoutNewlines) {
        Font probe = new Font(fontName, Font.PLAIN, 12);
        int index = probe.canDisplayUpTo(textWithoutNewlines);
        if (index != -1) {
            throw new QrCodeException(
                QrCodeErrorType.FONT,
                "中间提示文本存在字体无法显示的字符，字体=" + fontName + "，首个无法显示字符位置=" + index);
        }
    }

    /**
     * 解析绘制字体：显式指定字号时直接使用且不自动缩放，超出背板时快速失败；
     * 未指定字号（{@code 0}）时从上限字号开始逐步缩小，取第一个能完整放入背板的字号。
     */
    private static Font resolveFont(
        Graphics2D graphics,
        String[] lines,
        QrCodeOptions options,
        int availableWidth,
        int availableHeight) {
        String fontName = options.getCenterTextFontName();
        int explicitSize = options.getCenterTextFontSize();
        if (explicitSize != 0) {
            Font font = new Font(fontName, Font.PLAIN, explicitSize);
            if (!fits(graphics, font, lines, availableWidth, availableHeight)) {
                throw new IllegalArgumentException(
                    "中间提示文本在指定字号 " + explicitSize + " 下超出背板范围，请调小字号或增大 centerTextAreaRatio");
            }
            return font;
        }
        int startSize = Math.min(QrCodeOptions.MAX_CENTER_TEXT_FONT_SIZE, Math.max(availableHeight, 1));
        for (int candidate = startSize; candidate >= QrCodeOptions.MIN_CENTER_TEXT_FONT_SIZE; candidate--) {
            Font font = new Font(fontName, Font.PLAIN, candidate);
            if (fits(graphics, font, lines, availableWidth, availableHeight)) {
                return font;
            }
        }
        throw new IllegalArgumentException(
            "中间提示文本过长，自动缩小到最小字号 " + QrCodeOptions.MIN_CENTER_TEXT_FONT_SIZE
                + " 仍无法放入背板，请缩短文本或增大 centerTextAreaRatio");
    }

    /** 判断按给定字体绘制全部文本行时，最长行宽度和总行高是否都在可用范围内。 */
    private static boolean fits(
        Graphics2D graphics,
        Font font,
        String[] lines,
        int availableWidth,
        int availableHeight) {
        FontMetrics metrics = graphics.getFontMetrics(font);
        int totalHeight = metrics.getHeight() * lines.length;
        if (totalHeight > availableHeight) {
            return false;
        }
        for (String line : lines) {
            if (metrics.stringWidth(line) > availableWidth) {
                return false;
            }
        }
        return true;
    }

    /** 把已按换行符拆分的文本整体居中绘制在指定中心点。 */
    private static void drawCenteredLines(Graphics2D graphics, String[] lines, int centerX, int centerY) {
        FontMetrics metrics = graphics.getFontMetrics();
        int lineHeight = metrics.getHeight();
        int totalHeight = lineHeight * lines.length;
        int startY = centerY - totalHeight / 2 + metrics.getAscent();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int x = centerX - metrics.stringWidth(line) / 2;
            int y = startY + index * lineHeight;
            graphics.drawString(line, x, y);
        }
    }
}
