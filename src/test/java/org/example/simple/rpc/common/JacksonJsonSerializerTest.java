package org.example.simple.rpc.common;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.IntNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Jackson JSON 序列化器测试。
 */
class JacksonJsonSerializerTest {

    private final JacksonJsonSerializer serializer = new JacksonJsonSerializer();

    @Test
    void requestMessageCompletesSerializationRoundTrip() {
        RpcRequest request = new RpcRequest(
            "请求-1",
            "计算服务",
            "求和",
            List.of(Integer.class.getName()),
            List.of(IntNode.valueOf(3)));

        RpcRequest result = serializer.deserialize(serializer.serialize(request), RpcRequest.class);

        assertEquals(request, result);
    }

    @Test
    void malformedJsonIsConvertedToRpcException() {
        RpcException exception = assertThrows(
            RpcException.class,
            () -> serializer.deserialize("{错误".getBytes(), RpcRequest.class));

        assertEquals(RpcErrorCode.SERIALIZATION_FAILED, exception.getErrorCode());
    }

    @Test
    void unknownPropertiesAreIgnored() {
        SamplePayload result = serializer.deserialize(
            "{\"name\":\"测试\",\"unknown\":\"忽略\"}".getBytes(),
            SamplePayload.class);

        assertEquals("测试", result.name());
    }

    @Test
    void dateUsesConfiguredDateTimePattern() throws ParseException {
        SimpleDateFormat dateFormat = new SimpleDateFormat(JacksonJsonSerializer.DATE_TIME_PATTERN);
        dateFormat.setLenient(false);
        String expected = "2026-08-25 14:30:45";

        byte[] bytes = serializer.serialize(dateFormat.parse(expected));

        assertEquals('"' + expected + '"', new String(bytes));
        assertEquals(expected, dateFormat.format(serializer.deserialize(bytes, java.util.Date.class)));
    }

    @Test
    void localDateTimeUsesConfiguredDateTimePattern() {
        LocalDateTime expected = LocalDateTime.of(2026, 8, 25, 14, 30, 45);

        byte[] bytes = serializer.serialize(expected);

        assertEquals("\"2026-08-25 14:30:45\"", new String(bytes));
        assertEquals(expected, serializer.deserialize(bytes, LocalDateTime.class));
    }

    @Test
    void invalidDateTimeFormatIsRejected() {
        RpcException exception = assertThrows(
            RpcException.class,
            () -> serializer.deserialize("\"2026/08/25 14:30:45\"".getBytes(), LocalDateTime.class));

        assertEquals(RpcErrorCode.SERIALIZATION_FAILED, exception.getErrorCode());
    }

    @Test
    void providedMapperUsesSameSerializationRules() {
        JacksonJsonSerializer configuredSerializer = new JacksonJsonSerializer(new ObjectMapper());

        SamplePayload result = configuredSerializer.deserialize(
            "{\"name\":\"测试\",\"unknown\":true}".getBytes(),
            SamplePayload.class);

        assertEquals("测试", result.name());
    }

    /**
     * 用于验证未知字段兼容性的测试载荷。
     *
     * @param name 可识别字段
     */
    private record SamplePayload(String name) {
    }
}
