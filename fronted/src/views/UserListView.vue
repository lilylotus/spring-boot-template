<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listUsers } from '../api/user'
import type { UserVO } from '../types/user'
import StatusTag from '../components/StatusTag.vue'
import UserCreateDialog from '../components/UserCreateDialog.vue'
import UserEditDialog from '../components/UserEditDialog.vue'

/**
 * 用户列表页（/users）：展示用户基础信息与当前状态（已生效/待审批/已驳回），
 * 提供"新增"入口按钮与每行"编辑"入口。
 */

const users = ref<UserVO[]>([])
const loading = ref(false)
const createDialogVisible = ref(false)
const editDialogVisible = ref(false)
const editingUserId = ref<number | null>(null)

async function loadUsers() {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch {
    // 错误提示已由 http.ts 的响应拦截器统一处理，这里不重复弹窗。
  } finally {
    loading.value = false
  }
}

function openEditDialog(row: UserVO) {
  editingUserId.value = row.id
  editDialogVisible.value = true
}

onMounted(loadUsers)
</script>

<template>
  <div class="user-list-view">
    <div class="toolbar">
      <h2>用户管理</h2>
      <el-button type="primary" @click="createDialogVisible = true">新增用户</el-button>
    </div>

    <el-table :data="users" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="username" label="账号" width="160" />
      <el-table-column prop="realName" label="姓名" width="160" />
      <el-table-column prop="mobile" label="手机号" width="160" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <StatusTag :status="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="待审批变更" min-width="160">
        <template #default="{ row }">
          <span v-if="row.pendingChange">存在待审批的编辑变更</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openEditDialog(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <UserCreateDialog v-model="createDialogVisible" @created="loadUsers" />
    <UserEditDialog
      v-model="editDialogVisible"
      :user-id="editingUserId"
      @updated="loadUsers"
    />
  </div>
</template>

<style scoped>
.user-list-view {
  padding: 24px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.toolbar h2 {
  margin: 0;
}
</style>
