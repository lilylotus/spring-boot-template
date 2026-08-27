package org.example.simple.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.hc.core5.http.ContentType;

/**
 * multipart 请求中的文件字段。
 *
 * @param fieldName 字段名称
 * @param path 文件路径
 * @param fileName 发送给服务端的文件名
 * @param contentType 文件媒体类型
 */
public record MultipartFilePart(String fieldName, Path path, String fileName, String contentType) {

    /**
     * 校验并创建 multipart 文件字段。
     *
     * @param fieldName 字段名称
     * @param path 文件路径
     * @param fileName 文件名
     * @param contentType 文件媒体类型
     */
    public MultipartFilePart {
        fieldName = FormRequestBody.requireText(fieldName, "multipart 文件字段名称不能为空");
        path = Objects.requireNonNull(path, "multipart 文件路径不能为空");
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("multipart 文件必须存在且可读: " + path);
        }
        fileName = FormRequestBody.requireText(fileName, "multipart 文件名不能为空");
        contentType = requireContentType(contentType);
    }

    /**
     * 使用路径文件名创建 multipart 文件字段。
     *
     * @param fieldName 字段名称
     * @param path 文件路径
     * @param contentType 文件媒体类型
     * @return multipart 文件字段
     */
    public static MultipartFilePart of(String fieldName, Path path, String contentType) {
        Objects.requireNonNull(path, "multipart 文件路径不能为空");
        Path fileName = path.getFileName();
        if (fileName == null) {
            throw new IllegalArgumentException("multipart 文件路径必须包含文件名");
        }
        return new MultipartFilePart(fieldName, path, fileName.toString(), contentType);
    }

    static String requireContentType(String value) {
        String contentType = FormRequestBody.requireText(value, "媒体类型不能为空");
        try {
            String mimeType = ContentType.parse(contentType).getMimeType();
            if (!mimeType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) {
                throw new IllegalArgumentException("媒体类型不合法: " + contentType);
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("媒体类型不合法: " + contentType, exception);
        }
        return contentType;
    }
}
