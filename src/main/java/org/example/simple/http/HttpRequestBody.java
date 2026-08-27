package org.example.simple.http;

/**
 * HTTP 请求体的受控类型。
 */
public sealed interface HttpRequestBody
    permits BinaryRequestBody, FormRequestBody, JsonRequestBody, MultipartRequestBody {
}
