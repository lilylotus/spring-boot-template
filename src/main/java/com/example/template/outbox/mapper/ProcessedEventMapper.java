package com.example.template.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.template.outbox.entity.ProcessedEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link ProcessedEvent} 的MyBatis-Plus Mapper，消费者用{@code exists}判重、用{@code insert}落地
 * 幂等记录，不需要自定义方法。
 */
@Mapper
public interface ProcessedEventMapper extends BaseMapper<ProcessedEvent> {
}
