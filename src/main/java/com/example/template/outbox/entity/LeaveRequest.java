package com.example.template.outbox.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 请假审批业务表，作为Outbox模式"业务写入与Outbox写入同一事务"的演示载体
 * (对应{@code Outbox模式事务流程.md}里的请假审批例子)，不代表真实业务用法。
 */
@TableName("leave_request")
public class LeaveRequest {

    /** 主键沿用仓库全局MyBatis-Plus配置的自增id(id-type: auto)。 */
    private Long id;

    /** {@link LeaveRequestStatus} 中的一个取值。 */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

}
