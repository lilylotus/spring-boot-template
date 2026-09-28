package org.example.simple.util.qrcode;

import java.awt.Font;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 二维码生成选项，包含图片尺寸、颜色和可选的中间提示文本叠加配置。
 * <p>
 * 选项对象不可变，构建时统一校验，可安全地跨线程共享和重复使用。
 */
public final class QrCodeOptions {

    /** 版本 1 二维码的最小模块边长，低于该值无法保证每个模块至少占一个像素。 */
    private static final int MIN_SIZE = 21;
    /** 图片边长上限，避免一次调用申请过大的像素缓冲。 */
    private static final int MAX_SIZE = 4096;
    /** 静区宽度上限，单位为模块数。 */
    private static final int MAX_MARGIN = 32;
    /** 中间提示文本字号下限（像素），自动适配缩小到此值仍放不下时判定为失败；渲染时按此下限做自动适配搜索。 */
    static final int MIN_CENTER_TEXT_FONT_SIZE = 6;
    /** 中间提示文本字号上限（像素）；渲染时自动适配搜索不超过此值。 */
    static final int MAX_CENTER_TEXT_FONT_SIZE = 512;
    /** 中间提示文本背板占图片边长比例下限。 */
    private static final double MIN_CENTER_TEXT_AREA_RATIO = 0.05;
    /** 中间提示文本背板占图片边长比例上限，对应高纠错等级的可用恢复余量。 */
    private static final double MAX_CENTER_TEXT_AREA_RATIO = 0.30;

    /** 图片边长（像素），二维码为正方形，宽高相同。 */
    private final int size;
    /** 静区宽度，单位是模块数，不是像素。 */
    private final int margin;
    /** 纠错等级。 */
    private final QrCodeErrorCorrection errorCorrection;
    /** 文本编码。 */
    private final Charset charset;
    /** 暗模块颜色，ARGB。 */
    private final int foregroundColor;
    /** 亮模块颜色，ARGB，支持透明。 */
    private final int backgroundColor;
    /** 中间提示文本内容，{@code null} 表示不叠加。 */
    private final String centerText;
    /** 中间提示文本字体名称。 */
    private final String centerTextFontName;
    /** 中间提示文本字号（像素），{@code 0} 表示自动适配到能放入背板的最大字号。 */
    private final int centerTextFontSize;
    /** 中间提示文本颜色，ARGB。 */
    private final int centerTextColor;
    /** 中间提示文本背板颜色，ARGB；透明表示不铺背板。 */
    private final int centerTextBackgroundColor;
    /** 中间提示文本背板边长占图片边长的比例。 */
    private final double centerTextAreaRatio;

    /**
     * 从已校验构建器复制字段，形成不可变配置快照。
     *
     * @param builder 已完成校验的构建器
     */
    private QrCodeOptions(Builder builder) {
        this.size = builder.size;
        this.margin = builder.margin;
        this.errorCorrection = builder.errorCorrection;
        this.charset = builder.charset;
        this.foregroundColor = builder.foregroundColor;
        this.backgroundColor = builder.backgroundColor;
        this.centerText = builder.centerText;
        this.centerTextFontName = builder.centerTextFontName;
        this.centerTextFontSize = builder.centerTextFontSize;
        this.centerTextColor = builder.centerTextColor;
        this.centerTextBackgroundColor = builder.centerTextBackgroundColor;
        this.centerTextAreaRatio = builder.centerTextAreaRatio;
    }

    /**
     * 创建使用默认值的生成选项：300 像素、1 个模块静区、中等纠错、UTF-8、黑白配色，不叠加中间提示文本。
     *
     * @return 默认生成选项
     */
    public static QrCodeOptions defaults() {
        return builder().build();
    }

    /**
     * 创建生成选项构建器。
     *
     * @return 新生成选项构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回图片边长。
     *
     * @return 图片边长（像素）
     */
    public int getSize() {
        return size;
    }

    /**
     * 返回静区宽度。
     *
     * @return 静区宽度（模块数）
     */
    public int getMargin() {
        return margin;
    }

    /**
     * 返回纠错等级。
     *
     * @return 纠错等级
     */
    public QrCodeErrorCorrection getErrorCorrection() {
        return errorCorrection;
    }

    /**
     * 返回文本编码。
     *
     * @return 文本编码
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * 返回暗模块颜色。
     *
     * @return 暗模块颜色（ARGB）
     */
    public int getForegroundColor() {
        return foregroundColor;
    }

    /**
     * 返回亮模块颜色。
     *
     * @return 亮模块颜色（ARGB）
     */
    public int getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * 返回中间提示文本内容。
     *
     * @return 中间提示文本，未配置时为 {@code null}
     */
    public String getCenterText() {
        return centerText;
    }

    /**
     * 返回中间提示文本字体名称。
     *
     * @return 字体名称
     */
    public String getCenterTextFontName() {
        return centerTextFontName;
    }

    /**
     * 返回中间提示文本字号。
     *
     * @return 字号（像素），{@code 0} 表示自动适配
     */
    public int getCenterTextFontSize() {
        return centerTextFontSize;
    }

    /**
     * 返回中间提示文本颜色。
     *
     * @return 文本颜色（ARGB）
     */
    public int getCenterTextColor() {
        return centerTextColor;
    }

    /**
     * 返回中间提示文本背板颜色。
     *
     * @return 背板颜色（ARGB）
     */
    public int getCenterTextBackgroundColor() {
        return centerTextBackgroundColor;
    }

    /**
     * 返回中间提示文本背板占图片边长的比例。
     *
     * @return 背板占比
     */
    public double getCenterTextAreaRatio() {
        return centerTextAreaRatio;
    }

    /**
     * 二维码生成选项构建器。
     */
    public static final class Builder {

        /** 图片边长，默认 300 像素。 */
        private int size = 300;
        /** 静区宽度，默认 1 个模块。 */
        private int margin = 1;
        /** 纠错等级，默认中等。 */
        private QrCodeErrorCorrection errorCorrection = QrCodeErrorCorrection.MEDIUM;
        /** 文本编码，默认 UTF-8。 */
        private Charset charset = StandardCharsets.UTF_8;
        /** 暗模块颜色，默认不透明黑。 */
        private int foregroundColor = 0xFF000000;
        /** 亮模块颜色，默认不透明白。 */
        private int backgroundColor = 0xFFFFFFFF;
        /** 中间提示文本，默认不叠加。 */
        private String centerText;
        /** 中间提示文本字体名称，默认使用逻辑无衬线字体。 */
        private String centerTextFontName = Font.SANS_SERIF;
        /** 中间提示文本字号，默认 0 表示自动适配。 */
        private int centerTextFontSize;
        /** 中间提示文本颜色，默认不透明黑。 */
        private int centerTextColor = 0xFF000000;
        /** 中间提示文本背板颜色，默认不透明白。 */
        private int centerTextBackgroundColor = 0xFFFFFFFF;
        /** 中间提示文本背板占图片边长比例，默认 0.22。 */
        private double centerTextAreaRatio = 0.22;

        /** 仅允许通过 {@link QrCodeOptions#builder()} 创建构建器。 */
        private Builder() {
        }

        /**
         * 设置图片边长。
         *
         * @param size 图片边长（像素）
         * @return 当前构建器
         */
        public Builder size(int size) {
            this.size = size;
            return this;
        }

        /**
         * 设置静区宽度。
         *
         * @param margin 静区宽度（模块数）
         * @return 当前构建器
         */
        public Builder margin(int margin) {
            this.margin = margin;
            return this;
        }

        /**
         * 设置纠错等级。
         *
         * @param errorCorrection 纠错等级
         * @return 当前构建器
         */
        public Builder errorCorrection(QrCodeErrorCorrection errorCorrection) {
            this.errorCorrection = errorCorrection;
            return this;
        }

        /**
         * 设置文本编码。
         *
         * @param charset 文本编码
         * @return 当前构建器
         */
        public Builder charset(Charset charset) {
            this.charset = charset;
            return this;
        }

        /**
         * 设置暗模块颜色。
         *
         * @param foregroundColor 暗模块颜色（ARGB）
         * @return 当前构建器
         */
        public Builder foregroundColor(int foregroundColor) {
            this.foregroundColor = foregroundColor;
            return this;
        }

        /**
         * 设置亮模块颜色。
         *
         * @param backgroundColor 亮模块颜色（ARGB），支持透明
         * @return 当前构建器
         */
        public Builder backgroundColor(int backgroundColor) {
            this.backgroundColor = backgroundColor;
            return this;
        }

        /**
         * 设置中间提示文本，支持使用 {@code \n} 显式换行。
         *
         * @param centerText 中间提示文本，{@code null} 表示不叠加
         * @return 当前构建器
         */
        public Builder centerText(String centerText) {
            this.centerText = centerText;
            return this;
        }

        /**
         * 设置中间提示文本字体名称。
         *
         * @param centerTextFontName 字体名称，逻辑字体名或已安装的物理字体名
         * @return 当前构建器
         */
        public Builder centerTextFontName(String centerTextFontName) {
            this.centerTextFontName = centerTextFontName;
            return this;
        }

        /**
         * 设置中间提示文本字号。
         *
         * @param centerTextFontSize 字号（像素），{@code 0} 表示自动适配到能放入背板的最大字号
         * @return 当前构建器
         */
        public Builder centerTextFontSize(int centerTextFontSize) {
            this.centerTextFontSize = centerTextFontSize;
            return this;
        }

        /**
         * 设置中间提示文本颜色。
         *
         * @param centerTextColor 文本颜色（ARGB）
         * @return 当前构建器
         */
        public Builder centerTextColor(int centerTextColor) {
            this.centerTextColor = centerTextColor;
            return this;
        }

        /**
         * 设置中间提示文本背板颜色。
         *
         * @param centerTextBackgroundColor 背板颜色（ARGB），透明表示不铺背板
         * @return 当前构建器
         */
        public Builder centerTextBackgroundColor(int centerTextBackgroundColor) {
            this.centerTextBackgroundColor = centerTextBackgroundColor;
            return this;
        }

        /**
         * 设置中间提示文本背板占图片边长的比例。
         *
         * @param centerTextAreaRatio 背板占比
         * @return 当前构建器
         */
        public Builder centerTextAreaRatio(double centerTextAreaRatio) {
            this.centerTextAreaRatio = centerTextAreaRatio;
            return this;
        }

        /**
         * 校验并创建不可变选项。
         *
         * @return 生成选项
         * @throws IllegalArgumentException 任一参数无效时抛出
         */
        public QrCodeOptions build() {
            requireRange(size, MIN_SIZE, MAX_SIZE, "图片边长");
            requireRange(margin, 0, MAX_MARGIN, "静区宽度");
            if (errorCorrection == null) {
                throw new IllegalArgumentException("纠错等级不能为 null");
            }
            if (charset == null) {
                throw new IllegalArgumentException("文本编码不能为 null");
            }
            validateCenterText();
            return new QrCodeOptions(this);
        }

        /**
         * 校验中间提示文本相关配置：文本不能是空白字符串、字体名称不能为空白、
         * 字号必须是自动适配或落在允许区间、背板占比必须落在允许区间，
         * 并且配置提示文本时纠错等级不能是恢复能力最弱的 {@code LOW}。
         */
        private void validateCenterText() {
            if (centerText != null && centerText.isBlank()) {
                throw new IllegalArgumentException("中间提示文本不能是空白字符串，未配置时应传入 null");
            }
            if (centerTextFontName == null || centerTextFontName.isBlank()) {
                throw new IllegalArgumentException("中间提示文本字体名称不能为空白");
            }
            if (centerTextFontSize != 0) {
                requireRange(
                    centerTextFontSize,
                    MIN_CENTER_TEXT_FONT_SIZE,
                    MAX_CENTER_TEXT_FONT_SIZE,
                    "中间提示文本字号");
            }
            if (centerTextAreaRatio < MIN_CENTER_TEXT_AREA_RATIO
                || centerTextAreaRatio > MAX_CENTER_TEXT_AREA_RATIO) {
                throw new IllegalArgumentException(
                    "中间提示文本背板占比必须在 " + MIN_CENTER_TEXT_AREA_RATIO
                        + " 到 " + MAX_CENTER_TEXT_AREA_RATIO + " 之间");
            }
            if (centerText != null && errorCorrection == QrCodeErrorCorrection.LOW) {
                throw new IllegalArgumentException(
                    "配置中间提示文本时纠错等级不能为 LOW，背板遮挡面积会超出该等级的恢复余量，建议使用 HIGH");
            }
        }

        /** 校验整数参数处于闭区间内。 */
        private static void requireRange(int value, int minimum, int maximum, String name) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(name + "必须在 " + minimum + " 到 " + maximum + " 之间");
            }
        }
    }
}
