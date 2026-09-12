package com.example.template.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class BaseResponse implements Serializable {
    private static final long serialVersionUID = 7429981865691924671L;

    private String code;
    private String traceId;
    private long timestamp;
    private String error;
    private Map<String, Object> userUnique;
    private Map<String, Object> extUserAttrs;

}
