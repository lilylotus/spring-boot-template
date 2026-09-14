package org.example.simple.rpc.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RpcResponseTest {
    @Test void bothSerializersPreserveNullAndEmptyValues() {
        for (MessageSerializer serializer : new MessageSerializer[]{new JacksonJsonSerializer(), new ProtostuffSerializer()}) {
            for (String value : new String[]{null, "", "中文结果"}) {
                RpcResponse response = RpcResponse.success(RpcPayload.of(value, String.class, serializer));
                RpcResponse decoded = serializer.deserialize(serializer.serialize(response), RpcResponse.class);
                assertEquals(value, decoded.result().decode(String.class, serializer));
            }
            RpcResponse failure = RpcResponse.failure(RpcErrorCode.SERVER_BUSY, "繁忙");
            assertEquals(failure, serializer.deserialize(serializer.serialize(failure), RpcResponse.class));
        }
    }
    @Test void rejectsInconsistentResponse() {
        assertThrows(IllegalArgumentException.class, () -> new RpcResponse(true, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RpcResponse(false, null, null));
        assertThrows(IllegalArgumentException.class, () -> new RpcPayload(true, new byte[]{1}));
    }
}
