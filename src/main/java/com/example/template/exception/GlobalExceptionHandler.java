package com.example.template.exception;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.template.response.RestResult;
import com.example.template.response.RestResultUtils;

/**
 * REST 接口全局异常处理器，统一把常见请求错误和未预期异常转换为 {@link RestResult}。
 * <p>
 * 客户端输入问题保留 HTTP 400 语义；未预期异常返回 HTTP 500，并只向调用方暴露固定提示，
 * 完整异常信息通过日志和 {@code traceId} 关联排查。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LogManager.getLogger(GlobalExceptionHandler.class);
    private static final String VALIDATION_ERROR_MESSAGE = "请求参数校验失败";
    private static final String PARAMETER_ERROR_MESSAGE = "请求参数错误";
    private static final String REQUEST_BODY_ERROR_MESSAGE = "请求体格式错误";
    private static final String INTERNAL_ERROR_MESSAGE = "服务器内部错误";

    /**
     * 处理 {@code @Valid} 请求体产生的字段校验异常。
     *
     * @param exception 请求体字段校验异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<RestResult<Void>> handleMethodArgumentNotValid(
        MethodArgumentNotValidException exception) {
        return badRequest(firstBindingMessage(exception.getBindingResult()));
    }

    /**
     * 处理 Spring MVC 原生方法参数校验异常。
     *
     * @param exception 方法参数校验异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<RestResult<Void>> handleHandlerMethodValidation(
        HandlerMethodValidationException exception) {
        String message = exception.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream())
            .map(MessageSourceResolvable::getDefaultMessage)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(VALIDATION_ERROR_MESSAGE);
        return badRequest(message);
    }

    /**
     * 处理方法级校验代理产生的传统约束违规异常。
     *
     * @param exception 约束违规异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<RestResult<Void>> handleConstraintViolation(
        ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
            .map(ConstraintViolation::getMessage)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(VALIDATION_ERROR_MESSAGE);
        return badRequest(message);
    }

    /**
     * 处理模型属性或表单参数绑定失败。
     *
     * @param exception 参数绑定异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<RestResult<Void>> handleBindException(BindException exception) {
        return badRequest(firstBindingMessage(exception.getBindingResult()));
    }

    /**
     * 处理缺少必填查询参数的异常，并指出缺失的参数名称。
     *
     * @param exception 缺少必填参数异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<RestResult<Void>> handleMissingRequestParameter(
        MissingServletRequestParameterException exception) {
        return badRequest("缺少必填参数: " + exception.getParameterName());
    }

    /**
     * 处理查询参数或路径参数无法转换为目标 Java 类型的异常。
     *
     * @param exception 参数类型转换异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<RestResult<Void>> handleMethodArgumentTypeMismatch(
        MethodArgumentTypeMismatchException exception) {
        return badRequest("参数类型错误: " + exception.getName());
    }

    /**
     * 处理 JSON 语法错误或请求体字段类型不匹配导致的读取异常。
     *
     * @param exception 请求体不可读异常
     * @return HTTP 400 统一错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RestResult<Void>> handleHttpMessageNotReadable(
        HttpMessageNotReadableException exception) {
        return badRequest(REQUEST_BODY_ERROR_MESSAGE);
    }

    /**
     * 兜底处理未被更具体规则覆盖的异常。
     * <p>
     * 日志保留完整堆栈并显式记录当前 {@code traceId}，响应只返回固定消息，避免泄露内部实现。
     *
     * @param exception 未预期异常
     * @return HTTP 500 统一错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<RestResult<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("处理请求时发生未预期异常，traceId={}", ThreadContext.get("traceId"), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(RestResultUtils.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), INTERNAL_ERROR_MESSAGE));
    }

    /**
     * 创建 HTTP 400 响应，保证所有客户端输入错误使用相同的状态码和响应结构。
     *
     * @param message 面向调用方的错误说明
     * @return HTTP 400 统一错误响应
     */
    private ResponseEntity<RestResult<Void>> badRequest(String message) {
        String resolvedMessage = StringUtils.hasText(message) ? message : PARAMETER_ERROR_MESSAGE;
        return ResponseEntity.badRequest()
            .body(RestResultUtils.failure(HttpStatus.BAD_REQUEST.value(), resolvedMessage));
    }

    /**
     * 从 Spring 绑定结果中提取首项可读错误；快速失败模式下通常只有一项违规。
     *
     * @param bindingResult Spring 参数绑定结果
     * @return 首项错误消息，没有可读消息时返回稳定的校验失败提示
     */
    private String firstBindingMessage(BindingResult bindingResult) {
        return bindingResult.getAllErrors().stream()
            .map(ObjectError::getDefaultMessage)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(VALIDATION_ERROR_MESSAGE);
    }
}
