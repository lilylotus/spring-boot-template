package com.example.template.mybatis;

import com.example.template.mybatis.entity.MybatisUserData;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SystemMetaObjectTest {

    @Test
    void testObject() {
        final MybatisUserData dto = new MybatisUserData();
        final MetaObject mo = SystemMetaObject.forObject(dto);
        final int idValue = 10;
        mo.setValue("id", idValue);

        Integer iv = (Integer) mo.getValue("id");

        Assertions.assertEquals(idValue, iv);

    }

}
