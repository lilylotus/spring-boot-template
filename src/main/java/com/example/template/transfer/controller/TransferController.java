package com.example.template.transfer.controller;

import com.example.template.response.RestResult;
import com.example.template.response.RestResultUtils;
import com.example.template.transfer.dto.TransferRequest;
import com.example.template.transfer.dto.TransferResponse;
import com.example.template.transfer.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口幂等示例：模拟日常交易转账。调用方为每一次"转账意图"生成一个唯一的幂等键放在
 * {@code Idempotency-Key} 请求头里，超时重试、重复点击时原样带上同一个幂等键，服务端保证资金只划转一次。
 */
@RestController
@Tag(name = "转账示例接口", description = "演示基于Idempotency-Key请求头、Redis幂等锁与数据库唯一索引的接口幂等")
public class TransferController {

    /** 幂等键请求头名称，沿用业界通行的命名。 */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 提交一笔转账。同一幂等键首次提交时执行转账；执行成功后再次提交返回首次的结果。
     * <p>
     * 幂等键请求头声明为 {@code required = false} 再用 {@code @NotBlank} 校验：缺少请求头时参数为null，
     * 由校验统一给出400和明确的提示；若用默认的 {@code required = true}，缺失时Spring抛的是
     * {@code MissingRequestHeaderException}，全局异常处理器没有对应分支，会落到兜底的500。
     *
     * @param idempotencyKey 调用方生成的幂等键，必填，最长64个字符
     * @param request 转账请求体
     * @return 包含转账流水信息的统一成功响应
     */
    @PostMapping("/api/transfers")
    @Operation(summary = "转账", description = "同一Idempotency-Key重复提交只执行一次转账，重复请求返回首次执行的结果")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "转账成功，或重复请求返回首次执行的结果"),
        @ApiResponse(responseCode = "400", description = "缺少幂等键、请求参数不合法或转出转入为同一账户"),
        @ApiResponse(responseCode = "404", description = "转出或转入账户不存在"),
        @ApiResponse(responseCode = "409", description = "相同幂等键的请求正在处理中，或幂等键已被内容不同的请求使用"),
        @ApiResponse(responseCode = "422", description = "转出账户余额不足")
    })
    public RestResult<TransferResponse> transfer(
        @Parameter(description = "幂等键，调用方为每次转账意图生成的唯一值，重试时保持不变", required = true)
        @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
        @NotBlank(message = "请求头Idempotency-Key不能为空")
        @Size(max = 64, message = "请求头Idempotency-Key长度不能超过64") String idempotencyKey,
        @Valid @RequestBody TransferRequest request) {
        return RestResultUtils.success(transferService.transfer(idempotencyKey, request));
    }
}
