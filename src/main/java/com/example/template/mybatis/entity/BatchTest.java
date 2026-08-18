package com.example.template.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("batch_test")
public class BatchTest {

    private Long id;
    private String stringField1;
    private String stringField2;
    private String stringField3;
    private String stringField4;
    private String stringField5;
    private String stringField6;
    private String stringField7;
    private String stringField8;
    private String stringField9;
    private String stringField10;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStringField1() {
        return stringField1;
    }

    public void setStringField1(String stringField1) {
        this.stringField1 = stringField1;
    }

    public String getStringField2() {
        return stringField2;
    }

    public void setStringField2(String stringField2) {
        this.stringField2 = stringField2;
    }

    public String getStringField3() {
        return stringField3;
    }

    public void setStringField3(String stringField3) {
        this.stringField3 = stringField3;
    }

    public String getStringField4() {
        return stringField4;
    }

    public void setStringField4(String stringField4) {
        this.stringField4 = stringField4;
    }

    public String getStringField5() {
        return stringField5;
    }

    public void setStringField5(String stringField5) {
        this.stringField5 = stringField5;
    }

    public String getStringField6() {
        return stringField6;
    }

    public void setStringField6(String stringField6) {
        this.stringField6 = stringField6;
    }

    public String getStringField7() {
        return stringField7;
    }

    public void setStringField7(String stringField7) {
        this.stringField7 = stringField7;
    }

    public String getStringField8() {
        return stringField8;
    }

    public void setStringField8(String stringField8) {
        this.stringField8 = stringField8;
    }

    public String getStringField9() {
        return stringField9;
    }

    public void setStringField9(String stringField9) {
        this.stringField9 = stringField9;
    }

    public String getStringField10() {
        return stringField10;
    }

    public void setStringField10(String stringField10) {
        this.stringField10 = stringField10;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "BatchTest{" +
            "id=" + id +
            ", stringField1='" + stringField1 + '\'' +
            ", stringField2='" + stringField2 + '\'' +
            ", stringField3='" + stringField3 + '\'' +
            ", stringField4='" + stringField4 + '\'' +
            ", stringField5='" + stringField5 + '\'' +
            ", stringField6='" + stringField6 + '\'' +
            ", stringField7='" + stringField7 + '\'' +
            ", stringField8='" + stringField8 + '\'' +
            ", stringField9='" + stringField9 + '\'' +
            ", stringField10='" + stringField10 + '\'' +
            ", createTime=" + createTime +
            ", updateTime=" + updateTime +
            '}';
    }
}
