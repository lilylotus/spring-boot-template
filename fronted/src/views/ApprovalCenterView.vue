<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { listApprovalRecords, listPendingApprovalTasks } from '../api/approval'
import { listUsers } from '../api/user'
import ApprovalActionDialog from '../components/ApprovalActionDialog.vue'
import ApprovalDetailDialog from '../components/ApprovalDetailDialog.vue'
import { useOperator } from '../composables/useOperator'
import type { ApprovalAction, ApprovalRecord, ApprovalTask } from '../types/approval'
import type { UserVO } from '../types/user'

const { operator } = useOperator()

const activeTab = ref('pending')
const pendingTasks = ref<ApprovalTask[]>([])
const approvalRecords = ref<ApprovalRecord[]>([])
const pendingLoading = ref(false)
const recordsLoading = ref(false)
const actionDialogVisible = ref(false)
const selectedTask = ref<ApprovalTask | null>(null)
const selectedAction = ref<ApprovalAction>('AGREE')
const users = ref<UserVO[]>([])
const detailDialogVisible = ref(false)
const selectedApprovalId = ref<number | null>(null)
let requestSequence = 0

const pendingTabLabel = computed(() => `待我审批 ${pendingTasks.value.length}`)

function bizTypeLabel(bizType: string): string {
  const labels: Record<string, string> = {
    USER_CREATE: '新增用户',
    USER_EDIT: '编辑用户',
  }
  return labels[bizType] ?? bizType
}

function approvalUserName(bizType: string, bizId: string): string {
  if (bizType !== 'USER_CREATE' && bizType !== 'USER_EDIT') {
    return bizId
  }

  const user = users.value.find((item) => String(item.id) === bizId)
  return user?.realName?.trim() || bizId
}

function actionLabel(action: ApprovalAction): string {
  return action === 'AGREE' ? '同意' : '驳回'
}

async function loadApprovalData() {
  const currentSequence = ++requestSequence
  pendingTasks.value = []
  approvalRecords.value = []
  pendingLoading.value = true
  recordsLoading.value = true

  const pendingRequest = listPendingApprovalTasks()
    .then((tasks) => {
      if (currentSequence === requestSequence) {
        pendingTasks.value = tasks
      }
    })
    .catch(() => {
      // 统一 HTTP 层提示；当前操作人的表格保持为空。
    })
    .finally(() => {
      if (currentSequence === requestSequence) {
        pendingLoading.value = false
      }
    })

  const recordsRequest = listApprovalRecords()
    .then((records) => {
      if (currentSequence === requestSequence) {
        approvalRecords.value = records
      }
    })
    .catch(() => {
      // 统一 HTTP 层提示；当前操作人的表格保持为空。
    })
    .finally(() => {
      if (currentSequence === requestSequence) {
        recordsLoading.value = false
      }
    })

  await Promise.allSettled([pendingRequest, recordsRequest])
}

function openActionDialog(task: ApprovalTask, action: ApprovalAction) {
  selectedTask.value = task
  selectedAction.value = action
  actionDialogVisible.value = true
}

function openDetail(approvalId: number) {
  selectedApprovalId.value = approvalId
  detailDialogVisible.value = true
}

async function loadUsersForDisplay() {
  try {
    users.value = await listUsers()
  } catch {
    // 详情弹窗无法映射的用户会回退显示用户 ID。
  }
}

function handleActionSubmitted() {
  selectedTask.value = null
  loadApprovalData()
}

watch(
  () => operator.value.id,
  () => {
    actionDialogVisible.value = false
    detailDialogVisible.value = false
    selectedTask.value = null
    selectedApprovalId.value = null
    loadApprovalData()
  },
  { immediate: true },
)

loadUsersForDisplay()
</script>

<template>
  <section class="approval-center">
    <header class="approval-center__intro">
      <div>
        <h1>审批中心</h1>
        <p>查看并处理当前操作人的审批任务，处理结果会保留在审批记录中。</p>
      </div>
      <div class="operator-context" aria-label="当前审批身份">
        <span class="operator-context__dot" />
        <span>当前队列</span>
        <strong>{{ operator.name }}</strong>
      </div>
    </header>

    <div class="approval-panel">
      <el-tabs v-model="activeTab" class="approval-tabs">
        <el-tab-pane :label="pendingTabLabel" name="pending">
          <el-table
            v-loading="pendingLoading"
            :data="pendingTasks"
            empty-text="当前没有待审批任务"
            stripe
          >
            <el-table-column label="业务类型" width="120">
              <template #default="{ row }">{{ bizTypeLabel(row.bizType) }}</template>
            </el-table-column>
            <el-table-column label="审批用户" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                {{ approvalUserName(row.bizType, row.bizId) }}
              </template>
            </el-table-column>
            <el-table-column prop="level" label="级别" width="88">
              <template #default="{ row }">第 {{ row.level }} 级</template>
            </el-table-column>
            <el-table-column prop="taskTitle" label="任务标题" min-width="180" show-overflow-tooltip />
            <el-table-column prop="createdTime" label="到达时间" width="180" />
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button link @click="openDetail(row.approvalId)">详情</el-button>
                <el-button type="success" link @click="openActionDialog(row, 'AGREE')">同意</el-button>
                <el-button type="danger" link @click="openActionDialog(row, 'REJECT')">驳回</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="审批记录" name="records">
          <el-table
            v-loading="recordsLoading"
            :data="approvalRecords"
            empty-text="暂无审批记录"
            stripe
          >
            <el-table-column label="业务类型" width="120">
              <template #default="{ row }">{{ bizTypeLabel(row.bizType) }}</template>
            </el-table-column>
            <el-table-column label="审批用户" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">
                {{ approvalUserName(row.bizType, row.bizId) }}
              </template>
            </el-table-column>
            <el-table-column prop="level" label="级别" width="88">
              <template #default="{ row }">第 {{ row.level }} 级</template>
            </el-table-column>
            <el-table-column prop="taskTitle" label="任务标题" min-width="170" show-overflow-tooltip />
            <el-table-column label="结果" width="88">
              <template #default="{ row }">
                <el-tag :type="row.action === 'AGREE' ? 'success' : 'danger'" effect="light">
                  {{ actionLabel(row.action) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="comment" label="审批意见" min-width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.comment || '-' }}</template>
            </el-table-column>
            <el-table-column prop="taskCreatedTime" label="到达时间" width="180" />
            <el-table-column prop="actedTime" label="处理时间" width="180" />
            <el-table-column label="操作" width="76" fixed="right">
              <template #default="{ row }">
                <el-button link @click="openDetail(row.approvalId)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <ApprovalActionDialog
      v-model="actionDialogVisible"
      :task="selectedTask"
      :action="selectedAction"
      @submitted="handleActionSubmitted"
    />
    <ApprovalDetailDialog
      v-model="detailDialogVisible"
      :approval-id="selectedApprovalId"
      :users="users"
    />
  </section>
</template>

<style scoped>
.approval-center {
  width: min(1360px, 100%);
  margin: 0 auto;
  padding: 28px 24px;
}

.approval-center__intro {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}

.approval-center__intro h1 {
  margin: 0 0 8px;
  color: #303133;
  font-size: 26px;
  line-height: 1.25;
}

.approval-center__intro p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.operator-context {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.operator-context strong {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.operator-context__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #67c23a;
  box-shadow: 0 0 0 3px #e7f6df;
}

.approval-panel {
  overflow: hidden;
  padding: 0 20px 20px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 3px rgb(31 45 61 / 5%);
}

.approval-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.approval-tabs :deep(.el-tabs__item) {
  height: 52px;
  font-weight: 500;
}

@media (max-width: 700px) {
  .approval-center {
    padding: 20px 12px;
  }

  .approval-center__intro {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .approval-panel {
    padding-right: 12px;
    padding-left: 12px;
  }
}
</style>
