package org.example.simple.rpc.common;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 使用 Jackson 3 的 JSON 消息序列化器。
 */
public final class JacksonJsonSerializer implements MessageSerializer {

    /** RPC 日期时间文本格式。 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final ObjectMapper objectMapper;

    /**
     * 使用默认且未开启多态类型的映射器创建序列化器。
     */
    public JacksonJsonSerializer() {
        this(new ObjectMapper());
    }

    /**
     * 使用调用方提供的映射器创建序列化器。
     *
     * @param objectMapper 可复用的 Jackson 映射器
     */
    public JacksonJsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = configure(Objects.requireNonNull(objectMapper, "Jackson 映射器不能为空"));
    }

    @Override
    public byte[] serialize(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (RuntimeException exception) {
            throw serializationFailure("JSON 序列化失败", exception);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        Objects.requireNonNull(bytes, "消息字节不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");
        try {
            return objectMapper.readValue(bytes, type);
        } catch (RuntimeException exception) {
            throw serializationFailure("JSON 反序列化失败", exception);
        }
    }

    @Override
    public JsonNode toTree(Object value) {
        try {
            return objectMapper.valueToTree(value);
        } catch (RuntimeException exception) {
            throw serializationFailure("参数转换为 JSON 失败", exception);
        }
    }

    @Override
    public <T> T fromTree(JsonNode node, Class<T> type) {
        Objects.requireNonNull(type, "目标类型不能为空");
        try {
            return objectMapper.treeToValue(node, type);
        } catch (RuntimeException exception) {
            throw serializationFailure("JSON 参数类型转换失败", exception);
        }
    }

    private RpcException serializationFailure(String message, RuntimeException cause) {
        if (cause instanceof RpcException rpcException) {
            return rpcException;
        }
        return new RpcException(RpcErrorCode.SERIALIZATION_FAILED, message, cause);
    }

    private static ObjectMapper configure(ObjectMapper sourceMapper) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_TIME_PATTERN);
        dateFormat.setLenient(false);
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        SimpleModule dateTimeModule = new SimpleModule("RPC 日期时间格式模块")
            .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter))
            .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter));

        return sourceMapper.rebuild()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .defaultDateFormat(dateFormat)
            .addModule(dateTimeModule)
            .build();
    }
}
