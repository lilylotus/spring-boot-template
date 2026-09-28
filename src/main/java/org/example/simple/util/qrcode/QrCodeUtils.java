package org.example.simple.util.qrcode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;

import javax.imageio.ImageIO;

/**
 * 文本与 PNG 二维码互转的工具类：生成支持 Base64 字符串、data URI、指定文件和调用方输出流四种输出形态，
 * 并支持在二维码中间叠加提示文本；解码支持从 Base64/data URI 文本、调用方输入流和指定文件还原原始文本。
 * <p>
 * 工具不会关闭调用方传入的流。默认选项见 {@link QrCodeOptions#defaults()}；参数非法抛出
 * {@link IllegalArgumentException}，编码、字体、解码或读写失败抛出 {@link QrCodeException}。
 * 工具无可变静态状态，{@link QrCodeOptions} 不可变，均可安全地跨线程共享。
 * <pre>{@code
 * String base64 = QrCodeUtils.toBase64("https://example.com/order/A-01");
 * String dataUri = QrCodeUtils.toDataUri("订单 A-01");
 *
 * QrCodeOptions options = QrCodeOptions.builder()
 *     .size(512)
 *     .errorCorrection(QrCodeErrorCorrection.HIGH)
 *     .centerText("扫码\n报修")
 *     .build();
 * QrCodeUtils.writeToFile("https://example.com/order/A-01", Path.of("build/qr/A-01.png"), options);
 * QrCodeUtils.write("https://example.com/order/A-01", response.getOutputStream());
 *
 * String text = QrCodeUtils.decode(base64);
 * String sameText = QrCodeUtils.decode(Path.of("build/qr/A-01.png"));
 * }</pre>
 */
public final class QrCodeUtils {

    /** data URI 的固定前缀，后接标准 Base64 编码的 PNG 字节。 */
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";
    /** data URI 的方案前缀，用于在解码时识别并去除到第一个逗号为止的部分。 */
    private static final String DATA_URI_SCHEME = "data:";

    /** 静态工具类不允许实例化。 */
    private QrCodeUtils() {
    }

    /**
     * 使用默认选项把文本生成二维码，返回标准 Base64 编码的 PNG 字节。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @return 不含 data URI 前缀的标准 Base64 文本
     * @throws IllegalArgumentException 文本为 {@code null} 或空字符串时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static String toBase64(String text) {
        return toBase64(text, QrCodeOptions.defaults());
    }

    /**
     * 使用指定选项把文本生成二维码，返回标准 Base64 编码的 PNG 字节。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @param options 生成选项
     * @return 不含 data URI 前缀的标准 Base64 文本
     * @throws IllegalArgumentException 文本或选项非法时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static String toBase64(String text, QrCodeOptions options) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        write(text, buffer, options);
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * 使用默认选项把文本生成二维码，返回可直接用于 {@code img} 标签的 data URI。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @return {@code data:image/png;base64,} 前缀加标准 Base64 文本
     * @throws IllegalArgumentException 文本为 {@code null} 或空字符串时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static String toDataUri(String text) {
        return toDataUri(text, QrCodeOptions.defaults());
    }

    /**
     * 使用指定选项把文本生成二维码，返回可直接用于 {@code img} 标签的 data URI。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @param options 生成选项
     * @return {@code data:image/png;base64,} 前缀加标准 Base64 文本
     * @throws IllegalArgumentException 文本或选项非法时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static String toDataUri(String text, QrCodeOptions options) {
        return DATA_URI_PREFIX + toBase64(text, options);
    }

    /**
     * 使用默认选项把文本生成二维码并写入指定文件。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @param file 目标文件路径，父目录不存在时自动创建，同名文件将被覆盖
     * @throws IllegalArgumentException 文本、文件路径非法时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static void writeToFile(String text, Path file) {
        writeToFile(text, file, QrCodeOptions.defaults());
    }

    /**
     * 使用指定选项把文本生成二维码并写入指定文件。
     * <p>
     * 先在目标文件所在目录写入临时文件，再移动覆盖目标文件；任何失败路径都会清理临时文件，
     * 不会在目标位置留下不完整的 PNG。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @param file 目标文件路径，父目录不存在时自动创建，同名文件将被覆盖
     * @param options 生成选项
     * @throws IllegalArgumentException 文本、文件路径或选项非法时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static void writeToFile(String text, Path file, QrCodeOptions options) {
        requireText(text);
        requireNonNull(file, "目标文件路径不能为 null");
        requireNonNull(options, "生成选项不能为 null");
        BufferedImage image = renderImage(text, options);
        writeImageToFile(image, file);
    }

    /**
     * 使用默认选项把文本生成二维码并写入调用方提供的输出流。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @param output 输出流，方法不会关闭
     * @throws IllegalArgumentException 文本或输出流非法时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static void write(String text, OutputStream output) {
        write(text, output, QrCodeOptions.defaults());
    }

    /**
     * 使用指定选项把文本生成二维码并写入调用方提供的输出流。
     * <p>
     * 方法不会关闭该输出流；写出中途失败时输出流可能已包含不完整字节。
     *
     * @param text 待编码文本，不能为 {@code null} 或空字符串；纯空白文本合法
     * @param output 输出流，方法不会关闭
     * @param options 生成选项
     * @throws IllegalArgumentException 文本、输出流或选项非法时抛出
     * @throws QrCodeException 文本编码失败或写出失败时抛出
     */
    public static void write(String text, OutputStream output, QrCodeOptions options) {
        requireText(text);
        requireNonNull(output, "输出流不能为 null");
        requireNonNull(options, "生成选项不能为 null");
        BufferedImage image = renderImage(text, options);
        writePng(image, output);
    }

    /**
     * 把二维码 PNG 解码为其编码的原始文本。
     * <p>
     * 同时接受标准 Base64 文本和 {@link #toDataUri} 产出的完整 data URI：以 {@code data:} 开头时
     * 自动识别并去除到第一个逗号为止的前缀，效果与直接传入去除前缀后的 Base64 文本一致。
     * 只识别 QR_CODE 码制。
     *
     * @param base64OrDataUri 标准 Base64 文本，或 {@code data:image/png;base64,} 前缀的 data URI；
     *     不能为 {@code null} 或空字符串
     * @return 解码得到的原始文本
     * @throws IllegalArgumentException 入参为 {@code null}、空字符串，或 Base64 格式本身非法时抛出
     * @throws QrCodeException 图片无法识别为二维码时抛出
     */
    public static String decode(String base64OrDataUri) {
        requireText(base64OrDataUri);
        byte[] pngBytes = Base64.getDecoder().decode(stripDataUriPrefix(base64OrDataUri));
        return QrCodeDecoder.decode(pngBytes);
    }

    /**
     * 从调用方提供的输入流读取二维码 PNG 并解码为其编码的原始文本。
     * <p>
     * 方法不会关闭该输入流。只识别 QR_CODE 码制。
     *
     * @param input 输入流，方法不会关闭
     * @return 解码得到的原始文本
     * @throws IllegalArgumentException 输入流为 {@code null} 时抛出
     * @throws QrCodeException 读取失败，或图片无法识别为二维码时抛出
     */
    public static String decode(InputStream input) {
        requireNonNull(input, "输入流不能为 null");
        byte[] pngBytes;
        try {
            pngBytes = input.readAllBytes();
        } catch (IOException exception) {
            throw new QrCodeException(QrCodeErrorType.IO, "读取待解码输入流失败", exception);
        }
        return QrCodeDecoder.decode(pngBytes);
    }

    /**
     * 从指定文件读取二维码 PNG 并解码为其编码的原始文本。
     * <p>
     * 只识别 QR_CODE 码制。
     *
     * @param file 待解码的图片文件路径
     * @return 解码得到的原始文本
     * @throws IllegalArgumentException 文件路径为 {@code null} 时抛出
     * @throws QrCodeException 读取失败，或图片无法识别为二维码时抛出
     */
    public static String decode(Path file) {
        requireNonNull(file, "待解码文件路径不能为 null");
        byte[] pngBytes;
        try {
            pngBytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new QrCodeException(QrCodeErrorType.IO, "读取待解码文件失败，文件=" + file, exception);
        }
        return QrCodeDecoder.decode(pngBytes);
    }

    /** 去除 data URI 前缀（若存在），未使用该前缀时原样返回入参本身作为 Base64 负载。 */
    private static String stripDataUriPrefix(String base64OrDataUri) {
        if (!base64OrDataUri.startsWith(DATA_URI_SCHEME)) {
            return base64OrDataUri;
        }
        int commaIndex = base64OrDataUri.indexOf(',');
        if (commaIndex < 0) {
            throw new IllegalArgumentException("data URI 缺少逗号分隔符，无法定位 Base64 负载");
        }
        return base64OrDataUri.substring(commaIndex + 1);
    }

    /** 渲染二维码模块并按选项叠加中间提示文本，返回最终图片。 */
    private static BufferedImage renderImage(String text, QrCodeOptions options) {
        QrCodeRenderer.Rendered rendered = QrCodeRenderer.render(text, options);
        QrCodeCenterTextPainter.paint(rendered.image(), rendered.modulePixels(), options);
        return rendered.image();
    }

    /** 校验待编码文本：拒绝 {@code null} 和空字符串，纯空白文本视为合法内容。 */
    private static void requireText(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("待编码文本不能为 null 或空字符串");
        }
    }

    /**
     * 校验必填引用参数非空，使用 {@link IllegalArgumentException} 而不是
     * {@link NullPointerException}，使本工具的全部入参非法情形共用同一种异常类型。
     */
    private static void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /** 把图片编码为 PNG 并写入输出流，不关闭该流。 */
    private static void writePng(BufferedImage image, OutputStream output) {
        try {
            boolean written = ImageIO.write(image, "png", output);
            if (!written) {
                throw new QrCodeException(QrCodeErrorType.IO, "当前运行环境未注册可用的 PNG 图像写出器");
            }
        } catch (IOException exception) {
            throw new QrCodeException(
                QrCodeErrorType.IO,
                "写出二维码 PNG 失败，输出流中可能已包含不完整字节",
                exception);
        }
    }

    /**
     * 把图片写入目标文件：先写同目录临时文件再移动覆盖，失败时清理临时文件，
     * 避免目标路径出现无法解码的残缺文件。
     */
    private static void writeImageToFile(BufferedImage image, Path file) {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("目标文件路径没有可用的父目录：" + target);
        }
        Path tempFile = null;
        try {
            Files.createDirectories(parent);
            tempFile = Files.createTempFile(parent, "qrcode-", ".png.tmp");
            try (OutputStream output = Files.newOutputStream(tempFile)) {
                writePng(image, output);
            }
            moveIntoPlace(tempFile, target);
            tempFile = null;
        } catch (IOException exception) {
            throw new QrCodeException(QrCodeErrorType.IO, "写出二维码文件失败，目标=" + target, exception);
        } finally {
            if (tempFile != null) {
                deleteQuietly(tempFile);
            }
        }
    }

    /** 优先使用原子移动覆盖目标文件，文件系统不支持原子移动时退回普通移动覆盖。 */
    private static void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 尽力删除临时文件，清理失败不影响调用方已经收到的主异常。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不是主流程需要报告的错误，忽略即可。
        }
    }
}
