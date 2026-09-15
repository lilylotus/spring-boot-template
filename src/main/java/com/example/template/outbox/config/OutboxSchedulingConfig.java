package com.example.template.outbox.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用Spring定时任务能力，{@code OutboxMessageRelay}的{@code @Scheduled}轮询依赖它才能生效。
 * 仓库其余模块目前都不需要定时任务，故把{@code @EnableScheduling}放在Outbox模块自己的配置类里，
 * 而不是放到应用启动类上——这样整个Outbox模块(含这条开关)可以作为一个整体被删除/复用，
 * 不会影响仓库其它部分。
 */
@Configuration
@EnableScheduling
public class OutboxSchedulingConfig {
}
