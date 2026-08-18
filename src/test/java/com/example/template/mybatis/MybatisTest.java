package com.example.template.mybatis;

import com.example.template.mybatis.entity.BatchTest;
import com.example.template.mybatis.mapper.BatchTestMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@SpringBootTest
class MybatisTest {

    @Autowired
    private BatchTestMapper batchTestMapper;

    BatchTest generateBatchTest() {
        BatchTest result = new BatchTest();
//        result.setId(SnowFlakeId.nextSnowId());
        result.setCreateTime(LocalDateTime.now());
        result.setUpdateTime(LocalDateTime.now());
        result.setStringField1(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField2(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField3(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField4(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField5(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField6(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField7(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField8(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField9(UUID.randomUUID().toString().replace("-", ""));
        result.setStringField10(UUID.randomUUID().toString().replace("-", ""));
        return result;
    }

    @Test
    void testMybatisMapper() {
        Assertions.assertNotNull(batchTestMapper);
//        List<BatchTest> list = batchTestMapper.queryOptimal(0, 10);
        List<BatchTest> list = batchTestMapper.queryOptimalJoin(0, 10);
        System.out.println("size = " + list.size());
        Assertions.assertNotNull(list);
    }

    @Test
    void testMybatisMapperInsert() {
        Assertions.assertNotNull(batchTestMapper);
        int loop = 100;
        for (int i = 0; i < loop; i++) {
            BatchTest batchTest = generateBatchTest();
            Integer result = batchTestMapper.insertBatchTest(batchTest);
            Assertions.assertEquals(1, result);
        }
        /*for (int i = 0; i < loop; i++) {
            System.out.printf("%-2d", SnowFlakeId.nextSnowId() % 4);
        }*/
    }

}
