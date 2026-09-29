package org.example.simple.util;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JacksonUtils} 的序列化、反序列化和对象转换测试。 */
class JacksonUtilsTest {

    /** 简单 POJO，覆盖 {@code null} 字段跳过场景。 */
    static class OrderDto {

        private String code;
        private Double amount;
        private String remark;

        OrderDto() {
        }

        OrderDto(String code, Double amount, String remark) {
            this.code = code;
            this.amount = amount;
            this.remark = remark;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public Double getAmount() {
            return amount;
        }

        public void setAmount(Double amount) {
            this.amount = amount;
        }

        public String getRemark() {
            return remark;
        }

        public void setRemark(String remark) {
            this.remark = remark;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof OrderDto that)) {
                return false;
            }
            return java.util.Objects.equals(code, that.code)
                    && java.util.Objects.equals(amount, that.amount)
                    && java.util.Objects.equals(remark, that.remark);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(code, amount, remark);
        }
    }

    /** 覆盖四种日期时间类型的 POJO。 */
    static class DateFieldsDto {

        private LocalDateTime createdAt;
        private LocalDate bornOn;
        private LocalTime alarmAt;
        private Date legacyDate;

        DateFieldsDto() {
        }

        DateFieldsDto(LocalDateTime createdAt, LocalDate bornOn, LocalTime alarmAt, Date legacyDate) {
            this.createdAt = createdAt;
            this.bornOn = bornOn;
            this.alarmAt = alarmAt;
            this.legacyDate = legacyDate;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public LocalDate getBornOn() {
            return bornOn;
        }

        public void setBornOn(LocalDate bornOn) {
            this.bornOn = bornOn;
        }

        public LocalTime getAlarmAt() {
            return alarmAt;
        }

        public void setAlarmAt(LocalTime alarmAt) {
            this.alarmAt = alarmAt;
        }

        public Date getLegacyDate() {
            return legacyDate;
        }

        public void setLegacyDate(Date legacyDate) {
            this.legacyDate = legacyDate;
        }
    }

    /** 三字段记录类型，用于验证依赖全部构造参数的记录在缺字段时仍能反序列化。 */
    record ThreeFieldRecord(String name, int age, String city) {
    }

    /** 记录 {@code close()} 是否被调用的输入流包装，用于验证反序列化不关闭调用方传入的流。 */
    static class CloseTrackingInputStream extends FilterInputStream {

        private boolean closed;

        CloseTrackingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }

    @Test
    void serializesPojoListAndMap() {
        OrderDto order = new OrderDto("A-01", 12.5, "备注");
        String json = JacksonUtils.toJson(order);
        assertTrue(json.contains("\"code\":\"A-01\""));
        assertTrue(json.contains("\"amount\":12.5"));
        assertTrue(json.contains("\"remark\":\"备注\""));

        List<String> list = List.of("a", "b");
        assertEquals("[\"a\",\"b\"]", JacksonUtils.toJson(list));

        Map<String, Object> map = new HashMap<>();
        map.put("key", "value");
        assertEquals("{\"key\":\"value\"}", JacksonUtils.toJson(map));
    }

    @Test
    void serializingNullReturnsNullWithoutThrowing() {
        assertNull(JacksonUtils.toJson(null));
    }

    @Test
    void serializesFixedDateTimeFormatsAndSkipsNullFields() {
        DateFieldsDto dto = new DateFieldsDto(
                LocalDateTime.of(2026, 9, 29, 10, 30, 0),
                LocalDate.of(2026, 9, 29),
                LocalTime.of(10, 30, 0),
                new Date(1780000000000L));
        String json = JacksonUtils.toJson(dto);
        assertTrue(json.contains("\"createdAt\":\"2026-09-29 10:30:00\""));
        assertTrue(json.contains("\"bornOn\":\"2026-09-29\""));
        assertTrue(json.contains("\"alarmAt\":\"10:30:00\""));
        assertFalse(json.contains("T10:30"), "不应使用 ISO-8601 分隔符");

        OrderDto withNullRemark = new OrderDto("A-02", 5.0, null);
        String orderJson = JacksonUtils.toJson(withNullRemark);
        assertFalse(orderJson.contains("remark"));
        assertTrue(orderJson.contains("\"code\":\"A-02\""));
        assertTrue(orderJson.contains("\"amount\":5.0"));
    }

    @Test
    void deserializesJsonIntoPojoByClass() {
        OrderDto restored = JacksonUtils.fromJson("{\"code\":\"A-01\",\"amount\":12.5,\"remark\":\"备注\"}", OrderDto.class);
        assertEquals(new OrderDto("A-01", 12.5, "备注"), restored);
    }

    @Test
    void roundTripsDateTimeFieldsThroughFixedFormats() {
        DateFieldsDto original = new DateFieldsDto(
                LocalDateTime.of(2026, 9, 29, 10, 30, 0),
                LocalDate.of(2026, 9, 29),
                LocalTime.of(10, 30, 0),
                new Date(1780000000000L / 1000 * 1000));
        DateFieldsDto restored = JacksonUtils.fromJson(JacksonUtils.toJson(original), DateFieldsDto.class);
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(original.getBornOn(), restored.getBornOn());
        assertEquals(original.getAlarmAt(), restored.getAlarmAt());
        assertEquals(original.getLegacyDate(), restored.getLegacyDate());
    }

    @Test
    void fromJsonByClassRejectsNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.fromJson((String) null, OrderDto.class));
        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.fromJson("{}", (Class<OrderDto>) null));
    }

    @Test
    void fromJsonByClassIgnoresUnknownFields() {
        OrderDto restored = JacksonUtils.fromJson(
                "{\"code\":\"A-01\",\"amount\":12.5,\"remark\":\"备注\",\"extra\":\"多余字段\"}", OrderDto.class);
        assertEquals(new OrderDto("A-01", 12.5, "备注"), restored);
    }

    @Test
    void fromJsonByClassToleratesMissingFieldsIncludingRecordConstructorArgs() {
        OrderDto pojoMissingRemark = JacksonUtils.fromJson("{\"code\":\"A-01\",\"amount\":12.5}", OrderDto.class);
        assertEquals(new OrderDto("A-01", 12.5, null), pojoMissingRemark);

        ThreeFieldRecord record = JacksonUtils.fromJson("{\"name\":\"张三\",\"city\":\"上海\"}", ThreeFieldRecord.class);
        assertEquals(new ThreeFieldRecord("张三", 0, "上海"), record);
    }

    @Test
    void fromJsonByClassPropagatesJacksonExceptionForMalformedJson() {
        assertInstanceOf(JacksonException.class, assertThrows(JacksonException.class,
                () -> JacksonUtils.fromJson("not-a-json", OrderDto.class)));
    }

    @Test
    void fromJsonByteArrayMatchesStringResultAndRejectsNullArguments() {
        String json = "{\"code\":\"A-01\",\"amount\":12.5,\"remark\":\"备注\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        OrderDto byClass = JacksonUtils.fromJson(bytes, OrderDto.class);
        assertEquals(JacksonUtils.fromJson(json, OrderDto.class), byClass);

        List<OrderDto> byTypeReference = JacksonUtils.fromJson(
                "[{\"code\":\"A-01\",\"amount\":12.5}]".getBytes(StandardCharsets.UTF_8),
                new TypeReference<List<OrderDto>>() {
                });
        assertEquals(List.of(new OrderDto("A-01", 12.5, null)), byTypeReference);

        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.fromJson((byte[]) null, OrderDto.class));
        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.fromJson(bytes, (Class<OrderDto>) null));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.fromJson((byte[]) null, new TypeReference<OrderDto>() {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.fromJson(bytes, (TypeReference<OrderDto>) null));
    }

    @Test
    void fromJsonInputStreamMatchesStringResultAndDoesNotCloseStream() throws IOException {
        String json = "{\"code\":\"A-01\",\"amount\":12.5,\"remark\":\"备注\"}";

        try (var tracking = new CloseTrackingInputStream(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))) {
            OrderDto byClass = JacksonUtils.fromJson(tracking, OrderDto.class);
            assertEquals(JacksonUtils.fromJson(json, OrderDto.class), byClass);
            assertFalse(tracking.isClosed(), "fromJson 不应关闭调用方传入的 InputStream");
        }

        try (var tracking = new CloseTrackingInputStream(new ByteArrayInputStream(
                "[{\"code\":\"A-01\",\"amount\":12.5}]".getBytes(StandardCharsets.UTF_8)))) {
            List<OrderDto> byTypeReference = JacksonUtils.fromJson(tracking, new TypeReference<List<OrderDto>>() {
            });
            assertEquals(List.of(new OrderDto("A-01", 12.5, null)), byTypeReference);
            assertFalse(tracking.isClosed(), "fromJson 不应关闭调用方传入的 InputStream");
        }

        InputStream openStream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.fromJson((InputStream) null, OrderDto.class));
        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.fromJson(openStream, (Class<OrderDto>) null));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.fromJson((InputStream) null, new TypeReference<OrderDto>() {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.fromJson(openStream, (TypeReference<OrderDto>) null));
    }

    @Test
    void fromJsonByTypeReferenceHandlesGenericTypesAndRejectsNullArguments() {
        List<OrderDto> orders = JacksonUtils.fromJson(
                "[{\"code\":\"A-01\",\"amount\":12.5},{\"code\":\"A-02\",\"amount\":5.0}]",
                new TypeReference<List<OrderDto>>() {
                });
        assertEquals(List.of(new OrderDto("A-01", 12.5, null), new OrderDto("A-02", 5.0, null)), orders);

        Map<String, Object> map = JacksonUtils.fromJson("{\"key\":\"value\"}", new TypeReference<Map<String, Object>>() {
        });
        assertEquals(Map.of("key", "value"), map);

        OrderDto single = JacksonUtils.fromJson(
                "{\"code\":\"A-01\",\"amount\":12.5}", new TypeReference<OrderDto>() {
                });
        assertEquals(JacksonUtils.fromJson("{\"code\":\"A-01\",\"amount\":12.5}", OrderDto.class), single);

        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.fromJson((String) null, new TypeReference<OrderDto>() {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.fromJson("{}", (TypeReference<OrderDto>) null));
    }

    @Test
    void convertByClassRoundTripsBetweenMapAndPojoAndRejectsInvalidArguments() {
        OrderDto order = new OrderDto("A-01", 12.5, "备注");
        Map<String, Object> asMap = JacksonUtils.convert(order, new TypeReference<Map<String, Object>>() {
        });
        assertEquals(order, JacksonUtils.convert(asMap, OrderDto.class));

        assertNull(JacksonUtils.convert(null, OrderDto.class));
        assertThrows(IllegalArgumentException.class, () -> JacksonUtils.convert(order, (Class<OrderDto>) null));

        DateFieldsDto dateFields = new DateFieldsDto(
                LocalDateTime.of(2026, 9, 29, 10, 30, 0), LocalDate.of(2026, 9, 29),
                LocalTime.of(10, 30, 0), new Date(1780000000000L / 1000 * 1000));
        Map<String, Object> dateFieldsAsMap = JacksonUtils.convert(dateFields, new TypeReference<Map<String, Object>>() {
        });
        assertEquals("2026-09-29 10:30:00", dateFieldsAsMap.get("createdAt"));
        assertFalse(dateFieldsAsMap.containsKey("nonExistentField"));

        OrderDto withNullRemark = new OrderDto("A-02", 5.0, null);
        Map<String, Object> nullRemarkAsMap = JacksonUtils.convert(withNullRemark, new TypeReference<Map<String, Object>>() {
        });
        assertFalse(nullRemarkAsMap.containsKey("remark"));
    }

    @Test
    void convertByTypeReferenceHandlesGenericTypesAndMatchesJsonRoundTrip() {
        List<OrderDto> orders = new ArrayList<>();
        orders.add(new OrderDto("A-01", 12.5, "备注"));
        orders.add(new OrderDto("A-02", 5.0, null));

        List<Map<String, Object>> asMaps = JacksonUtils.convert(orders, new TypeReference<List<Map<String, Object>>>() {
        });
        assertEquals(2, asMaps.size());
        assertEquals("A-01", asMaps.get(0).get("code"));

        List<Map<String, Object>> viaJsonRoundTrip = JacksonUtils.fromJson(
                JacksonUtils.toJson(orders), new TypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(viaJsonRoundTrip, asMaps);

        assertNull(JacksonUtils.convert(null, new TypeReference<List<Map<String, Object>>>() {
        }));
        assertThrows(IllegalArgumentException.class,
                () -> JacksonUtils.convert(orders, (TypeReference<List<Map<String, Object>>>) null));
    }

    @Test
    void handlesConcurrentCallsAcrossThreadsWithoutCrossContamination() throws Exception {
        AtomicBoolean failed = new AtomicBoolean(false);
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int workerIndex = worker;
                futures.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        for (int iteration = 0; iteration < 500; iteration++) {
                            OrderDto order = new OrderDto("W" + workerIndex + "-" + iteration, (double) iteration, null);
                            String json = JacksonUtils.toJson(order);
                            OrderDto restored = JacksonUtils.fromJson(json, OrderDto.class);
                            if (!order.equals(restored)) {
                                failed.set(true);
                            }
                            Map<String, Object> asMap = JacksonUtils.convert(order, new TypeReference<Map<String, Object>>() {
                            });
                            if (!order.equals(JacksonUtils.convert(asMap, OrderDto.class))) {
                                failed.set(true);
                            }
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        failed.set(true);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertFalse(failed.get());
    }
}
