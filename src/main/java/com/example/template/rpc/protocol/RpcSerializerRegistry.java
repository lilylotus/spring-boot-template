package com.example.template.rpc.protocol;

import com.example.template.rpc.RpcException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 序列化方式编码 -> {@link RpcSerializer} 实现的注册表，编解码器按协议帧里的编码从这里查找实际
 * 使用哪个序列化实现，从而实现“序列化方式可插拔”——替换/新增实现只需要 {@link #register}，
 * 不需要改动编解码器代码。默认注册了 {@link JacksonRpcSerializer}。
 */
public class RpcSerializerRegistry {

    private final Map<Byte, RpcSerializer> serializers = new ConcurrentHashMap<>();

    /**
     * 创建注册表并注册默认的 Jackson 实现。
     */
    public RpcSerializerRegistry() {
        register(new JacksonRpcSerializer());
    }

    /**
     * 注册一个序列化实现，按其 {@link RpcSerializer#type()} 编码作为查找键。
     *
     * @param serializer 待注册的序列化实现
     */
    public final void register(RpcSerializer serializer) {
        serializers.put(serializer.type(), serializer);
    }

    /**
     * 按编码查找序列化实现，找不到说明收发双方序列化方式配置不一致或协议帧已损坏。
     *
     * @param type 序列化方式编码
     * @return 对应的序列化实现
     */
    public RpcSerializer get(byte type) {
        RpcSerializer serializer = serializers.get(type);
        if (serializer == null) {
            throw new RpcException("未注册的序列化方式编码: " + type);
        }
        return serializer;
    }

}
