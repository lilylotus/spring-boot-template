package com.example.template.rpc.server;

import com.example.template.rpc.RpcException;

import java.util.Map;

/**
 * 服务端反射调用前需要的类型辅助方法：把 {@code RpcRequest#parameterTypes} 里的类型名(可能是
 * 基本类型关键字，如 {@code int})还原成 {@link Class}，以及把 Jackson 反序列化出来的参数值
 * 收窄转换成目标方法声明的具体数值类型。
 * <p>
 * 需要收窄转换的原因：{@link com.example.template.rpc.protocol.JacksonRpcSerializer} 对
 * {@code String}/{@code Integer}/{@code Boolean}/{@code Double} 等 JDK 内置 final 类型不会写入
 * 类型信息(JSON 字面量类型本身已无歧义)，所以一个原本是 {@code long} 的参数，反序列化到静态类型为
 * {@code Object} 的数组元素里时可能变成 {@code Integer}，需要在反射调用前按目标参数类型转换回来，
 * 否则 {@code Method#invoke} 会直接抛 {@code IllegalArgumentException}。
 */
final class RpcTypeUtils {

    /** JDK 基本类型关键字到 {@link Class} 的映射，{@link Class#forName} 不能直接解析这些名字。 */
    private static final Map<String, Class<?>> PRIMITIVE_TYPES = Map.of(
        "int", int.class,
        "long", long.class,
        "short", short.class,
        "byte", byte.class,
        "char", char.class,
        "boolean", boolean.class,
        "float", float.class,
        "double", double.class,
        "void", void.class);

    private RpcTypeUtils() {
    }

    /**
     * 把类型全限定名(或基本类型关键字)还原成 {@link Class}。
     *
     * @param typeName 类型名
     * @return 对应的 {@link Class}
     */
    static Class<?> resolveClass(String typeName) {
        Class<?> primitive = PRIMITIVE_TYPES.get(typeName);
        if (primitive != null) {
            return primitive;
        }
        try {
            return Class.forName(typeName);
        } catch (ClassNotFoundException e) {
            throw new RpcException("无法解析参数类型: " + typeName, e);
        }
    }

    /**
     * 把参数值收窄/拆箱转换为目标参数类型期望的具体数值类型；非数值类型原样返回。
     *
     * @param targetType 方法声明的参数类型
     * @param value      反序列化得到的原始参数值
     * @return 转换后的参数值
     */
    static Object coerce(Class<?> targetType, Object value) {
        if (value == null || !(value instanceof Number number)) {
            return value;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return number.intValue();
        }
        if (targetType == long.class || targetType == Long.class) {
            return number.longValue();
        }
        if (targetType == short.class || targetType == Short.class) {
            return number.shortValue();
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return number.byteValue();
        }
        if (targetType == float.class || targetType == Float.class) {
            return number.floatValue();
        }
        if (targetType == double.class || targetType == Double.class) {
            return number.doubleValue();
        }
        return value;
    }

}
