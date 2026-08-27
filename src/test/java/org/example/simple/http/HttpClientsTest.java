package org.example.simple.http;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 客户端请求、响应、编码和超时集成测试。
 */
class HttpClientsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;
    private String baseUrl;
    private final Set<Integer> remotePorts = ConcurrentHashMap.newKeySet();
    private CountDownLatch blockingRequestStarted;
    private CountDownLatch releaseBlockingRequest;

    @BeforeEach
    void startServer() throws IOException {
        remotePorts.clear();
        blockingRequestStarted = new CountDownLatch(1);
        releaseBlockingRequest = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", this::echo);
        server.createContext("/status", exchange -> respond(exchange, 422, "处理失败"));
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/json", exchange -> respond(exchange, 200, "[{\"name\":\"测试\"}]"));
        server.createContext("/invalid-json", exchange -> respond(exchange, 200, "{错误"));
        server.createContext("/delay", exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "完成");
        });
        server.createContext("/blocking", exchange -> {
            blockingRequestStarted.countDown();
            try {
                releaseBlockingRequest.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "解除阻塞");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        HttpClients.configure(HttpClientConfig.defaults());
    }

    @AfterEach
    void closeResources() {
        HttpClients.shutdown();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsSupportedMethodsHeadersAndEncodedQueryParameters() {
        for (HttpMethod method : HttpMethod.values()) {
            HttpRequestData.Builder builder = switch (method) {
                case GET -> HttpRequestData.get(baseUrl + "/echo?existing=1");
                case POST -> HttpRequestData.post(baseUrl + "/echo?existing=1");
                case PUT -> HttpRequestData.put(baseUrl + "/echo?existing=1");
                case DELETE -> HttpRequestData.delete(baseUrl + "/echo?existing=1");
            };
            HttpResponseData response = HttpClients.execute(builder
                .header("X-Test", "header-value")
                .queryParameter("名称", "中文 值")
                .build());

            String text = response.bodyAsString();
            assertTrue(text.contains("\"method\":\"" + method + "\""));
            assertTrue(text.contains("existing=1"));
            assertTrue(text.contains("%E5%90%8D%E7%A7%B0"));
            assertTrue(text.contains("%E4%B8%AD%E6%96%87%20%E5%80%BC"));
            assertTrue(text.contains("header-value"));
        }
        assertEquals(1, remotePorts.size());
    }

    @Test
    void convenienceMethodsAreStaticAndSendRequestsWithoutClientInstance() throws Exception {
        assertTrue(Modifier.isPrivate(HttpClients.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isStatic(HttpClients.class.getMethod("get", String.class).getModifiers()));
        assertTrue(Modifier.isStatic(
            HttpClients.class.getMethod("post", String.class, HttpRequestBody.class).getModifiers()));
        assertTrue(Modifier.isStatic(
            HttpClients.class.getMethod("put", String.class, HttpRequestBody.class).getModifiers()));
        assertTrue(Modifier.isStatic(HttpClients.class.getMethod("delete", String.class).getModifiers()));

        assertTrue(HttpClients.get(baseUrl + "/echo").bodyAsString().contains("\"method\":\"GET\""));
        assertTrue(HttpClients.post(
            baseUrl + "/echo",
            new JsonRequestBody(Map.of("value", 1))).bodyAsString().contains("\"method\":\"POST\""));
        assertTrue(HttpClients.put(
            baseUrl + "/echo",
            new JsonRequestBody(Map.of("value", 1))).bodyAsString().contains("\"method\":\"PUT\""));
        assertTrue(HttpClients.delete(baseUrl + "/echo").bodyAsString().contains("\"method\":\"DELETE\""));
    }

    @Test
    void sendsJsonAndUrlEncodedFormBodies() {
        HttpResponseData jsonResponse = HttpClients.post(
            baseUrl + "/echo",
            new JsonRequestBody(Map.of("name", "测试")));
        HttpResponseData formResponse = HttpClients.post(
            baseUrl + "/echo",
            FormRequestBody.of(Map.of("名称", "中文 值")));

        assertTrue(jsonResponse.bodyAsString().contains("application/json"));
        assertTrue(jsonResponse.bodyAsString().contains("{\\\"name\\\":\\\"测试\\\"}"));
        assertTrue(formResponse.bodyAsString().contains("application/x-www-form-urlencoded"));
        assertTrue(formResponse.bodyAsString().contains("%E5%90%8D%E7%A7%B0=%E4%B8%AD%E6%96%87+%E5%80%BC"));
    }

    @Test
    void sendsMultipartTextMultipleFilesAndBinaryFile() throws Exception {
        Path firstFile = temporaryDirectory.resolve("甲.txt");
        Path secondFile = temporaryDirectory.resolve("乙.bin");
        java.nio.file.Files.writeString(firstFile, "文件甲", StandardCharsets.UTF_8);
        java.nio.file.Files.write(secondFile, new byte[] {0, 1, 2, 3});
        MultipartRequestBody multipart = new MultipartRequestBody(
            Map.of("description", List.of("中文文本")),
            List.of(
                MultipartFilePart.of("firstFile", firstFile, "text/plain; charset=UTF-8"),
                MultipartFilePart.of("secondFile", secondFile, "application/octet-stream")));

        HttpResponseData multipartResponse = HttpClients.post(baseUrl + "/echo", multipart);
        HttpResponseData binaryResponse = HttpClients.put(
            baseUrl + "/echo",
            new BinaryRequestBody(secondFile, "application/octet-stream"));

        String multipartText = multipartResponse.bodyAsString();
        assertTrue(multipartText.contains("multipart/form-data; charset=ISO-8859-1; boundary="));
        assertTrue(multipartText.contains("name=\\\"description\\\""));
        assertTrue(multipartText.contains("中文文本"));
        assertTrue(multipartText.contains("filename*=\\\"UTF-8''%E7%94%B2.txt\\\""));
        assertTrue(multipartText.contains("filename*=\\\"UTF-8''%E4%B9%99.bin\\\""));
        assertTrue(multipartText.contains("文件甲"));
        EchoResponse binaryEcho = objectMapper.readValue(binaryResponse.body(), EchoResponse.class);
        assertArrayEquals(new byte[] {0, 1, 2, 3}, binaryEcho.rawBodyBytes());
    }

    @Test
    void returnsRawSuccessAndMapsNonSuccessResponse() {
        HttpResponseData empty = HttpClients.get(baseUrl + "/empty");
        HttpClientException exception = assertThrows(
            HttpClientException.class,
            () -> HttpClients.get(baseUrl + "/status"));

        assertEquals(204, empty.statusCode());
        assertArrayEquals(new byte[0], empty.body());
        assertEquals(HttpErrorType.HTTP_STATUS, exception.getErrorType());
        HttpResponseData errorResponse = exception.getResponse().orElseThrow();
        assertEquals(422, errorResponse.statusCode());
        assertEquals("处理失败", errorResponse.bodyAsString());
    }

    @Test
    void convertsJsonToOrdinaryAndGenericTypes() {
        HttpRequestData request = HttpRequestData.get(baseUrl + "/json").build();

        SamplePayload[] array = HttpClients.executeJson(request, SamplePayload[].class);
        List<SamplePayload> list = HttpClients.executeJson(request, new TypeReference<List<SamplePayload>>() {
        });
        HttpClientException exception = assertThrows(
            HttpClientException.class,
            () -> HttpClients.executeJson(
                HttpRequestData.get(baseUrl + "/invalid-json").build(),
                SamplePayload.class));

        assertEquals("测试", array[0].name());
        assertEquals("测试", list.getFirst().name());
        assertEquals(HttpErrorType.JSON_CONVERSION, exception.getErrorType());
        assertTrue(exception.getCause() instanceof RuntimeException);
    }

    @Test
    void requestTimeoutOverridesDefaultWithoutChangingFollowingRequests() {
        HttpClients.configure(HttpClientConfig.builder()
            .connectTimeout(Duration.ofSeconds(2))
            .requestTimeout(Duration.ofMillis(100))
            .build());
        HttpRequestData defaultRequest = HttpRequestData.get(baseUrl + "/delay").build();
        HttpRequestData overrideRequest = HttpRequestData.get(baseUrl + "/delay")
            .timeout(Duration.ofSeconds(1))
            .build();

        HttpClientException firstTimeout = assertThrows(
            HttpClientException.class,
            () -> HttpClients.execute(defaultRequest));
        assertEquals("完成", HttpClients.execute(overrideRequest).bodyAsString());
        HttpClientException nextTimeout = assertThrows(
            HttpClientException.class,
            () -> HttpClients.execute(defaultRequest));

        assertEquals(HttpErrorType.TIMEOUT, firstTimeout.getErrorType());
        assertEquals(HttpErrorType.TIMEOUT, nextTimeout.getErrorType());
    }

    @Test
    void shutdownIsIdempotentAndConfigureRestoresRequests() {
        HttpClients.shutdown();

        assertDoesNotThrow(HttpClients::shutdown);
        HttpClientException exception = assertThrows(
            HttpClientException.class,
            () -> HttpClients.get(baseUrl + "/echo"));
        assertEquals(HttpErrorType.CLIENT_CLOSED, exception.getErrorType());

        HttpClients.configure(HttpClientConfig.defaults());
        assertEquals(200, HttpClients.get(baseUrl + "/echo").statusCode());
    }

    @Test
    void invalidGlobalConfigurationDoesNotReplaceCurrentRuntime() {
        assertThrows(NullPointerException.class, () -> HttpClients.configure(null));
        assertThrows(
            IllegalArgumentException.class,
            () -> HttpClients.configure(HttpClientConfig.builder()
                .requestTimeout(Duration.ZERO)
                .build()));

        assertEquals(200, HttpClients.get(baseUrl + "/echo").statusCode());
    }

    @Test
    void reconfigurationWaitsForActiveRequestBeforeReplacingRuntime() throws Exception {
        CompletableFuture<HttpResponseData> request = CompletableFuture.supplyAsync(
            () -> HttpClients.get(baseUrl + "/blocking"));
        assertTrue(blockingRequestStarted.await(2, TimeUnit.SECONDS));

        CompletableFuture<Void> reconfiguration = CompletableFuture.runAsync(
            () -> HttpClients.configure(HttpClientConfig.builder()
                .connectTimeout(Duration.ofSeconds(1))
                .requestTimeout(Duration.ofSeconds(2))
                .build()));

        assertThrows(TimeoutException.class, () -> reconfiguration.get(100, TimeUnit.MILLISECONDS));
        assertFalse(reconfiguration.isDone());
        releaseBlockingRequest.countDown();

        assertEquals("解除阻塞", request.get(2, TimeUnit.SECONDS).bodyAsString());
        reconfiguration.get(2, TimeUnit.SECONDS);
        assertEquals(200, HttpClients.get(baseUrl + "/echo").statusCode());
    }

    private void echo(HttpExchange exchange) throws IOException {
        remotePorts.add(exchange.getRemoteAddress().getPort());
        byte[] requestBody = exchange.getRequestBody().readAllBytes();
        EchoResponse response = new EchoResponse(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawQuery(),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            exchange.getRequestHeaders().getFirst("X-Test"),
            new String(requestBody, StandardCharsets.UTF_8),
            requestBody);
        respond(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 本地回显服务响应。
     *
     * @param method HTTP 方法
     * @param rawQuery 未解码查询字符串
     * @param contentType 请求媒体类型
     * @param testHeader 测试请求头
     * @param body UTF-8 请求体
     * @param rawBodyBytes 原始请求体字节
     */
    private record EchoResponse(
        String method,
        String rawQuery,
        String contentType,
        String testHeader,
        String body,
        byte[] rawBodyBytes) {
    }

    /**
     * JSON 转换测试对象。
     *
     * @param name 名称
     */
    private record SamplePayload(String name) {
    }
}
