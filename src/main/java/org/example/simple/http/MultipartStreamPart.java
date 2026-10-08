package org.example.simple.http;

import java.io.InputStream;
import java.util.Objects;

/**
 * 基于输入流的 multipart 文件字段。
 * <p>
 * 与基于本地文件的 {@link MultipartFilePart} 并列，适用于文件内容来自内存、网络下载等非本地文件场景，
 * 调用方不需要先把内容落地成本地文件。
 * <p>
 * <b>所有权约定：</b>调用方将 {@code inputStream} 传入本记录后即转移该流的所有权给
 * {@link HttpClients}；{@code HttpClients} 会在请求发送完成后（无论成功、失败还是超时）关闭该流，
 * 调用方不应再手动关闭、也不应在传入后继续读取该流。
 *
 * @param fieldName 字段名称
 * @param inputStream 文件内容输入流
 * @param fileName 发送给服务端的文件名
 * @param contentType 文件媒体类型
 */
public record MultipartStreamPart(String fieldName, InputStream inputStream, String fileName, String contentType) {

    /**
     * 校验并创建 multipart 流文件字段。
     *
     * @param fieldName 字段名称
     * @param inputStream 文件内容输入流
     * @param fileName 文件名
     * @param contentType 文件媒体类型
     */
    public MultipartStreamPart {
        fieldName = FormRequestBody.requireText(fieldName, "multipart 流文件字段名称不能为空");
        inputStream = Objects.requireNonNull(inputStream, "multipart 文件输入流不能为空");
        fileName = FormRequestBody.requireText(fileName, "multipart 流文件名不能为空");
        contentType = MultipartFilePart.requireContentType(contentType);
    }
}
