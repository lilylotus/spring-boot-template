package com.example.template.usergroup.mapper;
import com.example.template.usergroup.entity.UserGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
/** 用户组持久化入口，供用户组服务查询和事务锁定。 */
@Mapper
public interface UserGroupMapper extends BaseMapper<UserGroup> {
}
