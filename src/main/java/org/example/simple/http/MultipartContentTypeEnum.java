package org.example.simple.http;

/**
 * 常见的 multipart 文件媒体类型常量。
 * <p>
 * 每个常量对应一个标准 MIME 字符串（{@code type/subtype} 格式，不含 {@code charset} 等参数），
 * 可直接通过 {@link #getContentType()} 取值用于 {@link MultipartFilePart#of(String, java.nio.file.Path, String)}
 * 的 {@code contentType} 参数，避免调用方手写字符串字面量。
 * <p>
 * 本枚举只收录项目常用的媒体类型子集，并非 IANA 媒体类型的完整列表；未收录的类型调用方仍可直接传入合法的媒体类型字符串。
 */
public enum MultipartContentTypeEnum {

    /** 通用二进制流，适用于无法归类到其他具体类型的文件。 */
    OCTET_STREAM("application/octet-stream", "通用二进制流"),

    /** 纯文本文件。 */
    TEXT_PLAIN("text/plain", "纯文本文件"),

    /** JSON 文本文件。 */
    JSON("application/json", "JSON 文本文件"),

    /** XML 文本文件。 */
    XML("application/xml", "XML 文本文件"),

    /** PDF 文档。 */
    PDF("application/pdf", "PDF 文档"),

    /** ZIP 压缩包。 */
    ZIP("application/zip", "ZIP 压缩包"),

    /** JPEG 图片。 */
    JPEG("image/jpeg", "JPEG 图片"),

    /** PNG 图片。 */
    PNG("image/png", "PNG 图片"),

    /** GIF 图片。 */
    GIF("image/gif", "GIF 图片");

    private final String contentType;
    private final String description;

    MultipartContentTypeEnum(String contentType, String description) {
        this.contentType = contentType;
        this.description = description;
    }

    /**
     * 获取该常量对应的标准 MIME 字符串。
     *
     * @return 媒体类型字符串，例如 {@code "application/octet-stream"}
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * 获取该常量对应的中文描述。
     *
     * @return 中文描述文本
     */
    public String getDescription() {
        return description;
    }
}
