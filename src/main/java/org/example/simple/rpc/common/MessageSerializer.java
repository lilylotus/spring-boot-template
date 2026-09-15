package org.example.simple.rpc.common;

import java.lang.reflect.Type;

/** 按本地声明类型编码，禁止由远端类型元数据加载类。 */
public interface MessageSerializer {
    /**
     * 返回写入协议头的序列化器标识。
     *
     * @return 注册表中唯一的序列化器标识
     */
    byte id();

    /**
     * 校验本地调用允许使用的目标类型。
     *
     * @param type 本地声明的目标类型
     */
    default void validateType(Type type) {
        java.util.Objects.requireNonNull(type, "目标类型不能为空");
    }

    /**
     * 按指定类型编码值。
     *
     * @param value 待编码的值
     * @param type 本地声明的值类型
     * @return 编码后的字节
     */
    byte[] serialize(Object value, Type type);

    /**
     * 按指定类型解码字节。
     *
     * @param bytes 编码字节
     * @param type 本地声明的目标类型
     * @return 解码后的值
     */
    Object deserialize(byte[] bytes, Type type);

    /**
     * 使用运行时类型编码非空值。
     *
     * @param value 待编码的值
     * @return 编码后的字节
     */
    default byte[] serialize(Object value) {
        return serialize(value, value == null ? String.class : value.getClass());
    }

    /**
     * 按指定 Class 解码字节。
     *
     * @param bytes 编码字节
     * @param type 本地声明的目标类型
     * @param <T> 目标类型
     * @return 解码后的值
     */
    default <T> T deserialize(byte[] bytes, Class<T> type) {
        @SuppressWarnings("unchecked")
        T result = (T) deserialize(bytes, (Type) type);
        return result;
    }
}
