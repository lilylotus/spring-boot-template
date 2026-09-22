package org.example.simple.rpc.common;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** 使用 Jackson 3 的 JSON 消息序列化器。 */
public final class JacksonJsonSerializer implements MessageSerializer {

    /** RPC 日期时间文本格式。 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 已完成 RPC 定制配置的 Jackson 映射器，线程安全，可被并发编解码复用。 */
    private final ObjectMapper objectMapper;

    /** 使用默认且未开启多态类型的映射器创建序列化器。 */
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

    /**
     * 返回写入协议头的序列化器标识。
     *
     * @return 固定为 1，代表 JSON 编码
     */
    @Override
    public byte id() {
        return 1;
    }

    /**
     * 把值编码为 JSON 字节。
     *
     * @param value 待编码的值
     * @param type 本地声明的值类型，JSON 编码按运行时结构输出，此参数仅用于接口对齐
     * @return 编码后的字节
     * @throws RpcException 编码失败时抛出，错误类型为 {@link RpcErrorCode#SERIALIZATION_FAILED}
     */
    @Override
    public byte[] serialize(Object value, java.lang.reflect.Type type) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (RuntimeException exception) {
            throw serializationFailure("JSON 序列化失败", exception);
        }
    }

    /**
     * 按本地声明类型把 JSON 字节解码为对象。
     *
     * <p>解码前先做结构校验，限制嵌套深度与集合元素数量，避免恶意报文导致解析放大。
     *
     * @param bytes JSON 字节
     * @param type 本地声明的目标类型
     * @return 解码后的对象
     * @throws RpcException 解码或结构校验失败时抛出，错误类型为 {@link RpcErrorCode#SERIALIZATION_FAILED}
     */
    @Override
    public Object deserialize(byte[] bytes, java.lang.reflect.Type type) {
        Objects.requireNonNull(bytes, "消息字节不能为空");
        Objects.requireNonNull(type, "目标类型不能为空");
        try {
            validateInput(bytes);
            return objectMapper.readValue(bytes, objectMapper.constructType(type));
        } catch (RuntimeException exception) {
            throw serializationFailure("JSON 反序列化失败", exception);
        }
    }

    /**
     * 把对象转换为 JSON 树节点，便于在不确定目标类型时先保留原始结构。
     *
     * @param value 待转换的对象
     * @return 对应的 JSON 树节点
     * @throws RpcException 转换失败时抛出
     */
    public JsonNode toTree(Object value) {
        try {
            return objectMapper.valueToTree(value);
        } catch (RuntimeException exception) {
            throw serializationFailure("参数转换为 JSON 失败", exception);
        }
    }

    /**
     * 把 JSON 树节点转换为指定类型的对象。
     *
     * @param node JSON 树节点
     * @param type 目标类型
     * @param <T> 目标类型
     * @return 转换后的对象
     * @throws RpcException 转换失败时抛出
     */
    public <T> T fromTree(JsonNode node, Class<T> type) {
        Objects.requireNonNull(type, "目标类型不能为空");
        try {
            return objectMapper.treeToValue(node, type);
        } catch (RuntimeException exception) {
            throw serializationFailure("JSON 参数类型转换失败", exception);
        }
    }

    /**
     * 解析前先扫描一遍 JSON 结构做安全校验。
     *
     * <p>用栈记录当前所处的数组或对象层级：每进入一层容器压栈并检查深度不超过 64，
     * 容器内每出现一个值就累加计数并检查单个容器元素不超过 10000。
     * 这样可以在真正构造对象之前拦住深度嵌套或超大集合构成的解析放大攻击。
     *
     * @param bytes 待校验的 JSON 字节
     * @throws IllegalArgumentException 当嵌套深度或元素数量超过上限时抛出
     */
    private void validateInput(byte[] bytes) {
        try (var parser = objectMapper.createParser(bytes)) {
            java.util.ArrayDeque<int[]> containers = new java.util.ArrayDeque<>();
            for (var token = parser.nextToken(); token != null; token = parser.nextToken()) {
                if (token == tools.jackson.core.JsonToken.END_ARRAY
                        || token == tools.jackson.core.JsonToken.END_OBJECT) {
                    containers.pop();
                    continue;
                }
                if (!containers.isEmpty() && token != tools.jackson.core.JsonToken.PROPERTY_NAME) {
                    if (++containers.peek()[0] > 10000) {
                        throw new IllegalArgumentException("集合元素数量超过上限");
                    }
                }
                if (token == tools.jackson.core.JsonToken.START_ARRAY
                        || token == tools.jackson.core.JsonToken.START_OBJECT) {
                    if (containers.size() >= 64) {
                        throw new IllegalArgumentException("嵌套深度超过上限");
                    }
                    containers.push(new int[1]);
                }
            }
        }
    }

    /**
     * 把编解码过程中的运行时异常统一包装为 RPC 序列化异常。
     *
     * @param message 中文错误说明
     * @param cause 原始异常；本身已是 {@link RpcException} 时原样返回，避免重复包装
     * @return 可直接抛出的 RPC 异常
     */
    private RpcException serializationFailure(String message, RuntimeException cause) {
        if (cause instanceof RpcException rpcException) {
            return rpcException;
        }
        return new RpcException(RpcErrorCode.SERIALIZATION_FAILED, message, cause);
    }

    /**
     * 在调用方映射器基础上叠加 RPC 需要的固定配置。
     *
     * <p>配置项：忽略未知字段以兼容双方版本差异；日期与 {@link LocalDateTime} 统一使用
     * {@link #DATE_TIME_PATTERN} 文本格式，保证跨语言、跨版本的时间表示一致。
     *
     * @param sourceMapper 调用方提供的映射器
     * @return 完成配置的新映射器，不修改入参实例
     */
    private static ObjectMapper configure(ObjectMapper sourceMapper) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_TIME_PATTERN);
        dateFormat.setLenient(false);
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        SimpleModule dateTimeModule =
                new SimpleModule("RPC 日期时间格式模块")
                        .addSerializer(
                                LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter))
                        .addDeserializer(
                                LocalDateTime.class,
                                new LocalDateTimeDeserializer(dateTimeFormatter));

        return sourceMapper
                .rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .defaultDateFormat(dateFormat)
                .addModule(dateTimeModule)
                .build();
    }
}
