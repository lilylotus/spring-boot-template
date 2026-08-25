package com.example.template.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Jakarta Validation 常用约束示例请求，集中演示空值、长度、数值、格式和时间校验。
 * <p>
 * 每个字段对应一种主要约束，调用方可以通过校验示例接口观察合法值和非法值的处理结果。
 *
 * @param notNullValue 不允许为 {@code null} 的值
 * @param notEmptyValue 不允许为 {@code null} 或空集合的值
 * @param notBlankValue 不允许为 {@code null}、空串或纯空白的值
 * @param sizeValue 长度必须位于指定范围的文本
 * @param minValue 不得小于指定下限的整数
 * @param maxValue 不得大于指定上限的整数
 * @param positiveValue 必须为正数的整数
 * @param negativeValue 必须为负数的整数
 * @param emailValue 必须符合电子邮箱格式的文本
 * @param patternValue 必须符合指定正则表达式的文本
 * @param pastValue 必须早于当前日期的日期
 * @param futureValue 必须晚于当前日期的日期
 * @param decimalMinValue 不得小于指定下限的小数
 * @param decimalMaxValue 不得大于指定上限的小数
 */
@Schema(description = "Jakarta Validation常用约束示例请求")
public record ValidationExampleRequest(
    @NotNull(message = "notNullValue不能为null")
    @Schema(description = "@NotNull示例", example = "存在的值")
    String notNullValue,

    @NotEmpty(message = "notEmptyValue不能为空")
    @Schema(description = "@NotEmpty示例", example = "[\"item\"]")
    List<String> notEmptyValue,

    @NotBlank(message = "notBlankValue不能为空白")
    @Schema(description = "@NotBlank示例", example = "有效文本")
    String notBlankValue,

    @Size(min = 2, max = 10, message = "sizeValue长度必须在2到10之间")
    @Schema(description = "@Size(min=2,max=10)示例", example = "abcd")
    String sizeValue,

    @NotNull(message = "minValue不能为null")
    @Min(value = 1, message = "minValue不能小于1")
    @Schema(description = "@Min(1)示例", example = "1")
    Integer minValue,

    @NotNull(message = "maxValue不能为null")
    @Max(value = 100, message = "maxValue不能大于100")
    @Schema(description = "@Max(100)示例", example = "100")
    Integer maxValue,

    @NotNull(message = "positiveValue不能为null")
    @Positive(message = "positiveValue必须为正数")
    @Schema(description = "@Positive示例", example = "1")
    Integer positiveValue,

    @NotNull(message = "negativeValue不能为null")
    @Negative(message = "negativeValue必须为负数")
    @Schema(description = "@Negative示例", example = "-1")
    Integer negativeValue,

    @NotNull(message = "emailValue不能为null")
    @Email(message = "emailValue必须是合法邮箱")
    @Schema(description = "@Email示例", example = "user@example.com")
    String emailValue,

    @NotNull(message = "patternValue不能为null")
    @Pattern(regexp = "^[A-Z]{2}\\d{4}$", message = "patternValue必须为两个大写字母加四位数字")
    @Schema(description = "@Pattern示例，格式为两个大写字母加四位数字", example = "AB1234")
    String patternValue,

    @NotNull(message = "pastValue不能为null")
    @Past(message = "pastValue必须是过去日期")
    @Schema(description = "@Past示例", example = "2020-01-01")
    LocalDate pastValue,

    @NotNull(message = "futureValue不能为null")
    @Future(message = "futureValue必须是未来日期")
    @Schema(description = "@Future示例", example = "2099-01-01")
    LocalDate futureValue,

    @NotNull(message = "decimalMinValue不能为null")
    @DecimalMin(value = "0.01", message = "decimalMinValue不能小于0.01")
    @Schema(description = "@DecimalMin(0.01)示例", example = "0.01")
    BigDecimal decimalMinValue,

    @NotNull(message = "decimalMaxValue不能为null")
    @DecimalMax(value = "9999.99", message = "decimalMaxValue不能大于9999.99")
    @Schema(description = "@DecimalMax(9999.99)示例", example = "9999.99")
    BigDecimal decimalMaxValue) {
}
