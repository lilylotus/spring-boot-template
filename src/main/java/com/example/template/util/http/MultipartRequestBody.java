package com.example.template.util.http;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 包含 UTF-8 文本字段和文件字段的 {@code multipart/form-data} 请求体。
 *
 * @param textFields 文本字段，一个名称可对应多个值
 * @param fileParts  文件字段
 */
public record MultipartRequestBody(
    Map<String, List<String>> textFields,
    List<MultipartFilePart> fileParts) implements HttpRequestBody {

    /**
     * 创建 multipart 请求体。
     *
     * @param textFields 文本字段
     * @param fileParts  文件字段
     */
    public MultipartRequestBody {
        textFields = FormRequestBody.copyValues(textFields, "multipart 文本");
        fileParts = List.copyOf(Objects.requireNonNull(fileParts, "multipart 文件字段不能为空"));
        fileParts.forEach(part -> Objects.requireNonNull(part, "multipart 文件字段不能为空"));
        if (textFields.isEmpty() && fileParts.isEmpty()) {
            throw new IllegalArgumentException("multipart 请求体至少需要一个文本或文件字段");
        }
    }
}
