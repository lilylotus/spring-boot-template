package com.example.template.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * REST 接口统一响应模型，集中承载业务状态、响应数据、提示信息、链路标识和响应时间。
 *
 * @param code 业务状态码，成功时为 0，失败时使用对应的 HTTP 状态码
 * @param data 业务数据，没有数据时为 {@code null}
 * @param message 面向调用方的结果说明
 * @param traceId 当前请求的链路追踪标识，非请求线程中可能为 {@code null}
 * @param timestamp 响应创建时的 Unix 毫秒时间戳
 * @param <T> 业务数据类型
 */
@Schema(description = "REST接口统一响应")
public record RestResult<T>(
    @Schema(description = "业务状态码，成功时为0，失败时使用对应的HTTP状态码", example = "0")
    Integer code,
    @Schema(description = "业务数据，可为空")
    T data,
    @Schema(description = "结果说明", example = "成功")
    String message,
    @Schema(description = "链路追踪标识", example = "71c632f7159b4b50b507c4eb791e95ef")
    String traceId,
    @Schema(description = "响应创建时的Unix毫秒时间戳", example = "1787625600000")
    Long timestamp) {
}
