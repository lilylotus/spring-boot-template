create table batch_test (
	id int not null auto_increment ,

    string_field1 varchar(256) not null ,
    string_field2 varchar(256) not null ,
    string_field3 varchar(256) not null ,
    string_field4 varchar(256) not null ,
    string_field5 varchar(256) not null ,
    string_field6 varchar(256) not null ,
    string_field7 varchar(256) not null ,
    string_field8 varchar(256) not null ,
    string_field9 varchar(256) not null ,
    string_field10 varchar(256) not null ,

    create_time datetime not null ,
    update_time datetime not null ,

    primary key (id)
);

CREATE TABLE tb_user_data (
                              id int NOT NULL AUTO_INCREMENT,
                              name varchar(255),
                              mobile varchar(16),
                              id_card varchar(64),
                              address varchar(128),
                              create_time datetime,
                              update_time datetime,
                              PRIMARY KEY (id)
);

insert into batch_test (string_field1, string_field2 ,string_field3 ,string_field4 ,string_field5 ,string_field6 ,string_field7 ,string_field8 ,string_field9 ,string_field10,create_time,update_time)
values ('xx', 'xx', 'xx', 'xx', 'xx', 'xx', 'xx', 'xx', 'xx', 'xx', NOW(), NOW());
commit ;

-- use batch ;
select count(*) from batch_test ;
select * from batch_test ;


explain
select id,string_field1, string_field2 ,string_field3 ,string_field4 ,string_field5 ,string_field6 ,string_field7 ,string_field8 ,string_field9 ,string_field10,create_time,update_time
from batch_test
limit 800000, 100;

explain SELECT id FROM batch_test order by id limit 800000, 20 ;

explain
SELECT b.id, string_field1, string_field2, string_field3, string_field4, string_field5, string_field6, string_field7, string_field8, string_field9, string_field10, create_time, update_time
FROM ( SELECT id FROM batch_test order by id limit 800000, 20 ) AS a inner join batch_test b on a.id = b.id ;
-- 2.125

explain
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5, string_field6, string_field7, string_field8, string_field9, string_field10, create_time, update_time
FROM batch_test
WHERE id IN ( SELECT a.id FROM (SELECT id FROM batch_test order by id limit 800000, 20) AS a ) ;
-- 4.531

explain
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5, string_field6, string_field7, string_field8, string_field9, string_field10, create_time, update_time
FROM batch_test
WHERE id >= ( SELECT a.id FROM batch_test a order by id limit 800000, 1 )
order by id
LIMIT 20 ;
-- 2.156

explain
SELECT b.id, string_field1, string_field2, string_field3, string_field4, string_field5, string_field6, string_field7, string_field8, string_field9, string_field10, create_time, update_time
 FROM ( SELECT id FROM batch_test order by id limit 800000, 20 ) AS a, batch_test b
 WHERE a.id = b.id
 order by id;
 -- 2.125

 -- RBAC (Role-Base Access Controller)

CREATE TABLE rbac_user (
	id INT NOT NULL PRIMARY KEY AUTO_INCREMENT ,
    user_name VARCHAR(256) NOT NULL ,
    age INT NOT NULL,
    sex VARCHAR(8) NOT NULL
) ;

CREATE TABLE rbac_role (
	id INT NOT NULL PRIMARY KEY AUTO_INCREMENT ,
    role_name VARCHAR(256) NOT NULL
);

CREATE TABLE rbac_privileges (
	id INT NOT NULL PRIMARY KEY AUTO_INCREMENT ,
    name VARCHAR(256) NOT NULL
);

CREATE TABLE rbac_user_role (
	id INT NOT NULL PRIMARY KEY AUTO_INCREMENT ,
    user_id INT NOT NULL ,
    role_id INT NOT NULL
);

CREATE TABLE rbac_role_privileges (
	id INT NOT NULL PRIMARY KEY AUTO_INCREMENT ,
    role_id INT NOT NULL ,
    privileges_id INT NOT NULL
) ;


ALTER TABLE rbac_user_role ADD CONSTRAINT uk_user_role UNIQUE (user_id, role_id);


explain
select ru.user_name, ru.age, ru.sex, rr.role_name
from rbac_user ru, rbac_role rr, rbac_user_role rur
where ru.id = rur.user_id and rr.id = rur.role_id;

explain
select rr.role_name, rp.name
from rbac_role rr, rbac_privileges rp, rbac_role_privileges rrp
where rr.id = rrp.role_id and rp.id = rrp.privileges_id ;

select * from rbac_user ;
select * from rbac_role ;
select * from rbac_privileges ;
select * from rbac_user_role ;
select * from rbac_role_privileges ;

truncate table rbac_user ;
truncate table rbac_role ;
truncate table rbac_privileges ;
truncate table rbac_user_role ;
truncate table rbac_role_privileges ;

