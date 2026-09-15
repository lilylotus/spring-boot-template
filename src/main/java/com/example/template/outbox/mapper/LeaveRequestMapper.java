package com.example.template.outbox.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.template.outbox.entity.LeaveRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link LeaveRequest} 的MyBatis-Plus Mapper，按主键查询/更新即可满足演示场景，不需要自定义方法。
 */
@Mapper
public interface LeaveRequestMapper extends BaseMapper<LeaveRequest> {
}
