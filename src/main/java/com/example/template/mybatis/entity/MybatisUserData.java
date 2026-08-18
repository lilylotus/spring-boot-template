package com.example.template.mybatis.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@TableName("tb_user_data")
public class MybatisUserData {

    @TableId(type = IdType.AUTO)
    private Integer id;

    private String name;

    private String mobile;

    private String idCard;

    private String address;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public MybatisUserData() {
    }

    public MybatisUserData(String name, String mobile, String idCard, String address) {
        this.name = name;
        this.mobile = mobile;
        this.idCard = idCard;
        this.address = address;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

}
