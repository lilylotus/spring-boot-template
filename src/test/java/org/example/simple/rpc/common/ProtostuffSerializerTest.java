package org.example.simple.rpc.common;

import java.util.*;
import java.lang.reflect.Type;
import io.protostuff.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtostuffSerializerTest {
    static class Payload {
        @Tag(1) public String name;
        @Tag(2) public List<Integer> numbers;
        @Tag(3) public java.time.LocalDateTime time;
        public Payload() { }
    }
    static class Legacy { @Tag(1) public String name; public Legacy() { } }
    List<Integer> typeReference;
    @Test void encodesDtoWithStableTagsAndIgnoresAddedFields() {
        ProtostuffSerializer serializer = new ProtostuffSerializer();
        Payload source = new Payload(); source.name = "测试"; source.numbers = Arrays.asList(1, null, 3);
        source.time = java.time.LocalDateTime.of(2026, 9, 14, 12, 30);
        byte[] bytes = serializer.serialize(source);
        Payload result = serializer.deserialize(bytes, Payload.class);
        assertEquals(source.name, result.name); assertEquals(source.numbers, result.numbers);
        assertEquals(source.time, result.time);
        assertEquals(source.name, serializer.deserialize(bytes, Legacy.class).name);
        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).startsWith("{"));
    }
    @Test void supportsTypedListsAndRejectsUntypedObjects() throws Exception {
        ProtostuffSerializer serializer = new ProtostuffSerializer();
        Type type = getClass().getDeclaredField("typeReference").getGenericType();
        assertEquals(List.of(1, 2), serializer.deserialize(serializer.serialize(List.of(1, 2), type), type));
        assertThrows(RpcException.class, () -> serializer.serialize(new Object()));
        assertThrows(RpcException.class, () -> serializer.serialize(Collections.nCopies(10001, 1), type));
        assertThrows(RpcException.class, () -> serializer.deserialize(new byte[]{(byte) 255}, Payload.class));
    }
}
