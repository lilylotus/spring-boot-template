package com.example.template.util.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 使用指定媒体类型发送的二进制文件请求体。
 *
 * @param path 文件路径
 * @param contentType 文件媒体类型
 */
public record BinaryRequestBody(Path path, String contentType) implements HttpRequestBody {

    /**
     * 校验并创建二进制文件请求体。
     *
     * @param path 文件路径
     * @param contentType 文件媒体类型
     */
    public BinaryRequestBody {
        path = Objects.requireNonNull(path, "二进制文件路径不能为空");
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("二进制文件必须存在且可读: " + path);
        }
        contentType = MultipartFilePart.requireContentType(contentType);
    }
}
