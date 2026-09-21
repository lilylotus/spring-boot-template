package org.example.simple.rpc.common;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.protostuff.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protostuff 信封与受控类型 Schema 测试。
 * <p>
 * 覆盖协议 record 的固定字段编号、基础标量与日期时间适配、空值语义、
 * 泛型集合与映射入口，以及各类不受支持类型在编码前的明确拒绝。
 */
class ProtostuffEnvelopeTest {

    private final ProtostuffSerializer serializer = new ProtostuffSerializer();

    /** 用于取得带泛型参数的列表类型。 */
    private List<String> listReference;

    /** 用于取得带泛型参数的映射类型。 */
    private Map<String, Integer> mapReference;

    private Type typeOf(String field) throws NoSuchFieldException {
        return getClass().getDeclaredField(field).getGenericType();
    }

    @Test
    void roundTripsRequestEnvelopeWithFixedFieldNumbers() {
        RpcRequest source = new RpcRequest(
            "订单服务",
            "create",
            List.of("java.lang.String"),
            List.of(new RpcPayload(false, new byte[] {1, 2, 3})),
            2500,
            Map.of("traceparent", "00-1-2-01"));

        RpcRequest result = serializer.deserialize(serializer.serialize(source), RpcRequest.class);

        assertEquals(source.serviceName(), result.serviceName());
        assertEquals(source.methodName(), result.methodName());
        assertEquals(source.parameterTypeNames(), result.parameterTypeNames());
        assertEquals(source.timeoutMillis(), result.timeoutMillis());
        assertEquals(source.traceContext(), result.traceContext());
        assertArrayEquals(source.arguments().get(0).data(), result.arguments().get(0).data());
    }

    @Test
    void roundTripsResponseEnvelopeForSuccessAndFailure() {
        RpcResponse success = RpcResponse.success(new RpcPayload(false, new byte[] {9}));
        RpcResponse failure = RpcResponse.failure(RpcErrorCode.SERVER_BUSY, "服务端繁忙");

        RpcResponse decodedSuccess =
            serializer.deserialize(serializer.serialize(success), RpcResponse.class);
        RpcResponse decodedFailure =
            serializer.deserialize(serializer.serialize(failure), RpcResponse.class);

        assertTrue(decodedSuccess.success());
        assertArrayEquals(new byte[] {9}, decodedSuccess.result().data());
        assertEquals(false, decodedFailure.success());
        assertEquals(RpcErrorCode.SERVER_BUSY, decodedFailure.error().code());
        assertEquals("服务端繁忙", decodedFailure.error().message());
    }

    @Test
    void roundTripsScalarTypesIncludingDateAndTime() {
        assertEquals(42, serializer.deserialize(serializer.serialize(42, int.class), int.class));
        assertEquals(-7L, serializer.deserialize(serializer.serialize(-7L, long.class), long.class));
        assertEquals((short) 3, serializer.deserialize(serializer.serialize((short) 3, short.class), short.class));
        assertEquals((byte) -3, serializer.deserialize(serializer.serialize((byte) -3, byte.class), byte.class));
        assertEquals('中', serializer.deserialize(serializer.serialize('中', char.class), char.class));
        assertEquals(true, serializer.deserialize(serializer.serialize(true, boolean.class), boolean.class));
        assertEquals(1.5d, serializer.deserialize(serializer.serialize(1.5d, double.class), double.class));
        assertEquals(2.5f, serializer.deserialize(serializer.serialize(2.5f, float.class), float.class));
        assertEquals("文本", serializer.deserialize(serializer.serialize("文本", String.class), String.class));
        assertArrayEquals(
            new byte[] {4, 5},
            serializer.deserialize(serializer.serialize(new byte[] {4, 5}, byte[].class), byte[].class));
        assertEquals(
            new BigDecimal("1.25"),
            serializer.deserialize(serializer.serialize(new BigDecimal("1.25"), BigDecimal.class), BigDecimal.class));
        assertEquals(
            new BigInteger("90071992547409999"),
            serializer.deserialize(
                serializer.serialize(new BigInteger("90071992547409999"), BigInteger.class), BigInteger.class));
        assertEquals(
            RpcErrorCode.TIMEOUT,
            serializer.deserialize(serializer.serialize(RpcErrorCode.TIMEOUT, RpcErrorCode.class), RpcErrorCode.class));

        Date date = new Date(1758000000000L);
        assertEquals(date, serializer.deserialize(serializer.serialize(date, Date.class), Date.class));
        LocalDateTime time = LocalDateTime.of(2026, 9, 21, 18, 30, 15);
        assertEquals(
            time, serializer.deserialize(serializer.serialize(time, LocalDateTime.class), LocalDateTime.class));
    }

    @Test
    void distinguishesNullFromEmptyValues() throws Exception {
        assertNull(serializer.deserialize(serializer.serialize(null, String.class), String.class));
        assertEquals("", serializer.deserialize(serializer.serialize("", String.class), String.class));

        Type listType = typeOf("listReference");
        assertEquals(List.of(), serializer.deserialize(serializer.serialize(List.of(), listType), listType));
        assertNull(serializer.deserialize(serializer.serialize(null, listType), listType));
    }

    @Test
    void roundTripsGenericCollectionsAndMaps() throws Exception {
        Type listType = typeOf("listReference");
        Type mapType = typeOf("mapReference");

        assertEquals(
            List.of("甲", "乙"),
            serializer.deserialize(serializer.serialize(List.of("甲", "乙"), listType), listType));
        assertEquals(
            Map.of("甲", 1),
            serializer.deserialize(serializer.serialize(Map.of("甲", 1), mapType), mapType));
    }

    @Test
    void rejectsTypesWithoutExplicitSchema() {
        assertThrows(RpcException.class, () -> serializer.serialize(new Object()));
        assertThrows(RpcException.class, () -> serializer.serialize("x", Runnable.class));
        assertThrows(RpcException.class, () -> serializer.serialize(1, Number.class));
        assertThrows(RpcException.class, () -> serializer.serialize(new Concrete(), Abstract.class));
        assertThrows(RpcException.class, () -> serializer.serialize(UUID.randomUUID(), UUID.class));
        assertThrows(RpcException.class, () -> serializer.serialize(new Untagged(), Untagged.class));
        assertThrows(RpcException.class, () -> serializer.serialize(new Duplicated(), Duplicated.class));
        assertThrows(RpcException.class, () -> serializer.serialize(new Foreign("甲"), Foreign.class));
        assertThrows(RpcException.class, () -> serializer.serialize(List.of("甲"), List.class));
    }

    @Test
    void rejectsCyclicObjectGraph() {
        Node node = new Node();
        node.name = "自引用";
        node.next = node;

        assertThrows(RpcException.class, () -> serializer.serialize(node, Node.class));
    }

    @Test
    void rejectsNestingBeyondDepthLimit() {
        Node head = new Node();
        head.name = "顶层";
        Node current = head;
        for (int depth = 0; depth < 70; depth++) {
            current.next = new Node();
            current.next.name = "层" + depth;
            current = current.next;
        }

        assertThrows(RpcException.class, () -> serializer.serialize(head, Node.class));
    }

    /**
     * 缺少字段编号的 DTO。
     */
    static class Untagged {

        /** 未声明 {@code @Tag} 的字段。 */
        public String name;
    }

    /**
     * 字段编号重复的 DTO。
     */
    static class Duplicated {

        /** 第一个使用编号 1 的字段。 */
        @Tag(1)
        public String first;

        /** 第二个使用编号 1 的字段。 */
        @Tag(1)
        public String second;
    }

    /**
     * 协议包之外的 record，必须要求显式适配器。
     *
     * @param name 名称
     */
    record Foreign(String name) {
    }

    /**
     * 抽象类型，必须要求显式适配器。
     */
    abstract static class Abstract {

        /** 抽象类型上的字段。 */
        @Tag(1)
        public String name;
    }

    /**
     * 抽象类型的具体实现，用于构造多态场景。
     */
    static final class Concrete extends Abstract {
    }

    /**
     * 自引用 DTO，用于构造循环图与深层嵌套。
     */
    static class Node {

        /** 节点名称。 */
        @Tag(1)
        public String name;

        /** 下一个节点。 */
        @Tag(2)
        public Node next;
    }
}
