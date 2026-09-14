package org.example.simple.rpc.common;

import java.util.Map;
import java.util.HashMap;

/** 启动后不可变的编码注册表。 */
public final class SerializerRegistry {
    private final Map<Byte, MessageSerializer> serializers;
    public SerializerRegistry(MessageSerializer... values) {
        Map<Byte, MessageSerializer> entries = new HashMap<>();
        for (MessageSerializer value : values) {
            if (value.id() <= 0 || entries.putIfAbsent(value.id(), value) != null) {
                throw new IllegalArgumentException("序列化编号无效或重复");
            }
        }
        serializers = Map.copyOf(entries);
    }
    public static SerializerRegistry defaults() {
        return new SerializerRegistry(new JacksonJsonSerializer(), new ProtostuffSerializer());
    }
    public MessageSerializer get(byte id) {
        MessageSerializer serializer = serializers.get(id);
        if (serializer == null) { throw new IllegalArgumentException("未启用的序列化编号: " + id); }
        return serializer;
    }
}
