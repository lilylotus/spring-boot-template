package com.example.template.usergroup;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.example.template.identity.entity.SysUser;
import com.example.template.identity.mapper.SysUserMapper;
import com.example.template.usergroup.dto.UserGroupRequest;
import com.example.template.usergroup.dto.UserGroupView;
import com.example.template.usergroup.service.UserGroupService;
import com.example.template.util.JacksonUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实 MVC 与数据库验证用户组校验、版本冲突及成员保存原子性；测试数据整体回滚。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserGroupControllerIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserGroupService groups;
    @Autowired private SysUserMapper users;
    private Long userId;
    private UserGroupView group;

    /** 创建专用有效用户与用户组，不依赖本地已有数据。 */
    @BeforeEach
    void setup() {
        SysUser user = new SysUser();
        user.setUsername("group-api-" + UUID.randomUUID());
        user.setRealName("接口测试用户");
        user.setStatus("ACTIVE");
        user.setCreatedTime(LocalDateTime.now());
        user.setUpdatedTime(LocalDateTime.now());
        users.insert(user);
        userId = user.getId();
        group = groups.save(null, request("ACTIVE", List.of(userId)), "test");
    }

    /** 查询与停用保留成员和历史引用，恢复有效状态后成员重新生效。 */
    @Test
    void update_disabledGroup_preservesMembership() throws Exception {
        mvc.perform(get("/api/user-groups")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));
        mvc.perform(put("/api/user-groups/{id}", group.id()).contentType(MediaType.APPLICATION_JSON)
                .content(JacksonUtils.toJson(request("DISABLED", List.of(userId)))))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.result.activeMemberIds").isEmpty())
                .andExpect(jsonPath("$.result.memberIds[0]").value(userId));
        group = groups.get(group.id());
        groups.save(group.id(), request("ACTIVE", List.of(userId)), "test");
        assertThat(groups.activeMembers(group.id())).containsExactly(userId.toString());
    }

    /** 新增接口拒绝重复编码，不能覆盖现有成员。 */
    @Test
    void create_duplicateCode_preservesExistingGroup() throws Exception {
        var duplicate = new UserGroupRequest(group.code(), "重复组", "", "ACTIVE", 0L, List.of());
        mvc.perform(post("/api/user-groups").contentType(MediaType.APPLICATION_JSON)
                .content(JacksonUtils.toJson(duplicate))).andExpect(jsonPath("$.code").value("500"));
        assertThat(groups.get(group.id()).memberIds()).containsExactly(userId);
    }

    /** 无效、重复成员及旧编辑版本均不能修改组信息或成员关系。 */
    @Test
    void update_invalidMembersOrRevision_keepsOriginalData() throws Exception {
        for (List<Long> ids : List.of(List.of(userId, userId), List.of(Long.MAX_VALUE))) {
            mvc.perform(put("/api/user-groups/{id}", group.id()).contentType(MediaType.APPLICATION_JSON)
                    .content(JacksonUtils.toJson(request("ACTIVE", ids))))
                    .andExpect(jsonPath("$.code").value("500"));
            assertThat(groups.get(group.id()).memberIds()).containsExactly(userId);
        }
        var stale = new UserGroupRequest(group.code(), "覆盖", "", "ACTIVE", 0L, List.of());
        mvc.perform(put("/api/user-groups/{id}", group.id()).contentType(MediaType.APPLICATION_JSON)
                .content(JacksonUtils.toJson(stale))).andExpect(jsonPath("$.code").value("500"));
        assertThat(groups.get(group.id()).revision()).isEqualTo(group.revision());
    }

    /** Controller 在转调 Service 前拦截空名称、未知状态和非法成员标识。 */
    @Test
    void create_invalidBody_returnsValidationError() throws Exception {
        mvc.perform(post("/api/user-groups").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\",\"name\":\"\",\"status\":\"UNKNOWN\",\"revision\":0,\"memberIds\":[-1]}"))
                .andExpect(jsonPath("$.code").value("500"));
    }

    /** 组装当前版本请求；首次调用生成独立编码。 */
    private UserGroupRequest request(String status, List<Long> memberIds) {
        return new UserGroupRequest(group == null ? "api-" + UUID.randomUUID() : group.code(),
                "测试组", "", status, group == null ? 0L : group.revision(), memberIds);
    }
}
