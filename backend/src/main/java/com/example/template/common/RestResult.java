package com.example.template.common;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestResult<T> extends BaseResponse {

    private static final long serialVersionUID = -1712335074688408540L;

    private transient T result;

    public T getResult() {
        return result;
    }

    public void setResult(T result) {
        this.result = result;
    }

    public static <T> RestResult<T> success(T result) {
        RestResult<T> restResult = new RestResult<>();
        restResult.setCode("0");
        //restResult.setTraceId("1");
        restResult.setResult(result);
        restResult.setTimestamp(System.currentTimeMillis());
        return restResult;
    }

    public static RestResult<String> failure(String message) {
        RestResult<String> restResult = new RestResult<>();
        restResult.setCode("500");
        restResult.setError(message);
        restResult.setTimestamp(System.currentTimeMillis());
        return restResult;
    }

}
