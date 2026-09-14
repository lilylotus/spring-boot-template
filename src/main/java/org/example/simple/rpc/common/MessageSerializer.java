package org.example.simple.rpc.common;

import java.lang.reflect.Type;

/** 按本地声明类型编码，禁止由远端类型元数据加载类。 */
public interface MessageSerializer {
    byte id();
    default void validateType(Type type) { java.util.Objects.requireNonNull(type, "目标类型不能为空"); }
    byte[] serialize(Object value, Type type);
    Object deserialize(byte[] bytes, Type type);
    default byte[] serialize(Object value) {
        return serialize(value, value == null ? String.class : value.getClass());
    }
    default <T> T deserialize(byte[] bytes, Class<T> type) {
        @SuppressWarnings("unchecked") T result = (T) deserialize(bytes, (Type) type);
        return result;
    }
}
