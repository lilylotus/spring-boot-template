package org.example.simple.record;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RecordServiceTest {

    @Test
    void testRecordService() {
        RecordService rs = new RecordService(UUID.randomUUID().toString().replace("-", ""), UUID.randomUUID().toString().replace("-", ""), 1);
        System.out.println(rs);
    }

}
