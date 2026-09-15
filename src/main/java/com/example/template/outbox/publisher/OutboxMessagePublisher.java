package com.example.template.outbox.publisher;

import com.example.template.outbox.entity.OutboxEvent;

/**
 * 把一条Outbox记录真正投递出去的接口，消息中继任务({@code OutboxMessageRelay})只依赖这个接口，
 * 不关心背后是进程内事件、还是真实的Kafka/RabbitMQ等消息队列。仓库目前没有引入真实MQ依赖，
 * 默认实现{@code InProcessOutboxMessagePublisher}用Spring自身的{@code ApplicationEventPublisher}
 * 模拟"投递"这一步；需要接入真实MQ时，实现本接口并替换Spring装配的bean即可，不需要改动中继任务。
 */
public interface OutboxMessagePublisher {

    /**
     * 投递一条记录。实现方约定：投递失败通过抛出异常表达，调用方(消息中继任务)据此判断是否需要重试，
     * 不使用返回布尔值这种容易被调用方忽略的失败表达方式。
     *
     * @param event 待投递的Outbox记录
     */
    void publish(OutboxEvent event);

}
