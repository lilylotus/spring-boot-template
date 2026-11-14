package com.example.template.transfer.controller;

import com.example.template.exception.GlobalExceptionHandler;
import com.example.template.log4j2.TraceIdFilter;
import com.example.template.transfer.dto.TransferResponse;
import com.example.template.transfer.exception.TransferException;
import com.example.template.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TransferController} 的Spring MVC层测试，用 {@link MockitoBean} 把 {@link TransferService}
 * 换成mock，不连接数据库和Redis，只验证入参校验(幂等键请求头、请求体字段)以及与全局异常处理器的协作。
 */
@WebMvcTest(controllers = TransferController.class)
@ContextConfiguration(classes = {
    TransferController.class,
    GlobalExceptionHandler.class,
    TraceIdFilter.class
})
class TransferControllerTest {

    private static final String VALID_BODY =
        "{\"fromAccountNo\":\"ACC1001\",\"toAccountNo\":\"ACC1002\",\"amount\":100.00,\"remark\":\"房租\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    /**
     * 验证参数合法时返回统一成功响应并带上流水号。
     */
    @Test
    void shouldReturnTransferResultWhenRequestValid() throws Exception {
        when(transferService.transfer(eq("key-1"), any())).thenReturn(new TransferResponse(
            "71c632f7159b4b50b507c4eb791e95ef",
            "key-1",
            "ACC1001",
            "ACC1002",
            new BigDecimal("100.00"),
            "SUCCESS",
            LocalDateTime.of(2026, 11, 14, 10, 0, 0)));

        mockMvc.perform(transferRequest(VALID_BODY).header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.transferNo").value("71c632f7159b4b50b507c4eb791e95ef"))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    /**
     * 验证缺少幂等键请求头时返回400而不是落到兜底的500，且不会进入业务层。
     */
    @Test
    void shouldRejectWhenIdempotencyKeyMissing() throws Exception {
        mockMvc.perform(transferRequest(VALID_BODY))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("请求头Idempotency-Key不能为空"));

        verifyNoInteractions(transferService);
    }

    /**
     * 验证幂等键超过64个字符时返回400。
     */
    @Test
    void shouldRejectWhenIdempotencyKeyTooLong() throws Exception {
        mockMvc.perform(transferRequest(VALID_BODY)
                .header(TransferController.IDEMPOTENCY_KEY_HEADER, "k".repeat(65)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(transferService);
    }

    /**
     * 验证金额为0、负数、超过两位小数时均返回400。
     */
    @Test
    void shouldRejectInvalidAmount() throws Exception {
        for (String amount : new String[] {"0", "-1.00", "1.001"}) {
            String body = "{\"fromAccountNo\":\"ACC1001\",\"toAccountNo\":\"ACC1002\",\"amount\":" + amount + "}";

            mockMvc.perform(transferRequest(body).header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        }

        verifyNoInteractions(transferService);
    }

    /**
     * 验证缺少转出账户号时返回400。
     */
    @Test
    void shouldRejectWhenAccountNoMissing() throws Exception {
        String body = "{\"toAccountNo\":\"ACC1002\",\"amount\":100.00}";

        mockMvc.perform(transferRequest(body).header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(transferService);
    }

    /**
     * 验证业务层抛出的转账业务异常按其自带的状态码和提示转换成统一错误响应。
     */
    @Test
    void shouldMapTransferExceptionToItsHttpStatus() throws Exception {
        when(transferService.transfer(eq("key-1"), any()))
            .thenThrow(new TransferException(HttpStatus.CONFLICT, TransferService.PROCESSING_MESSAGE));

        mockMvc.perform(transferRequest(VALID_BODY).header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(409))
            .andExpect(jsonPath("$.message").value(TransferService.PROCESSING_MESSAGE));
    }

    /**
     * 验证Redis故障等未预期异常(业务层不捕获、原样抛出)最终返回500和固定提示，不向调用方暴露内部细节。
     */
    @Test
    void shouldReturnInternalErrorWhenRedisUnavailable() throws Exception {
        when(transferService.transfer(eq("key-1"), any()))
            .thenThrow(new RedisConnectionFailureException("模拟Redis不可用"));

        mockMvc.perform(transferRequest(VALID_BODY).header(TransferController.IDEMPOTENCY_KEY_HEADER, "key-1"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value(500))
            .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    /**
     * 构造一个JSON请求体的转账POST请求，幂等键请求头由各用例自行决定是否添加。
     *
     * @param body JSON请求体
     * @return 尚未携带幂等键请求头的请求构造器
     */
    private MockHttpServletRequestBuilder transferRequest(String body) {
        return post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
