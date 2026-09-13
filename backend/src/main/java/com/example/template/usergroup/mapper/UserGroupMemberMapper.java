package com.example.template.usergroup.mapper;
import com.example.template.usergroup.entity.UserGroupMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
/** 当前成员关系持久化入口，不承担审批授权逻辑。 */
@Mapper
public interface UserGroupMemberMapper extends BaseMapper<UserGroupMember> {
}
