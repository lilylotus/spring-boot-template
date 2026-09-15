package com.example.template.rpc.protocol;

import com.example.template.rpc.RpcSerializationException;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;

/**
 * 基于 Jackson 的默认序列化实现，消息体统一序列化为 UTF-8 编码的 JSON 文本字节。
 * <p>
 * {@code RpcRequest#parameters}/{@code RpcResponse#result} 的静态类型都是 {@code Object}，
 * 不启用多态类型信息的话，Jackson 反序列化时会把自定义 DTO 还原成 {@code LinkedHashMap} 而不是
 * 原始类型；这里的处理方式与 {@code RedisConfig} 完全一致：只允许 {@code com.example}/
 * {@code java.util} 包路径下的类型参与多态反序列化，避免不受限制的反序列化带来的安全风险。
 * 需要注意的是 {@code String}/{@code Integer}/{@code Boolean}/{@code Double} 等 JDK 内置的
 * final 类型不会被打上类型标记（JSON 自身的字面量类型已经无歧义），服务端在反射调用目标方法前
 * 需要对数值类型做一次收窄转换，见 {@code RpcServerHandler}。
 */
public class JacksonRpcSerializer implements RpcSerializer {

    /** 本实现在协议帧“序列化方式”字段里的编码，客户端/服务端默认都用这个编码注册。 */
    public static final byte TYPE_CODE = 1;

    private final ObjectMapper objectMapper = buildObjectMapper();

    @Override
    public byte[] serialize(Object object) {
        try {
            return objectMapper.writeValueAsBytes(object);
        } catch (Exception e) {
            throw new RpcSerializationException("消息体序列化失败: " + object, e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> clazz) {
        try {
            return objectMapper.readValue(new String(bytes, StandardCharsets.UTF_8), clazz);
        } catch (Exception e) {
            throw new RpcSerializationException("消息体反序列化失败，目标类型: " + clazz, e);
        }
    }

    @Override
    public byte type() {
        return TYPE_CODE;
    }

    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // 支持所有字段(包括private)的序列化，RpcRequest/RpcResponse等模型类不强制要求getter/setter
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // 反序列化时忽略未知字段，避免协议双方版本不完全一致时直接反序列化失败
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // 支持Java 8时间类型(LocalDateTime/LocalDate等)
        mapper.registerModule(new JavaTimeModule());

        // 启用多态类型信息，保证Object[]参数/Object返回值能还原出原始具体类型，
        // 用PolymorphicTypeValidator把允许反序列化的包路径收紧到本项目自己的包和java.util集合类型；
        // RpcRequest#parameters声明类型是Object[]，Jackson对数组类型属性即使标了final也会按运行时
        // 具体数组类(如"[Ljava.lang.Object;")单独打类型标记，与包名前缀匹配规则是两套逻辑，
        // 需要额外显式放行Object[]这个具体的数组类，否则参数为空数组之外的请求全部反序列化失败
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("com.example")
            .allowIfSubType("java.util")
            .allowIfSubType(Object[].class)
            .build();
        mapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL);

        return mapper;
    }

}
