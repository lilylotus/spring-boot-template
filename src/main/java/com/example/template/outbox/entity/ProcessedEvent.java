package com.example.template.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 消费方幂等记录：以{@code eventId}(对应{@link OutboxEvent#getId()})为主键，消费者处理消息前先
 * 查询本表判断是否已处理过，避免消息重复投递(中继重试/MQ的at-least-once语义)导致业务逻辑被
 * 重复执行。
 */
@TableName("processed_event")
public class ProcessedEvent {

    /** 主键直接使用外部传入的event_id，不使用自增/UUID生成(IdType.INPUT)。 */
    @TableId(value = "event_id", type = IdType.INPUT)
    private String eventId;

    private LocalDateTime processTime;

    public ProcessedEvent() {
    }

    public ProcessedEvent(String eventId, LocalDateTime processTime) {
        this.eventId = eventId;
        this.processTime = processTime;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public LocalDateTime getProcessTime() {
        return processTime;
    }

    public void setProcessTime(LocalDateTime processTime) {
        this.processTime = processTime;
    }

}
