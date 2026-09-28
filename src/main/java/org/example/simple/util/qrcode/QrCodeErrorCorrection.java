package org.example.simple.util.qrcode;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/**
 * 二维码纠错等级，对应 QR 规范定义的四档码字冗余能力。
 * <p>
 * 使用工具自有枚举而不是直接暴露 ZXing 的 {@link ErrorCorrectionLevel}，
 * 使调用方的编译期依赖不因此扩散到第三方库。
 */
public enum QrCodeErrorCorrection {

    /** 约可恢复 7% 的码字，不能与中间提示文本叠加同时使用。 */
    LOW,
    /** 约可恢复 15% 的码字。 */
    MEDIUM,
    /** 约可恢复 25% 的码字。 */
    QUARTILE,
    /** 约可恢复 30% 的码字，推荐配合中间提示文本叠加使用。 */
    HIGH;

    /**
     * 转换为 ZXing 对应的纠错等级，仅供包内渲染逻辑使用。
     *
     * @return ZXing 纠错等级
     */
    ErrorCorrectionLevel toZxing() {
        return switch (this) {
            case LOW -> ErrorCorrectionLevel.L;
            case MEDIUM -> ErrorCorrectionLevel.M;
            case QUARTILE -> ErrorCorrectionLevel.Q;
            case HIGH -> ErrorCorrectionLevel.H;
        };
    }
}
