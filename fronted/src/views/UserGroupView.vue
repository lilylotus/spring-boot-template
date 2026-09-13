<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listUserGroups, saveUserGroup } from '../api/user-group'
import type { UserGroup, UserGroupInput } from '../api/user-group'
import { listUsers } from '../api/user'
import type { UserVO } from '../types/user'

const groups = ref<UserGroup[]>([])
const users = ref<UserVO[]>([])
const loading = ref(false)
const saving = ref(false)
const failed = ref(false)
const visible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<UserGroupInput>({
  code: '', name: '', description: '', status: 'ACTIVE', revision: 0, memberIds: [],
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    const [newGroups, newUsers] = await Promise.all([listUserGroups(), listUsers()])
    groups.value = newGroups
    users.value = newUsers
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

function edit(group?: UserGroup) {
  editingId.value = group?.id ?? null
  Object.assign(form, group ? { ...group, memberIds: [...group.memberIds] } : {
    code: '', name: '', description: '', status: 'ACTIVE', revision: 0, memberIds: [],
  })
  visible.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('请填写用户组编码和名称')
    return
  }
  saving.value = true
  try {
    await saveUserGroup(editingId.value, form)
    visible.value = false
    ElMessage.success('用户组已保存，进行中的组审批将使用最新成员')
    await load()
  } catch {
    // 请求失败保留输入和成员选择，HTTP层负责具体错误提示。
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="groups-page">
    <header>
      <div><h1>用户组管理</h1><p>维护审批用户组，成员变更即时影响进行中的组审批。</p></div>
      <div>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :disabled="loading || failed" @click="edit()">新增用户组</el-button>
      </div>
    </header>
    <el-alert v-if="failed" type="error" title="加载失败，请点击刷新重试" :closable="false" />
    <el-table v-loading="loading" :data="groups" empty-text="暂无用户组">
      <el-table-column prop="code" label="编码" min-width="130" />
      <el-table-column prop="name" label="名称" min-width="150" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="成员" width="150">
        <template #default="{ row }">{{ row.memberIds.length }} 人 / 有效 {{ row.activeMemberIds.length }} 人</template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="160" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }"><el-button link type="primary" :disabled="failed" @click="edit(row)">编辑 / 维护成员</el-button></template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="editingId ? '编辑用户组' : '新增用户组'" width="min(600px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-position="top">
        <el-form-item label="编码（创建后不可修改）" required><el-input v-model="form.code" :disabled="editingId !== null || saving" maxlength="64" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" :disabled="saving" maxlength="128" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" :disabled="saving" type="textarea" maxlength="500" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" :disabled="saving"><el-option value="ACTIVE" label="启用" /><el-option value="DISABLED" label="停用" /></el-select>
        </el-form-item>
        <el-form-item label="成员">
          <el-select v-model="form.memberIds" multiple filterable :disabled="saving" placeholder="选择有效用户" style="width:100%">
            <el-option v-for="user in users" :key="user.id" :value="user.id" :disabled="user.status !== 'ACTIVE'" :label="user.realName + '（' + user.username + '）' + (user.status === 'ACTIVE' ? '' : ' [已失效]')" />
          </el-select>
        </el-form-item>
        <el-alert type="info" :closable="false" title="移出成员后该用户立即失去组审批资格；空组或停用组的进行中节点将保持待处理。" />
      </el-form>
      <template #footer><el-button :disabled="saving" @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.groups-page { max-width: 1200px; padding: 28px 20px; margin: auto; }
header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
header p { color: var(--el-text-color-secondary); }
@media (max-width: 650px) { header { align-items: start; flex-direction: column; } }
</style>
