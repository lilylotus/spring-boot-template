<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getApprovalSwitch, updateApprovalSwitch } from '../api/approval-config'
import { listUsers } from '../api/user'
import ApprovalProcessDesigner from '../components/ApprovalProcessDesigner.vue'
import type { UserVO } from '../types/user'

interface DesignerHandle {
  canEnable: () => boolean
  hasUnsavedChanges: () => boolean
  load: () => Promise<void>
}

const approvalEnabled = ref(false)
const switchLoading = ref(true)
const switchUpdating = ref(false)
const switchError = ref('')
const users = ref<UserVO[]>([])
const usersLoading = ref(true)
const usersError = ref('')
const activeTab = ref('USER_CREATE')
const createDesigner = ref<DesignerHandle>()
const editDesigner = ref<DesignerHandle>()

async function loadSwitch() {
  switchLoading.value = true
  switchError.value = ''
  try {
    approvalEnabled.value = (await getApprovalSwitch()).approvalEnabled
  } catch {
    switchError.value = '审批开关加载失败，请重试。'
  } finally {
    switchLoading.value = false
  }
}

async function loadUsers() {
  usersLoading.value = true
  usersError.value = ''
  try {
    users.value = await listUsers()
  } catch {
    usersError.value = '用户列表加载失败，暂时无法配置审批人。'
  } finally {
    usersLoading.value = false
  }
}

async function beforeSwitchChange(): Promise<boolean> {
  if (switchUpdating.value) return false
  const enabling = !approvalEnabled.value
  if (enabling) {
    const designers = [createDesigner.value, editDesigner.value]
    if (designers.some((designer) => designer?.hasUnsavedChanges())) {
      ElMessage.warning('请先保存两类业务的流程草稿')
      return false
    }
    if (designers.some((designer) => !designer?.canEnable())) {
      ElMessage.warning('请先为用户新增和用户编辑发布有效流程')
      return false
    }
  } else {
    try {
      await ElMessageBox.confirm(
        '关闭后只影响新提交的业务，运行中的审批不会取消。',
        '确认关闭审批流程？',
        { confirmButtonText: '确认关闭', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return false
    }
  }

  switchUpdating.value = true
  try {
    await updateApprovalSwitch(enabling)
    ElMessage.success(enabling ? '审批流程已开启' : '审批流程已关闭')
    return true
  } catch {
    return false
  } finally {
    switchUpdating.value = false
  }
}

Promise.allSettled([loadSwitch(), loadUsers()])
</script>

<template>
  <section class="approval-settings">
    <header class="settings-status">
      <div>
        <p class="eyebrow">流程模板</p>
        <h1>审批配置</h1>
        <p class="description">绘制并保存草稿，确认无误后发布。新版本只影响之后发起的审批。</p>
      </div>
      <div class="switch-control">
        <div>
          <strong>审批流程</strong>
          <span>{{ approvalEnabled ? '已开启' : '已关闭' }}</span>
        </div>
        <el-switch
          v-model="approvalEnabled"
          :loading="switchLoading || switchUpdating"
          :disabled="switchLoading || Boolean(switchError)"
          :before-change="beforeSwitchChange"
          inline-prompt
          active-text="开"
          inactive-text="关"
        />
      </div>
    </header>

    <el-alert v-if="switchError" class="settings-alert" :title="switchError" type="error" :closable="false" show-icon>
      <template #default><el-button link type="primary" @click="loadSwitch">重新加载</el-button></template>
    </el-alert>
    <el-alert v-if="usersError" class="settings-alert" :title="usersError" type="error" :closable="false" show-icon>
      <template #default><el-button link type="primary" @click="loadUsers">重新加载</el-button></template>
    </el-alert>

    <el-tabs v-model="activeTab" class="template-tabs" type="border-card">
      <el-tab-pane label="用户新增流程" name="USER_CREATE">
        <ApprovalProcessDesigner
          ref="createDesigner"
          biz-type="USER_CREATE"
          title="用户新增审批"
          :users="users"
        />
      </el-tab-pane>
      <el-tab-pane label="用户编辑流程" name="USER_EDIT">
        <ApprovalProcessDesigner
          ref="editDesigner"
          biz-type="USER_EDIT"
          title="用户编辑审批"
          :users="users"
        />
      </el-tab-pane>
    </el-tabs>

    <p v-if="usersLoading" class="loading-tip">正在加载可选审批人…</p>
  </section>
</template>

<style scoped>
.approval-settings { width: min(1380px, 100%); margin: 0 auto; padding: 28px 24px 40px; }
.settings-status { display: flex; align-items: center; justify-content: space-between; gap: 28px; padding: 24px 28px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: #fff; box-shadow: 0 1px 3px rgb(31 45 61 / 5%); }
.eyebrow { margin: 0 0 6px; color: var(--el-color-primary); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.settings-status h1 { margin: 0 0 8px; font-size: 26px; }
.description { margin: 0; color: var(--el-text-color-secondary); font-size: 14px; }
.switch-control { display: flex; align-items: center; gap: 16px; padding: 12px 16px; border-radius: 6px; background: var(--el-fill-color-light); }
.switch-control > div { display: grid; gap: 2px; text-align: right; }
.switch-control strong { font-size: 14px; }
.switch-control span { color: var(--el-text-color-secondary); font-size: 12px; }
.settings-alert { margin-top: 16px; }
.template-tabs { margin-top: 20px; border-radius: 8px; }
.template-tabs :deep(.el-tabs__content) { padding: 18px; background: var(--el-fill-color-lighter); }
.loading-tip { color: var(--el-text-color-secondary); font-size: 13px; }
@media (max-width: 700px) { .approval-settings { padding: 20px 12px 32px; } .settings-status { align-items: stretch; flex-direction: column; padding: 20px; } .switch-control { justify-content: space-between; } .template-tabs :deep(.el-tabs__content) { padding: 8px; } }
</style>
