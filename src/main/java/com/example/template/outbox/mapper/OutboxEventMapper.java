package com.example.template.outbox.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.template.outbox.entity.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * {@link OutboxEvent} 的MyBatis-Plus Mapper，简单的按主键/状态读写，不需要手写XML。
 */
@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    /**
     * 按状态查询、按create_time升序排序、限制返回数量，供消息中继任务分批拉取待处理记录。
     *
     * @param status    过滤的状态
     * @param batchSize 本批最多返回的记录数
     * @return 符合条件的记录，按create_time从早到晚排序
     */
    default List<OutboxEvent> findByStatusOrderByCreateTime(String status, int batchSize) {
        LambdaQueryWrapper<OutboxEvent> query = new LambdaQueryWrapper<OutboxEvent>()
            .eq(OutboxEvent::getStatus, status)
            .orderByAsc(OutboxEvent::getCreateTime);
        IPage<OutboxEvent> page = selectPage(new Page<>(1, batchSize), query);
        return page.getRecords();
    }

}
