package org.example.simple.rpc.common;

import io.protostuff.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 使用显式本地类型 Schema 的 Protostuff 编码器，不启用动态类加载。 */
public final class ProtostuffSerializer implements MessageSerializer {
    /** 允许的最大嵌套层数，超过即判定为恶意或异常报文。 */
    private static final int MAX_DEPTH = 64;
    /** 单个集合或映射允许的最大元素数量，用于防止解析放大。 */
    private static final int MAX_ELEMENTS = 10000;
    /** 类型到字段布局的缓存，避免每次编解码都重新做反射解析。 */
    private final Map<Type, List<Member>> members = new ConcurrentHashMap<>();

    /**
     * 一个参与编码的字段。
     *
     * @param tag 字段编号，决定写入报文的字段号，必须稳定以保证前后兼容
     * @param name 字段名，解码时用于按名回填
     * @param type 字段的声明类型（含泛型信息）
     * @param field 普通 DTO 的反射字段；record 类型为 {@code null}
     * @param accessor record 的组件访问方法；普通 DTO 为 {@code null}
     */
    private record Member(int tag, String name, Type type, Field field, Method accessor) {}

    /** 编解码的统一容器，把任意目标值包装成 Protostuff 需要的消息对象。 */
    private static final class Box {
        Object value;
    }

    /**
     * 返回写入协议头的序列化器标识。
     *
     * @return 固定为 2，代表 Protostuff 编码
     */
    @Override
    public byte id() {
        return 2;
    }

    /**
     * 递归校验目标类型是否可被本序列化器安全处理。
     *
     * @param type 本地声明的目标类型
     * @throws IllegalArgumentException 当类型缺少泛型信息、嵌套过深或需要显式适配器时抛出
     */
    @Override
    public void validateType(Type type) {
        validate(type, new HashSet<>(), 0);
    }

    /**
     * 深度优先遍历类型结构做校验，已访问类型直接返回以支持自引用类型。
     *
     * @param type 当前校验的类型
     * @param visited 已访问类型集合，防止递归死循环
     * @param depth 当前嵌套深度
     */
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

    /**
     * 按本地声明类型把值编码为 Protostuff 字节。
     *
     * @param value 待编码的值，可为 {@code null}
     * @param type 本地声明的值类型，必须携带完整泛型信息
     * @return 编码后的字节
     * @throws RpcException 类型不受支持或编码失败时抛出
     */
    @Override
    public byte[] serialize(Object value, Type type) {
        try {
            validateType(type);
            return encode(value, type, 0, new IdentityHashMap<>());
        } catch (Exception error) {
            throw failure(error);
        }
    }

    /**
     * 按本地声明类型把 Protostuff 字节解码为对象。
     *
     * <p>始终使用本地声明类型构造对象，不读取报文中的类型元数据，因此不存在远端指定类加载的风险。
     *
     * @param bytes 编码字节
     * @param type 本地声明的目标类型
     * @return 解码后的对象
     * @throws RpcException 类型不受支持或解码失败时抛出
     */
    @Override
    public Object deserialize(byte[] bytes, Type type) {
        try {
            validateType(type);
            return decode(bytes, type, 0);
        } catch (Exception error) {
            throw failure(error);
        }
    }

    /**
     * 把编解码异常统一包装为 RPC 序列化异常。
     *
     * @param cause 原始异常
     * @return 可直接抛出的 RPC 异常
     */
    private RpcException failure(Exception cause) {
        return new RpcException(RpcErrorCode.SERIALIZATION_FAILED, "Protostuff 类型或载荷不合法", cause);
    }

    /**
     * 把单个值编码为字节，嵌套结构逐层递归调用本方法。
     *
     * <p>用按引用比较的 {@code path} 记录当前编码路径上的对象：同一对象在自己的子树中再次出现即为
     * 循环引用，必须立即拒绝，否则会无限递归直至栈溢出。
     *
     * @param value 待编码的值
     * @param type 值的声明类型
     * @param depth 当前嵌套深度
     * @param path 当前编码路径上的对象集合
     * @return 编码后的字节
     * @throws IllegalArgumentException 当出现循环对象图或嵌套过深时抛出
     */
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

    /**
     * 把字节解码为单个值，嵌套结构逐层递归调用本方法。
     *
     * @param bytes 编码字节
     * @param type 目标类型
     * @param depth 当前嵌套深度
     * @return 解码后的值
     */
    private Object decode(byte[] bytes, Type type, int depth) {
        checkDepth(depth);
        Box box = new Box();
        ProtostuffIOUtil.mergeFrom(bytes, box, schema(type, depth, new IdentityHashMap<>()));
        return box.value;
    }

    /**
     * 为指定类型构造 Protostuff 读写 Schema。
     *
     * <p>报文字段号约定：1 为空值标记，2 为标量值，3、4 为集合元素或映射的键与值，5 为结束标记，
     * 普通字段则使用 {@code 字段 @Tag + 10}，与上述保留字段号错开。
     * 未知字段号交给 Protostuff 跳过，从而允许对端新增字段而不破坏兼容性。
     *
     * @param type 目标类型
     * @param depth 当前嵌套深度
     * @param path 编码路径上的对象集合，用于向下传递循环引用检测状态
     * @return 该类型对应的 Schema
     */
    private Schema<Box> schema(Type type, int depth, IdentityHashMap<Object, Boolean> path) {
        Class<?> raw = raw(type);
        boolean sequence =
                raw.isArray() && raw != byte[].class || Collection.class.isAssignableFrom(raw);
        boolean mapping = Map.class.isAssignableFrom(raw);
        boolean scalar = scalar(raw);
        List<Member> layout = scalar || sequence || mapping ? List.of() : layout(type);
        return new Schema<>() {
            /**
             * 返回字段号对应的名称，仅用于未知字段的诊断输出。
             *
             * @param number 字段号
             * @return 形如 field3 的占位名称
             */
            public String getFieldName(int number) {
                return "field" + number;
            }

            /**
             * 本 Schema 不按名称写入字段，因此固定返回 0。
             *
             * @param name 字段名
             * @return 固定为 0
             */
            public int getFieldNumber(String name) {
                return 0;
            }

            /**
             * 容器没有必填字段约束，始终视为已初始化。
             *
             * @param box 消息容器
             * @return 固定为 {@code true}
             */
            public boolean isInitialized(Box box) {
                return true;
            }

            /**
             * 创建空的消息容器，供解码时装载结果。
             *
             * @return 新的空容器
             */
            public Box newMessage() {
                return new Box();
            }

            /**
             * 返回消息简名。
             *
             * @return 固定为 TypedValue
             */
            public String messageName() {
                return "TypedValue";
            }

            /**
             * 返回消息全名。
             *
             * @return 固定为 rpc.TypedValue
             */
            public String messageFullName() {
                return "rpc.TypedValue";
            }

            /**
             * 返回消息容器类型。
             *
             * @return 容器类
             */
            public Class<Box> typeClass() {
                return Box.class;
            }

            /**
             * 按目标类型的结构写出值。
             *
             * <p>先写空值标记（字段 1）：值为 {@code null} 时只写标记即返回，因此"字段缺失"与
             * "字段显式为空"可以区分。标量写字段 2；集合元素写字段 3；映射的键写字段 3、值写字段 4，
             * 按相同顺序成对写出以便解码时配对；普通字段写"{@code @Tag} + 10"。
             * 集合与映射写出前校验元素数量上限。末尾写结束标记（字段 5），
             * 使空对象也至少有一个字段，避免被解析为缺失消息。
             *
             * @param out Protostuff 输出
             * @param box 待写出的值容器
             * @throws IOException 写出失败或读取字段失败时抛出
             */
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

            /**
             * 按目标类型的结构读回值，与 {@link #writeTo} 的字段号约定一一对应。
             *
             * <p>先把各字段收集到局部列表与映射，读完后再统一组装：空值标记为真直接置 {@code null}；
             * 数组按元素类型回填，Set 转为 {@link LinkedHashSet}，其余集合保留读取顺序；
             * 映射按键值成对数量校验后按顺序组装；普通对象交由构造逻辑回填字段。
             * 未在布局中出现的字段号交给 Protostuff 跳过，从而允许对端新增字段。
             *
             * @param in Protostuff 输入
             * @param box 用于装载结果的容器
             * @throws IOException 读取失败或映射键值数量不匹配时抛出
             */
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

    /**
     * 解析并缓存类型的字段布局。
     *
     * <p>协议自带的 record 使用硬编码字段号，保证协议结构稳定；业务 DTO 必须是可实例化的普通类，
     * 且每个参与编码的字段都显式声明有效的 {@link Tag}，避免字段顺序变化导致跨版本错位。
     *
     * @param type 目标类型
     * @return 不可变的字段布局列表
     * @throws IllegalArgumentException 当类型不受支持、缺少 {@code @Tag} 或字段编号重复时抛出
     */
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

    /**
     * 用解码出的字段值构造目标对象。
     *
     * <p>record 走规范构造器，缺失字段按类型默认值补齐；普通 DTO 走无参构造器后逐字段回填，
     * 报文中不存在的字段保持对象自身的初始值。
     *
     * @param raw 目标类型
     * @param layout 字段布局
     * @param fields 已解码的字段值，键为字段名
     * @return 构造出的对象
     * @throws IOException 构造失败时抛出
     */
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

    /**
     * 返回类型的默认值，用于补齐 record 构造器中缺失的参数。
     *
     * @param type 目标类型
     * @return 基本类型返回其零值，引用类型返回 {@code null}
     */
    private static Object defaultValue(Class<?> type) {
        return type.isPrimitive() ? Array.get(Array.newInstance(type, 1), 0) : null;
    }

    /**
     * 校验嵌套深度未超过 {@link #MAX_DEPTH}。
     *
     * @param depth 当前嵌套深度
     * @throws IllegalArgumentException 深度超限时抛出
     */
    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("嵌套深度超过上限");
        }
    }

    /**
     * 校验集合元素数量未超过 {@link #MAX_ELEMENTS}。
     *
     * @param size 当前元素数量
     * @throws IllegalArgumentException 数量超限时抛出
     */
    private static void limit(int size) {
        if (size > MAX_ELEMENTS) {
            throw new IllegalArgumentException("集合元素数量超过上限");
        }
    }

    /**
     * 取出类型对应的原始 Class。
     *
     * @param type 声明类型
     * @return 原始 Class
     * @throws IllegalArgumentException 当类型为 {@code Object} 或通配符等不明确类型时抛出
     */
    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> cls && cls != Object.class) {
            return cls;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (Class<?>) parameterized.getRawType();
        }
        throw new IllegalArgumentException("必须提供明确目标类型");
    }

    /**
     * 取出参数化类型的泛型实参。
     *
     * @param type 声明类型
     * @param count 期望的实参个数
     * @return 泛型实参数组
     * @throws IllegalArgumentException 当类型未参数化或实参个数不符时抛出
     */
    private static Type[] arguments(Type type, int count) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length == count) {
            return p.getActualTypeArguments();
        }
        throw new IllegalArgumentException("必须提供集合泛型类型");
    }

    /**
     * 取出数组或集合的元素类型。
     *
     * @param type 数组或集合的声明类型
     * @return 元素类型
     */
    private static Type elementType(Type type) {
        return raw(type).isArray() ? raw(type).getComponentType() : arguments(type, 1)[0];
    }

    /**
     * 把任意类型的数组按下标展开为列表，便于统一按集合方式编码。
     *
     * @param array 数组实例
     * @return 元素列表
     */
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

    /**
     * 以字段号 2 写出标量值。
     *
     * <p>整数族统一使用 zigzag 编码以压缩负数；{@link Date} 写出毫秒时间戳；
     * 高精度数值与枚举写出文本，保证跨语言可读且不丢精度。
     *
     * @param out Protostuff 输出
     * @param value 标量值
     * @param type 标量的声明类型
     * @throws IOException 写出失败时抛出
     */
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

    /**
     * 按本地声明类型读回标量值，并与 {@link #writeScalar} 的写法一一对应。
     *
     * <p>窄整数读回后显式检查取值范围，避免对端传入超范围数值时静默截断。
     *
     * @param in Protostuff 输入
     * @param type 标量的声明类型
     * @return 读回的标量值
     * @throws IOException 读取失败或数值溢出时抛出
     * @throws IllegalArgumentException 遇到未知枚举值或不支持的标量类型时抛出
     */
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
