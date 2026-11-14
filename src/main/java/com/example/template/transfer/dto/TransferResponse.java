package com.example.template.transfer.dto;

import com.example.template.transfer.entity.TransferOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转账接口的响应体。首次执行和重复请求的重放都由同一条转账流水转换而来，所以两次响应内容完全一致，
 * 调用方不需要区分"这次是真的执行了"还是"拿到的是上次的结果"。
 *
 * @param transferNo 转账流水号
 * @param idempotencyKey 本笔转账使用的幂等键
 * @param fromAccountNo 转出账户号
 * @param toAccountNo 转入账户号
 * @param amount 转账金额(元)
 * @param status 转账状态
 * @param createTime 转账流水创建时间
 */
@Schema(description = "转账响应")
public record TransferResponse(
    @Schema(description = "转账流水号", example = "71c632f7159b4b50b507c4eb791e95ef")
    String transferNo,
    @Schema(description = "本笔转账使用的幂等键", example = "6f1c0c1e-4a52-4c0b-9d7e-2f1f0a7b3c11")
    String idempotencyKey,
    @Schema(description = "转出账户号", example = "ACC1001")
    String fromAccountNo,
    @Schema(description = "转入账户号", example = "ACC1002")
    String toAccountNo,
    @Schema(description = "转账金额(元)", example = "100.00")
    BigDecimal amount,
    @Schema(description = "转账状态", example = "SUCCESS")
    String status,
    @Schema(description = "转账流水创建时间")
    LocalDateTime createTime) {

    /**
     * 由转账流水构造响应。
     *
     * @param order 已落库的转账流水
     * @return 对应的转账响应
     */
    public static TransferResponse from(TransferOrder order) {
        return new TransferResponse(
            order.getTransferNo(),
            order.getIdempotencyKey(),
            order.getFromAccountNo(),
            order.getToAccountNo(),
            order.getAmount(),
            order.getStatus(),
            order.getCreateTime());
    }
}
