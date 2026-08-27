package org.example.simple.http;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 公共模型、配置和请求体约束测试。
 */
class HttpModelsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void responseDataDefensivelyCopiesMutableContent() {
        byte[] body = "响应".getBytes(StandardCharsets.UTF_8);
        HttpResponseData response = new HttpResponseData(200, Map.of("X-Test", List.of("一", "二")), body);
        body[0] = 0;
        byte[] firstRead = response.body();
        firstRead[0] = 0;

        assertArrayEquals("响应".getBytes(StandardCharsets.UTF_8), response.body());
        assertEquals(List.of("一", "二"), response.headers().get("X-Test"));
        assertThrows(UnsupportedOperationException.class, () -> response.headers().put("X-New", List.of("值")));
        assertNotSame(firstRead, response.body());
    }

    @Test
    void statusExceptionKeepsResponseAndCause() {
        HttpResponseData response = new HttpResponseData(422, Map.of(), "错误".getBytes(StandardCharsets.UTF_8));
        IllegalStateException cause = new IllegalStateException("底层原因");
        HttpClientException statusException = new HttpClientException(
            HttpErrorType.HTTP_STATUS,
            "状态失败",
            response);
        HttpClientException transportException = new HttpClientException(
            HttpErrorType.TRANSPORT,
            "传输失败",
            cause);

        assertEquals(response, statusException.getResponse().orElseThrow());
        assertEquals(HttpErrorType.HTTP_STATUS, statusException.getErrorType());
        assertEquals(cause, transportException.getCause());
        assertTrue(transportException.getResponse().isEmpty());
    }

    @Test
    void clientConfigUsesSafeDefaultsAndValidatesTimeouts() {
        HttpClientConfig defaults = HttpClientConfig.defaults();

        assertEquals(Duration.ofSeconds(5), defaults.connectTimeout());
        assertEquals(Duration.ofSeconds(5), defaults.requestTimeout());
        assertFalse(defaults.skipSslVerification());
        assertTrue(defaults.objectMapper() instanceof ObjectMapper);
        assertThrows(
            NullPointerException.class,
            () -> new HttpClientConfig(null, Duration.ofSeconds(1), false, new ObjectMapper()));
        assertThrows(
            NullPointerException.class,
            () -> new HttpClientConfig(Duration.ofSeconds(1), null, false, new ObjectMapper()));
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpClientConfig.builder().connectTimeout(Duration.ZERO).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpClientConfig.builder().requestTimeout(Duration.ofNanos(1)).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpClientConfig.builder().connectTimeout(Duration.ofSeconds(-1)).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpClientConfig.builder().requestTimeout(Duration.ofSeconds(Long.MAX_VALUE)).build());
    }

    @Test
    void defaultObjectMapperHandlesUnknownNullAndJavaTimeValues() {
        ObjectMapper objectMapper = HttpClientConfig.defaults().objectMapper();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 27, 10, 15, 30);

        JsonPayload payload = objectMapper.readValue(
            "{\"name\":\"测试\",\"occurredAt\":\"2026-08-27T10:15:30\",\"unknown\":1}",
            JsonPayload.class);
        String json = objectMapper.writeValueAsString(new JsonPayload(null, occurredAt));

        assertEquals("测试", payload.name());
        assertEquals(occurredAt, payload.occurredAt());
        assertFalse(json.contains("name"));
        assertTrue(json.contains("\"occurredAt\":\"2026-08-27T10:15:30\""));
    }

    @Test
    void customObjectMapperIsUsedWithoutChangingItsConfiguration() {
        ObjectMapper custom = new ObjectMapper();
        boolean failOnUnknown = custom.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        HttpClientConfig config = HttpClientConfig.builder()
            .objectMapper(custom)
            .build();

        assertEquals(custom, config.objectMapper());
        assertEquals(failOnUnknown, custom.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
    }

    @Test
    void requestBuilderValidatesUriHeadersBodyAndTimeout() {
        HttpRequestData request = HttpRequestData.post("https://example.com/path?existing=1")
            .header("X-Test", "一")
            .header("X-Test", "二")
            .queryParameter("名称", "值")
            .body(new JsonRequestBody(Map.of("name", "测试")))
            .timeout(Duration.ofSeconds(2))
            .build();

        assertEquals(HttpMethod.POST, request.method());
        assertEquals("https://example.com/path?existing=1", request.url());
        assertEquals(List.of("一", "二"), request.headers().get("X-Test"));
        assertEquals(List.of("值"), request.queryParameters().get("名称"));
        assertEquals(Duration.ofSeconds(2), request.timeout().orElseThrow());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get("ftp://example.com/file").build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get("http:///path").build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get("http://exa mple.com/path").build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get(" ").build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get(null).build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get("https://example.com").header(" ", "值"));
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.get("https://example.com")
                .body(new JsonRequestBody(Map.of()))
                .build());
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpRequestData.delete("https://example.com")
                .timeout(Duration.ZERO)
                .build());
    }

    @Test
    void requestBodiesValidateFieldsFilesAndContentTypes() throws Exception {
        Path file = temporaryDirectory.resolve("数据.bin");
        java.nio.file.Files.write(file, new byte[] {1, 2, 3});

        FormRequestBody form = FormRequestBody.of(Map.of("名称", "值"));
        MultipartFilePart filePart = MultipartFilePart.of("文件", file, "application/octet-stream");
        MultipartRequestBody multipart = new MultipartRequestBody(
            Map.of("说明", List.of("文本")),
            List.of(filePart));
        BinaryRequestBody binary = new BinaryRequestBody(file, "application/octet-stream");

        assertEquals(List.of("值"), form.fields().get("名称"));
        assertEquals("数据.bin", multipart.fileParts().getFirst().fileName());
        assertEquals(file, binary.path());
        assertThrows(NullPointerException.class, () -> new JsonRequestBody(null));
        assertThrows(IllegalArgumentException.class, () -> FormRequestBody.of(Map.of(" ", "值")));
        assertThrows(
            IllegalArgumentException.class,
            () -> new MultipartRequestBody(Map.of(), List.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new BinaryRequestBody(temporaryDirectory.resolve("不存在.bin"), "application/octet-stream"));
        assertThrows(IllegalArgumentException.class, () -> new BinaryRequestBody(file, "错误媒体类型"));
    }

    /**
     * 默认 JSON 映射器测试对象。
     *
     * @param name 名称
     * @param occurredAt 发生时间
     */
    private record JsonPayload(String name, LocalDateTime occurredAt) {
    }
}
