package com.example.template.util.http;

/**
 * HTTP 请求体的受控类型。
 */
public sealed interface HttpRequestBody
    permits BinaryRequestBody, FormRequestBody, JsonRequestBody, MultipartRequestBody {
}
