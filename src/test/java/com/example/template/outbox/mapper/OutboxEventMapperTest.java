package com.example.template.outbox.mapper;

import com.example.template.outbox.entity.OutboxEvent;
import com.example.template.outbox.entity.OutboxEventStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutboxEventMapper} 的持久化测试，需要本地MySQL已执行{@code sql/outbox.sql}建表。
 */
@SpringBootTest
class OutboxEventMapperTest {

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    private final List<String> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        insertedIds.forEach(outboxEventMapper::deleteById);
        insertedIds.clear();
    }

    @Test
    void shouldCreateNewRecordWithPendingStatusAndZeroRetryCount() {
        OutboxEvent event = OutboxEvent.pending("leave-1", "ApprovalFinishedEvent", "{}");
        outboxEventMapper.insert(event);
        insertedIds.add(event.getId());

        OutboxEvent saved = outboxEventMapper.selectById(event.getId());
        assertEquals(OutboxEventStatus.PENDING, saved.getStatus());
        assertEquals(0, saved.getRetryCount());
    }

    @Test
    void shouldReturnPendingRecordsOrderedByCreateTimeWithinBatchLimit() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            OutboxEvent event = OutboxEvent.pending("leave-" + i, "ApprovalFinishedEvent", "{}");
            outboxEventMapper.insert(event);
            insertedIds.add(event.getId());
            // 保证create_time有可区分的先后顺序，避免同一毫秒内插入导致排序断言不稳定
            Thread.sleep(5);
        }

        List<OutboxEvent> firstTwo = outboxEventMapper.findByStatusOrderByCreateTime(OutboxEventStatus.PENDING, 2);
        assertEquals(2, firstTwo.size());
        assertTrue(firstTwo.get(0).getCreateTime().isBefore(firstTwo.get(1).getCreateTime())
            || firstTwo.get(0).getCreateTime().isEqual(firstTwo.get(1).getCreateTime()));
    }

}
