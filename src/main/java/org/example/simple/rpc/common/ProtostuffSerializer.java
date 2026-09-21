package org.example.simple.rpc.common;

import io.protostuff.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 使用显式本地类型 Schema 的 Protostuff 编码器，不启用动态类加载。 */
public final class ProtostuffSerializer implements MessageSerializer {
    private static final int MAX_DEPTH = 64;
    private static final int MAX_ELEMENTS = 10000;
    private final Map<Type, List<Member>> members = new ConcurrentHashMap<>();

    private record Member(int tag, String name, Type type, Field field, Method accessor) {}

    private static final class Box {
        Object value;
    }

    @Override
    public byte id() {
        return 2;
    }

    @Override
    public void validateType(Type type) {
        validate(type, new HashSet<>(), 0);
    }

    private void validate(Type type, Set<Type> visited, int depth) {
        checkDepth(depth);
        if (!visited.add(type)) {
            return;
        }
        Class<?> cls = raw(type);
        if (scalar(cls)) {
            return;
        }
        if (cls.isArray() || Collection.class.isAssignableFrom(cls)) {
            validate(elementType(type), visited, depth + 1);
        } else if (Map.class.isAssignableFrom(cls)) {
            for (Type argument : arguments(type, 2)) {
                validate(argument, visited, depth + 1);
            }
        } else {
            for (Member member : layout(type)) {
                validate(member.type(), visited, depth + 1);
            }
        }
    }

    @Override
    public byte[] serialize(Object value, Type type) {
        try {
            validateType(type);
            return encode(value, type, 0, new IdentityHashMap<>());
        } catch (Exception error) {
            throw failure(error);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, Type type) {
        try {
            validateType(type);
            return decode(bytes, type, 0);
        } catch (Exception error) {
            throw failure(error);
        }
    }

    private RpcException failure(Exception cause) {
        return new RpcException(RpcErrorCode.SERIALIZATION_FAILED, "Protostuff 类型或载荷不合法", cause);
    }

    private byte[] encode(
            Object value, Type type, int depth, IdentityHashMap<Object, Boolean> path) {
        checkDepth(depth);
        if (value != null && path.put(value, true) != null) {
            throw new IllegalArgumentException("不支持循环对象图");
        }
        LinkedBuffer buffer = LinkedBuffer.allocate(512);
        try {
            Box box = new Box();
            box.value = value;
            return ProtostuffIOUtil.toByteArray(box, schema(type, depth, path), buffer);
        } finally {
            buffer.clear();
            if (value != null) {
                path.remove(value);
            }
        }
    }

    private Object decode(byte[] bytes, Type type, int depth) {
        checkDepth(depth);
        Box box = new Box();
        ProtostuffIOUtil.mergeFrom(bytes, box, schema(type, depth, new IdentityHashMap<>()));
        return box.value;
    }

    private Schema<Box> schema(Type type, int depth, IdentityHashMap<Object, Boolean> path) {
        Class<?> raw = raw(type);
        boolean sequence =
                raw.isArray() && raw != byte[].class || Collection.class.isAssignableFrom(raw);
        boolean mapping = Map.class.isAssignableFrom(raw);
        boolean scalar = scalar(raw);
        List<Member> layout = scalar || sequence || mapping ? List.of() : layout(type);
        return new Schema<>() {
            public String getFieldName(int number) {
                return "field" + number;
            }

            public int getFieldNumber(String name) {
                return 0;
            }

            public boolean isInitialized(Box box) {
                return true;
            }

            public Box newMessage() {
                return new Box();
            }

            public String messageName() {
                return "TypedValue";
            }

            public String messageFullName() {
                return "rpc.TypedValue";
            }

            public Class<Box> typeClass() {
                return Box.class;
            }

            public void writeTo(Output out, Box box) throws IOException {
                Object value = box.value;
                if (value == null) {
                    out.writeBool(1, true, false);
                    return;
                }
                if (scalar) {
                    writeScalar(out, value, raw);
                    return;
                }
                if (sequence) {
                    int count =
                            raw.isArray() ? Array.getLength(value) : ((Collection<?>) value).size();
                    limit(count);
                    Iterable<?> values = raw.isArray() ? arrayValues(value) : (Collection<?>) value;
                    for (Object element : values) {
                        out.writeByteArray(
                                3, encode(element, elementType(type), depth + 1, path), true);
                    }
                } else if (mapping) {
                    Map<?, ?> values = (Map<?, ?>) value;
                    limit(values.size());
                    Type[] types = arguments(type, 2);
                    for (var entry : values.entrySet()) {
                        out.writeByteArray(
                                3, encode(entry.getKey(), types[0], depth + 1, path), true);
                        out.writeByteArray(
                                4, encode(entry.getValue(), types[1], depth + 1, path), true);
                    }
                } else {
                    try {
                        for (Member member : layout) {
                            Object field =
                                    member.field() == null
                                            ? member.accessor().invoke(value)
                                            : member.field().get(value);
                            out.writeByteArray(
                                    member.tag() + 10,
                                    encode(field, member.type(), depth + 1, path),
                                    false);
                        }
                    } catch (ReflectiveOperationException e) {
                        throw new IOException("读取字段失败", e);
                    }
                }
                out.writeBool(5, true, false);
            }

            public void mergeFrom(Input in, Box box) throws IOException {
                boolean isNull = false;
                List<Object> values = new ArrayList<>();
                List<Object> keys = new ArrayList<>();
                Map<String, Object> fields = new HashMap<>();
                for (int field; (field = in.readFieldNumber(this)) != 0; ) {
                    if (field == 1) {
                        isNull = in.readBool();
                    } else if (scalar && field == 2) {
                        box.value = readScalar(in, raw);
                    } else if (sequence && field == 3) {
                        limit(values.size() + 1);
                        values.add(decode(in.readByteArray(), elementType(type), depth + 1));
                    } else if (mapping && (field == 3 || field == 4)) {
                        List<Object> target = field == 3 ? keys : values;
                        limit(target.size() + 1);
                        target.add(
                                decode(
                                        in.readByteArray(),
                                        arguments(type, 2)[field - 3],
                                        depth + 1));
                    } else if (field == 5) {
                        in.readBool();
                    } else {
                        int fieldNumber = field;
                        Member member =
                                layout.stream()
                                        .filter(m -> m.tag() + 10 == fieldNumber)
                                        .findFirst()
                                        .orElse(null);
                        if (member == null) {
                            in.handleUnknownField(field, this);
                        } else {
                            fields.put(
                                    member.name(),
                                    decode(in.readByteArray(), member.type(), depth + 1));
                        }
                    }
                }
                if (isNull) {
                    box.value = null;
                    return;
                }
                if (sequence) {
                    if (raw.isArray()) {
                        Object array = Array.newInstance(raw.getComponentType(), values.size());
                        for (int i = 0; i < values.size(); i++) {
                            Array.set(array, i, values.get(i));
                        }
                        box.value = array;
                    } else {
                        box.value =
                                Set.class.isAssignableFrom(raw)
                                        ? new LinkedHashSet<>(values)
                                        : values;
                    }
                } else if (mapping) {
                    if (keys.size() != values.size()) {
                        throw new IOException("映射键值数量不匹配");
                    }
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < keys.size(); i++) {
                        map.put(keys.get(i), values.get(i));
                    }
                    box.value = map;
                } else if (!scalar) {
                    box.value = construct(raw, layout, fields);
                }
            }
        };
    }

    private List<Member> layout(Type type) {
        return members.computeIfAbsent(
                type,
                key -> {
                    Class<?> raw = raw(key);
                    List<Member> result = new ArrayList<>();
                    if (raw.isRecord()
                            && raw.getPackageName().equals("org.example.simple.rpc.common")) {
                        Map<String, Integer> tags;
                        if (raw == RpcRequest.class) {
                            tags =
                                    Map.of(
                                            "serviceName",
                                            1,
                                            "methodName",
                                            2,
                                            "parameterTypeNames",
                                            3,
                                            "arguments",
                                            4,
                                            "timeoutMillis",
                                            5,
                                            "traceContext",
                                            6);
                        } else if (raw == RpcResponse.class) {
                            tags = Map.of("success", 1, "result", 2, "error", 3);
                        } else if (raw == RpcPayload.class) {
                            tags = Map.of("isNull", 1, "data", 2);
                        } else if (raw == RpcError.class) {
                            tags = Map.of("code", 1, "message", 2);
                        } else {
                            throw new IllegalArgumentException("未注册的协议类型");
                        }
                        for (RecordComponent component : raw.getRecordComponents()) {
                            result.add(
                                    new Member(
                                            tags.get(component.getName()),
                                            component.getName(),
                                            component.getGenericType(),
                                            null,
                                            component.getAccessor()));
                        }
                    } else {
                        if (raw.isInterface()
                                || Modifier.isAbstract(raw.getModifiers())
                                || raw.isRecord()
                                || raw.getName().startsWith("java.")
                                || raw.getSuperclass() != Object.class) {
                            throw new IllegalArgumentException("类型需要显式适配器: " + raw.getName());
                        }
                        for (Field field : raw.getDeclaredFields()) {
                            if (Modifier.isStatic(field.getModifiers())
                                    || Modifier.isTransient(field.getModifiers())) {
                                continue;
                            }
                            Tag tag = field.getAnnotation(Tag.class);
                            if (tag == null
                                    || tag.value() <= 0
                                    || tag.value() > 100000
                                    || !field.trySetAccessible()) {
                                throw new IllegalArgumentException("DTO 字段必须显式声明有效 @Tag");
                            }
                            result.add(
                                    new Member(
                                            tag.value(),
                                            field.getName(),
                                            field.getGenericType(),
                                            field,
                                            null));
                        }
                        if (result.stream().map(Member::tag).distinct().count() != result.size()) {
                            throw new IllegalArgumentException("DTO 字段编号重复");
                        }
                    }
                    return List.copyOf(result);
                });
    }

    private Object construct(Class<?> raw, List<Member> layout, Map<String, Object> fields)
            throws IOException {
        try {
            if (raw.isRecord()) {
                Class<?>[] types =
                        Arrays.stream(raw.getRecordComponents())
                                .map(RecordComponent::getType)
                                .toArray(Class<?>[]::new);
                Object[] values =
                        layout.stream()
                                .map(
                                        m ->
                                                fields.getOrDefault(
                                                        m.name(), defaultValue(raw(m.type()))))
                                .toArray();
                return raw.getDeclaredConstructor(types).newInstance(values);
            }
            Constructor<?> constructor = raw.getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw new IllegalArgumentException("DTO 构造器不可访问");
            }
            Object value = constructor.newInstance();
            for (Member member : layout) {
                if (fields.containsKey(member.name())) {
                    member.field().set(value, fields.get(member.name()));
                }
            }
            return value;
        } catch (ReflectiveOperationException e) {
            throw new IOException("构造目标类型失败", e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        return type.isPrimitive() ? Array.get(Array.newInstance(type, 1), 0) : null;
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("嵌套深度超过上限");
        }
    }

    private static void limit(int size) {
        if (size > MAX_ELEMENTS) {
            throw new IllegalArgumentException("集合元素数量超过上限");
        }
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> cls && cls != Object.class) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        throw new IllegalArgumentException("必须提供明确目标类型");
    }

    private static Type[] arguments(Type type, int count) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length == count) {
            return p.getActualTypeArguments();
        }
        throw new IllegalArgumentException("必须提供集合泛型类型");
    }

    private static Type elementType(Type type) {
        return raw(type).isArray() ? raw(type).getComponentType() : arguments(type, 1)[0];
    }

    private static List<Object> arrayValues(Object array) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < Array.getLength(array); i++) {
            values.add(Array.get(array, i));
        }
        return values;
    }

    /**
     * 判断类型是否为受支持的标量。
     *
     * <p>这里只列举解码侧确实能够还原的具体类型：抽象的 {@code Number} 及其它未适配的数值类型
     * 必须在校验阶段就被拒绝，否则会通过校验却在解码时才失败。
     *
     * @param type 本地声明的目标类型
     * @return 受支持时为 {@code true}
     */
    private static boolean scalar(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Boolean.class
                || type == Character.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == java.math.BigDecimal.class
                || type == java.math.BigInteger.class
                || type == byte[].class
                || type.isEnum()
                || type == Date.class
                || type == LocalDateTime.class;
    }

    private static void writeScalar(Output out, Object value, Class<?> type) throws IOException {
        if (type == byte[].class) {
            out.writeByteArray(2, (byte[]) value, false);
        } else if (type == boolean.class || type == Boolean.class) {
            out.writeBool(2, (boolean) value, false);
        } else if (type == double.class || type == Double.class) {
            out.writeDouble(2, (double) value, false);
        } else if (type == float.class || type == Float.class) {
            out.writeFloat(2, (float) value, false);
        } else if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            out.writeSInt64(2, ((Number) value).longValue(), false);
        } else if (value instanceof Number n) {
            out.writeString(2, n.toString(), false);
        } else if (value instanceof Date date) {
            out.writeInt64(2, date.getTime(), false);
        } else {
            out.writeString(2, value instanceof Enum<?> e ? e.name() : value.toString(), false);
        }
    }

    private static Object readScalar(Input in, Class<?> type) throws IOException {
        if (type == byte[].class) {
            return in.readByteArray();
        }
        if (type == boolean.class || type == Boolean.class) {
            return in.readBool();
        }
        if (type == double.class || type == Double.class) {
            return in.readDouble();
        }
        if (type == float.class || type == Float.class) {
            return in.readFloat();
        }
        if (type == Date.class) {
            return new Date(in.readInt64());
        }
        if (type == int.class || type == Integer.class) {
            return Math.toIntExact(in.readSInt64());
        }
        if (type == long.class || type == Long.class) {
            return in.readSInt64();
        }
        if (type == short.class || type == Short.class) {
            long value = in.readSInt64();
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw new IOException("短整数溢出");
            }
            return (short) value;
        }
        if (type == byte.class || type == Byte.class) {
            long value = in.readSInt64();
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new IOException("字节溢出");
            }
            return (byte) value;
        }
        String value = in.readString();
        if (type == String.class) {
            return value;
        }
        if (type == char.class || type == Character.class) {
            return value.charAt(0);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.parse(value);
        }
        if (type == java.math.BigDecimal.class) {
            return new java.math.BigDecimal(value);
        }
        if (type == java.math.BigInteger.class) {
            return new java.math.BigInteger(value);
        }
        if (type.isEnum()) {
            return Arrays.stream(type.getEnumConstants())
                    .filter(e -> ((Enum<?>) e).name().equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知枚举值"));
        }
        throw new IllegalArgumentException("不支持的标量类型");
    }
}
