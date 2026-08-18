package com.example.template.mybatis;

import com.example.template.mybatis.entity.MybatisUserData;
import com.example.template.mybatis.plus.mapper.MybatisPlusUserDataMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MybatisPlusTest {

    @Autowired
    private MybatisPlusUserDataMapper mybatisPlusUserDataMapper;

    @Test
    void testMybatisPlusMapper() {
        Assertions.assertNotNull(mybatisPlusUserDataMapper);
        MybatisUserData data = mybatisPlusUserDataMapper.selectById(1);
        Assertions.assertNotNull(data);
    }

}
