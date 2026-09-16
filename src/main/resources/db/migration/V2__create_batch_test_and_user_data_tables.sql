-- 把sql/batch-test.sql中被Java代码实际引用的建表语句迁移到Flyway管理，按新库方式建表，
-- 不需要考虑"表已存在"的兼容问题。sql/batch-test.sql中与代码无关的rbac_*演示表、
-- EXPLAIN/TRUNCATE等探索性语句不纳入本迁移，仍保留在原文件中作历史参考。

-- 分页策略对比用的测试表，对应mybatis.entity.BatchTest + mybatis.mapper.BatchTestMapper /
-- mybatis.plus.mapper.BatchTestPlusMapper（两套MyBatis用法共用同一张表做对比）
CREATE TABLE batch_test (
    id            INT NOT NULL AUTO_INCREMENT,
    string_field1 VARCHAR(256) NOT NULL,
    string_field2 VARCHAR(256) NOT NULL,
    string_field3 VARCHAR(256) NOT NULL,
    string_field4 VARCHAR(256) NOT NULL,
    string_field5 VARCHAR(256) NOT NULL,
    string_field6 VARCHAR(256) NOT NULL,
    string_field7 VARCHAR(256) NOT NULL,
    string_field8 VARCHAR(256) NOT NULL,
    string_field9 VARCHAR(256) NOT NULL,
    string_field10 VARCHAR(256) NOT NULL,
    create_time   DATETIME NOT NULL,
    update_time   DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 对应mybatis.entity.MybatisUserData + mybatis.plus.mapper.MybatisPlusUserDataMapper
CREATE TABLE tb_user_data (
    id          INT NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255),
    mobile      VARCHAR(16),
    id_card     VARCHAR(64),
    address     VARCHAR(128),
    create_time DATETIME,
    update_time DATETIME,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 种子数据：MybatisPlusTest#testMybatisPlusMapper按id=1查询并断言非空，全新环境也要有这条记录
INSERT INTO tb_user_data (id, name, mobile, id_card, address, create_time, update_time)
VALUES (1, '测试用户', '13800000000', '000000000000000000', '测试地址', NOW(), NOW());
