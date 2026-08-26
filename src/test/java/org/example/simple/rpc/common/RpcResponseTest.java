package org.example.simple.rpc.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RPC 响应字符串结果约束测试。
 */
class RpcResponseTest {

    private final JacksonJsonSerializer serializer = new JacksonJsonSerializer();

    @Test
    void jsonStringResultSurvivesResponseRoundTrip() {
        String resultJson = "{\"name\":\"测试\"}";
        RpcResponse response = RpcResponse.success("请求-结果", resultJson);

        RpcResponse decoded = serializer.deserialize(serializer.serialize(response), RpcResponse.class);

        assertEquals(resultJson, decoded.result());
    }

    @Test
    void successfulNullValueUsesJsonNullText() {
        RpcResponse response = RpcResponse.success("请求-空值", "null");

        assertEquals("null", response.result());
        assertNull(response.error());
    }

    @Test
    void successfulResponseRejectsJavaNullResult() {
        assertThrows(NullPointerException.class, () -> RpcResponse.success("请求-非法空值", null));
    }
}
