package com.example.template.rpc.protocol;

/**
 * 可插拔的消息体序列化接口，协议帧里的“序列化方式”字段就是 {@link #type()} 返回的编码。
 * 默认只提供 {@link JacksonRpcSerializer} 一种实现，如需替换为 Kryo/Protostuff 等更高性能的
 * 二进制序列化方案，实现本接口并注册到 {@link RpcSerializerRegistry} 即可，无需改动协议编解码器
 * 或客户端/服务端主体逻辑。
 */
public interface RpcSerializer {

    /**
     * 把对象序列化为字节数组。
     *
     * @param object 待序列化对象
     * @return 序列化后的字节
     */
    byte[] serialize(Object object);

    /**
     * 把字节数组反序列化为指定类型的对象。
     *
     * @param bytes 待反序列化的字节
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 反序列化后的对象
     */
    <T> T deserialize(byte[] bytes, Class<T> clazz);

    /**
     * 该序列化实现在协议帧“序列化方式”字段里对应的编码，同一个 {@link RpcSerializerRegistry}
     * 内不允许有两个实现使用相同编码。
     *
     * @return 序列化方式编码
     */
    byte type();

}
