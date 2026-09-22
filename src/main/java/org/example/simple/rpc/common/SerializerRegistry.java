package org.example.simple.rpc.common;

import java.util.HashMap;
import java.util.Map;

/** 启动后不可变的编码注册表。 */
public final class SerializerRegistry {
    /** 序列化器标识到实现的映射，构造完成后不可变，因此可无锁并发读取。 */
    private final Map<Byte, MessageSerializer> serializers;

    /**
     * 用给定的序列化器构建注册表。
     *
     * @param values 参与注册的序列化器，标识必须为正且互不重复
     * @throws IllegalArgumentException 当序列化器标识非正或重复时抛出
     */
    public SerializerRegistry(MessageSerializer... values) {
        Map<Byte, MessageSerializer> entries = new HashMap<>();
        for (MessageSerializer value : values) {
            if (value.id() <= 0 || entries.putIfAbsent(value.id(), value) != null) {
                throw new IllegalArgumentException("序列化编号无效或重复");
            }
        }
        serializers = Map.copyOf(entries);
    }

    /**
     * 构建默认注册表，包含 Jackson JSON 与 Protostuff 两种序列化器。
     *
     * @return 默认序列化器注册表
     */
    public static SerializerRegistry defaults() {
        return new SerializerRegistry(new JacksonJsonSerializer(), new ProtostuffSerializer());
    }

    /**
     * 按协议头中的标识查找序列化器。
     *
     * @param id 序列化器标识
     * @return 对应的序列化器实现
     * @throws IllegalArgumentException 当标识未注册时抛出，用于拒绝未启用的编码方式
     */
    public MessageSerializer get(byte id) {
        MessageSerializer serializer = serializers.get(id);
        if (serializer == null) {
            throw new IllegalArgumentException("未启用的序列化编号: " + id);
        }
        return serializer;
    }
}
