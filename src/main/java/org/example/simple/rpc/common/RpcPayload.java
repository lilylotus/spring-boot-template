package org.example.simple.rpc.common;

/**
 * 参数或结果的显式空值与编码字节。
 *
 * <p>把"值为 null"与"值序列化后为空字节"区分开，避免远端无法判断参数是否显式传入 null。
 *
 * @param isNull 值是否为显式 null
 * @param data 序列化后的字节；{@code isNull} 为真时必须为空数组
 */
public record RpcPayload(boolean isNull, byte[] data) {
    /**
     * 校验空值标记与字节内容一致，并复制入参数组避免外部持有可变引用。
     *
     * @throws IllegalArgumentException 当字节为 {@code null} 或空值标记与内容矛盾时抛出
     */
    public RpcPayload {
        if (data == null || (isNull && data.length != 0)) {
            throw new IllegalArgumentException("载荷空值标记不合法");
        }
        data = data.clone();
    }

    /**
     * 返回载荷字节的副本，保证记录本身不可变。
     *
     * @return 编码字节的副本
     */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /**
     * 按本地声明类型把值编码为载荷。
     *
     * @param value 待编码的值，可为 {@code null}
     * @param type 本地声明的值类型
     * @param serializer 使用的序列化器
     * @return 编码后的载荷；值为 {@code null} 时返回空值载荷
     */
    public static RpcPayload of(
            Object value, java.lang.reflect.Type type, MessageSerializer serializer) {
        return value == null
                ? new RpcPayload(true, new byte[0])
                : new RpcPayload(false, serializer.serialize(value, type));
    }

    /**
     * 按本地声明类型把载荷解码为值。
     *
     * @param type 本地声明的目标类型
     * @param serializer 使用的序列化器
     * @return 解码后的值；空值载荷返回 {@code null}
     */
    public Object decode(java.lang.reflect.Type type, MessageSerializer serializer) {
        return isNull ? null : serializer.deserialize(data, type);
    }
}
