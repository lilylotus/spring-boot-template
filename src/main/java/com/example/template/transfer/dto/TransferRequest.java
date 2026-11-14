package com.example.template.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 转账接口的请求体。幂等键不在这里，而是通过 {@code Idempotency-Key} 请求头传递——它是"这次请求"的
 * 元信息而不是业务数据。"转出与转入不能是同一账户"属于跨字段规则，放在业务层校验。
 *
 * @param fromAccountNo 转出账户号
 * @param toAccountNo 转入账户号
 * @param amount 转账金额(元)，大于0且最多两位小数
 * @param remark 备注，可为空
 */
@Schema(description = "转账请求")
public record TransferRequest(
    @Schema(description = "转出账户号", example = "ACC1001")
    @NotBlank(message = "转出账户号不能为空")
    @Size(max = 32, message = "转出账户号长度不能超过32")
    String fromAccountNo,
    @Schema(description = "转入账户号", example = "ACC1002")
    @NotBlank(message = "转入账户号不能为空")
    @Size(max = 32, message = "转入账户号长度不能超过32")
    String toAccountNo,
    @Schema(description = "转账金额(元)，大于0且最多两位小数", example = "100.00")
    @NotNull(message = "转账金额不能为空")
    @DecimalMin(value = "0.01", message = "转账金额必须大于0")
    @Digits(integer = 16, fraction = 2, message = "转账金额最多16位整数和2位小数")
    BigDecimal amount,
    @Schema(description = "备注", example = "房租")
    @Size(max = 255, message = "备注长度不能超过255")
    String remark) {
}
