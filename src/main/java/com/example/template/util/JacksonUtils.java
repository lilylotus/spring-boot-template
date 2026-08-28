package com.example.template.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 统一的 JSON 序列化 / 反序列化 / 类型转换工具类，收敛后端各处重复的 {@link ObjectMapper}
 * 初始化与异常处理逻辑。内部维护的 {@link ObjectMapper} 是独立于 Spring 自动装配、用于
 * HTTP 响应实际序列化的那个 {@link ObjectMapper} Bean 的另一份实例，两者互不影响——调用
 * 本类方法不会改变任何 {@code @RestController} 接口对外返回的 JSON 输出格式，仅用于内部
 * 序列化场景（如落库前的 JSON 快照）。
 */
public final class JacksonUtils {

    /** {@code LocalDateTime} 的统一序列化 / 反序列化格式。 */
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** {@code LocalDate} 的统一序列化 / 反序列化格式。 */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** {@code LocalTime} 的统一序列化 / 反序列化格式。 */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** {@code java.util.Date} 的统一序列化 / 反序列化格式。 */
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 本工具类专用的 {@link ObjectMapper} 实例，不对外暴露、不注册为 Spring Bean。 */
    private static final ObjectMapper MAPPER = buildObjectMapper();

    public static final TypeReference<List<String>> LIST_STRING_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static final TypeReference<Map<String, String>> MAP_STRING_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static final TypeReference<Map<String, Object>> MAP_OBJECT_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static final TypeReference<List<Map<String, String>>> LIST_MAP_STRING_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static final TypeReference<List<Map<String, Object>>> LIST_MAP_OBJECT_TYPE_REFERENCE = new TypeReference<>() {
    };

    /**
     * 禁止实例化。
     */
    private JacksonUtils() {
    }

    /**
     * 构建本工具类专用的 {@link ObjectMapper}：序列化排除 {@code null} 字段、反序列化忽略
     * 目标类未定义的字段、日期类型统一格式化、不以时间戳形式序列化日期。
     *
     * @return 已完成初始化配置的 {@link ObjectMapper}
     */
    public static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME_FORMATTER));
        javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(DATE_FORMATTER));
        javaTimeModule.addDeserializer(LocalDate.class, new LocalDateDeserializer(DATE_FORMATTER));
        javaTimeModule.addSerializer(LocalTime.class, new LocalTimeSerializer(TIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalTime.class, new LocalTimeDeserializer(TIME_FORMATTER));
        mapper.registerModule(javaTimeModule);

        mapper.setDateFormat(new SimpleDateFormat(DATE_PATTERN));
        return mapper;
    }

    /**
     * 把对象序列化为 JSON 字符串。
     *
     * @param obj 待序列化对象
     * @return JSON 字符串
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 把 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把 JSON 字符串反序列化为指定的（可带泛型的）类型。
     *
     * @param json          JSON 字符串
     * @param typeReference 目标类型描述
     * @param <T>           目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(String json, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把 JSON 字符串反序列化为指定类型的对象。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(String json, Type type) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructType(type));
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把 JSON 字节数组反序列化为指定类型的对象。
     *
     * @param bytes JSON 内容的字节数组
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(byte[] bytes, Class<T> clazz) {
        try {
            return MAPPER.readValue(bytes, clazz);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把 JSON 字节数组反序列化为指定的（可带泛型的）类型。
     *
     * @param bytes         JSON 内容的字节数组
     * @param typeReference 目标类型描述
     * @param <T>           目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(byte[] bytes, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(bytes, typeReference);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把 JSON 字节数组反序列化为指定类型的对象。
     *
     * @param bytes JSON 内容的字节数组
     * @param type  目标类型
     * @param <T>   目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(byte[] bytes, Type type) {
        try {
            return MAPPER.readValue(bytes, MAPPER.getTypeFactory().constructType(type));
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把输入流中的 JSON 内容反序列化为指定类型的对象。
     *
     * @param in    JSON 内容输入流
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(InputStream in, Class<T> clazz) {
        try {
            return MAPPER.readValue(in, clazz);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把输入流中的 JSON 内容反序列化为指定的（可带泛型的）类型。
     *
     * @param in            JSON 内容输入流
     * @param typeReference 目标类型描述
     * @param <T>           目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(InputStream in, TypeReference<T> typeReference) {
        try {
            return MAPPER.readValue(in, typeReference);
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把输入流中的 JSON 内容反序列化为指定类型的对象。
     *
     * @param in   JSON 内容输入流
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 反序列化后的对象
     */
    public static <T> T toObj(InputStream in, Type type) {
        try {
            return MAPPER.readValue(in, MAPPER.getTypeFactory().constructType(type));
        } catch (IOException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    /**
     * 把一个已有对象转换为指定类型的另一个对象（等价于先 {@link #toJson} 再
     * {@link #toObj}，但不经过 JSON 字符串中转）。
     *
     * @param obj   源对象
     * @param clazz 目标类型
     * @param <T>   目标类型
     * @return 转换后的对象
     */
    public static <T> T convert(Object obj, Class<T> clazz) {
        return MAPPER.convertValue(obj, clazz);
    }

    /**
     * 把一个已有对象转换为指定的（可带泛型的）类型。
     *
     * @param obj           源对象
     * @param typeReference 目标类型描述
     * @param <T>           目标类型
     * @return 转换后的对象
     */
    public static <T> T convert(Object obj, TypeReference<T> typeReference) {
        return MAPPER.convertValue(obj, typeReference);
    }

    /**
     * 把一个已有对象转换为指定的 {@link JavaType} 类型。
     *
     * @param obj      源对象
     * @param javaType 目标类型
     * @param <T>      目标类型
     * @return 转换后的对象
     */
    public static <T> T convert(Object obj, JavaType javaType) {
        return MAPPER.convertValue(obj, javaType);
    }
}
