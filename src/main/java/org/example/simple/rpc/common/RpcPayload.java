package org.example.simple.rpc.common;

/** 参数或结果的显式空值与编码字节。 */
public record RpcPayload(boolean isNull, byte[] data) {
    public RpcPayload {
        if (data == null || (isNull && data.length != 0)) {
            throw new IllegalArgumentException("载荷空值标记不合法");
        }
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    public static RpcPayload of(
            Object value, java.lang.reflect.Type type, MessageSerializer serializer) {
        return value == null
                ? new RpcPayload(true, new byte[0])
                : new RpcPayload(false, serializer.serialize(value, type));
    }

    public Object decode(java.lang.reflect.Type type, MessageSerializer serializer) {
        return isNull ? null : serializer.deserialize(data, type);
    }
}
