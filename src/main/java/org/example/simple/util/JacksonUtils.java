package org.example.simple.util;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * 通用 JSON 工具类：对象与 JSON 字符串互转，以及对象到对象的结构转换。
 * <p>
 * 内部使用单个共享、不可变的默认 {@link ObjectMapper}，不提供自定义或注入配置的入口：
 * <ul>
 *   <li>{@link LocalDateTime}/{@link java.util.Date} 使用 {@value #DATE_TIME_PATTERN} 格式，
 *       {@link LocalDate} 使用 {@value #DATE_PATTERN} 格式，{@link LocalTime} 使用
 *       {@value #TIME_PATTERN} 格式，不使用 Jackson 3 默认的 ISO-8601 输出。</li>
 *   <li>反序列化时忽略目标类型未声明的多余字段，缺失字段取默认值，不因此报错。</li>
 *   <li>序列化时跳过值为 {@code null} 的属性，不写入输出 JSON。</li>
 * </ul>
 * <p>
 * {@link #toJson(Object)} 和 {@link #convert(Object, Class)} 等方法的主输入为 {@code null} 时
 * 直接返回 {@code null}，不抛出异常；目标类型参数（{@code Class}/{@code TypeReference}）为
 * {@code null}，或 {@code fromJson} 系列方法的 JSON 输入（{@code String}/{@code byte[]}/
 * {@link InputStream}）为 {@code null} 时，抛出 {@link IllegalArgumentException}。
 * <p>
 * {@code fromJson(InputStream, ...)} 不关闭调用方传入的输入流，由调用方管理生命周期。
 * <p>
 * 底层 Jackson 读写失败产生的异常直接透传，不做二次包装。
 * <p>
 * 本工具类的 {@link ObjectMapper} 单例是线程安全的，所有方法均可被多线程并发调用。
 */
public final class JacksonUtils {

    /** {@link LocalDateTime}/{@link java.util.Date} 的固定文本格式 */
    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** {@link LocalDate} 的固定文本格式 */
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    /** {@link LocalTime} 的固定文本格式 */
    private static final String TIME_PATTERN = "HH:mm:ss";

    private static final ObjectMapper MAPPER = buildMapper();

    private JacksonUtils() {
    }

    /**
     * 把对象序列化为 JSON 字符串。
     *
     * @param value 待序列化的对象，为 {@code null} 时直接返回 {@code null}
     * @return 序列化后的 JSON 字符串；{@code value} 为 {@code null} 时返回 {@code null}
     */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        return MAPPER.writeValueAsString(value);
    }

    /**
     * 把 JSON 字符串反序列化为不带泛型参数的目标类型对象。
     *
     * @param json JSON 字符串，不能为 {@code null}
     * @param type 目标类型，不能为 {@code null}
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 当 {@code json} 或 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 文本不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        return MAPPER.readValue(json, type);
    }

    /**
     * 把 JSON 字符串反序列化为携带完整泛型信息的目标类型对象。
     *
     * @param json JSON 字符串，不能为 {@code null}
     * @param type 目标类型引用，不能为 {@code null}
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 当 {@code json} 或 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 文本不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        return MAPPER.readValue(json, type);
    }

    /**
     * 把 JSON 字节数组反序列化为不带泛型参数的目标类型对象。
     *
     * @param json JSON 字节数组，不能为 {@code null}
     * @param type 目标类型，不能为 {@code null}
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 当 {@code json} 或 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T fromJson(byte[] json, Class<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 字节数组不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        return MAPPER.readValue(json, type);
    }

    /**
     * 把 JSON 字节数组反序列化为携带完整泛型信息的目标类型对象。
     *
     * @param json JSON 字节数组，不能为 {@code null}
     * @param type 目标类型引用，不能为 {@code null}
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 当 {@code json} 或 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T fromJson(byte[] json, TypeReference<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 字节数组不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        return MAPPER.readValue(json, type);
    }

    /**
     * 把 JSON 输入流反序列化为不带泛型参数的目标类型对象。
     * <p>
     * 不关闭传入的输入流，由调用方用 try-with-resources 管理生命周期。
     *
     * @param json JSON 输入流，不能为 {@code null}
     * @param type 目标类型，不能为 {@code null}
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 当 {@code json} 或 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T fromJson(InputStream json, Class<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 输入流不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        return MAPPER.readValue(json, type);
    }

    /**
     * 把 JSON 输入流反序列化为携带完整泛型信息的目标类型对象。
     * <p>
     * 不关闭传入的输入流，由调用方用 try-with-resources 管理生命周期。
     *
     * @param json JSON 输入流，不能为 {@code null}
     * @param type 目标类型引用，不能为 {@code null}
     * @param <T> 目标类型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 当 {@code json} 或 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T fromJson(InputStream json, TypeReference<T> type) {
        if (json == null) {
            throw new IllegalArgumentException("JSON 输入流不能为 null");
        }
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        return MAPPER.readValue(json, type);
    }

    /**
     * 把一个对象直接转换为不带泛型参数的目标类型对象，不经过 JSON 文本序列化往返。
     *
     * @param value 源对象，为 {@code null} 时直接返回 {@code null}
     * @param type 目标类型，不能为 {@code null}
     * @param <T> 目标类型
     * @return 转换后的对象；{@code value} 为 {@code null} 时返回 {@code null}
     * @throws IllegalArgumentException 当 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T convert(Object value, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        if (value == null) {
            return null;
        }
        return MAPPER.convertValue(value, type);
    }

    /**
     * 把一个对象直接转换为携带完整泛型信息的目标类型对象，不经过 JSON 文本序列化往返。
     *
     * @param value 源对象，为 {@code null} 时直接返回 {@code null}
     * @param type 目标类型引用，不能为 {@code null}
     * @param <T> 目标类型
     * @return 转换后的对象；{@code value} 为 {@code null} 时返回 {@code null}
     * @throws IllegalArgumentException 当 {@code type} 为 {@code null} 时抛出
     */
    public static <T> T convert(Object value, TypeReference<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("目标类型不能为 null");
        }
        if (value == null) {
            return null;
        }
        return MAPPER.convertValue(value, type);
    }

    /**
     * 构建本工具类使用的默认 {@link ObjectMapper}。
     * <p>
     * 注册日期时间类型的固定格式序列化器/反序列化器，放宽未知字段和缺失字段的校验，
     * 把默认属性包含策略改为跳过 {@code null} 值，并禁用输入流读取完成后自动关闭的行为，
     * 使 {@link #fromJson(InputStream, Class)} 等方法不关闭调用方传入的流。
     *
     * @return 完成配置的 {@link ObjectMapper}
     */
    private static ObjectMapper buildMapper() {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN);
        SimpleDateFormat legacyDateFormat = new SimpleDateFormat(DATE_TIME_PATTERN);
        legacyDateFormat.setLenient(false);

        SimpleModule dateTimeModule = new SimpleModule("JacksonUtils 日期时间格式模块")
                .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dateTimeFormatter))
                .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(dateTimeFormatter))
                .addSerializer(LocalDate.class, new LocalDateSerializer(dateFormatter))
                .addDeserializer(LocalDate.class, new LocalDateDeserializer(dateFormatter))
                .addSerializer(LocalTime.class, new LocalTimeSerializer(timeFormatter))
                .addDeserializer(LocalTime.class, new LocalTimeDeserializer(timeFormatter));

        return new ObjectMapper()
                .rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
                .changeDefaultPropertyInclusion(ignored -> JsonInclude.Value.ALL_NON_NULL)
                .defaultDateFormat(legacyDateFormat)
                .addModule(dateTimeModule)
                .build();
    }
}
