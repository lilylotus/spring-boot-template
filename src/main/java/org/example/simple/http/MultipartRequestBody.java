package org.example.simple.http;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 包含 UTF-8 文本字段、本地文件字段和流文件字段的 {@code multipart/form-data} 请求体。
 *
 * @param textFields 文本字段，一个名称可对应多个值
 * @param fileParts 基于本地文件的文件字段
 * @param streamParts 基于输入流的文件字段；其中每个流在请求发送完成后由 {@link HttpClients} 负责关闭，
 *     详见 {@link MultipartStreamPart}
 */
public record MultipartRequestBody(
    Map<String, List<String>> textFields,
    List<MultipartFilePart> fileParts,
    List<MultipartStreamPart> streamParts) implements HttpRequestBody {

    /**
     * 创建 multipart 请求体。
     *
     * @param textFields 文本字段
     * @param fileParts 基于本地文件的文件字段
     * @param streamParts 基于输入流的文件字段
     */
    public MultipartRequestBody {
        textFields = FormRequestBody.copyValues(textFields, "multipart 文本");
        fileParts = List.copyOf(Objects.requireNonNull(fileParts, "multipart 文件字段不能为空"));
        fileParts.forEach(part -> Objects.requireNonNull(part, "multipart 文件字段不能为空"));
        streamParts = List.copyOf(Objects.requireNonNull(streamParts, "multipart 流文件字段不能为空"));
        streamParts.forEach(part -> Objects.requireNonNull(part, "multipart 流文件字段不能为空"));
        if (textFields.isEmpty() && fileParts.isEmpty() && streamParts.isEmpty()) {
            throw new IllegalArgumentException("multipart 请求体至少需要一个文本、文件或流文件字段");
        }
    }

    /**
     * 创建不含流文件字段的 multipart 请求体，与新增 {@code streamParts} 之前的构造方式兼容。
     *
     * @param textFields 文本字段
     * @param fileParts 基于本地文件的文件字段
     */
    public MultipartRequestBody(Map<String, List<String>> textFields, List<MultipartFilePart> fileParts) {
        this(textFields, fileParts, List.of());
    }
}
