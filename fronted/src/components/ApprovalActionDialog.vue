<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { actOnApprovalTask } from '../api/approval'
import type { ApprovalAction, ApprovalTask } from '../types/approval'

const props = defineProps<{
  modelValue: boolean
  task: ApprovalTask | null
  action: ApprovalAction
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  submitted: []
  failed: []
}>()

const comment = ref('')
const submitting = ref(false)
const commentInputRef = ref<{ focus: () => void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => {
    if (!value && submitting.value) {
      return
    }
    emit('update:modelValue', value)
  },
})

const isAgree = computed(() => props.action === 'AGREE')
const actionText = computed(() => (isAgree.value ? '同意' : '驳回'))

function resetDialog() {
  comment.value = ''
}

function handleOpened() {
  nextTick(() => commentInputRef.value?.focus())
}

function handleBeforeClose(done: () => void) {
  if (!submitting.value) {
    done()
  }
}

async function handleSubmit() {
  if (submitting.value || !props.task) {
    return
  }

  submitting.value = true
  try {
    await actOnApprovalTask({
      bizType: props.task.bizType,
      bizId: props.task.bizId,
      action: props.action,
      comment: comment.value.trim() || undefined,
    })
    ElMessage.success(props.task.groupId ? '操作已记录，请查看节点最新结果' : `已${actionText.value}该审批`)
    emit('update:modelValue', false)
    emit('submitted')
  } catch {
    // 统一 HTTP 层负责提示；保留意见与弹窗，方便修改后重试。
    emit('failed')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`${actionText}审批`"
    width="min(500px, calc(100vw - 32px))"
    :before-close="handleBeforeClose"
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @open="resetDialog"
    @opened="handleOpened"
    @closed="resetDialog"
  >
    <div v-if="task" class="approval-summary">
      <div class="approval-summary__title">{{ task.taskTitle }}</div>
      <div class="approval-summary__meta">
        <span>{{ task.bizType }}</span>
        <code>{{ task.bizId }}</code>
        <span>第 {{ task.level }} 级</span>
      </div>
    </div>

    <el-form label-position="top">
      <el-alert v-if="task?.groupId && !task.confirmOnly" title="用户组任一有效成员同意即通过；个人驳回后其他成员仍可审批，全部当前成员驳回才终止。" type="info" :closable="false" />
      <el-form-item label="审批意见（选填）">
        <el-input
          ref="commentInputRef"
          v-model="comment"
          type="textarea"
          :rows="4"
          maxlength="500"
          show-word-limit
          placeholder="填写本次审批意见"
          :disabled="submitting"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button :disabled="submitting" @click="visible = false">取消</el-button>
      <el-button
        :type="isAgree ? 'success' : 'danger'"
        :loading="submitting"
        @click="handleSubmit"
      >
        确认{{ actionText }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.approval-summary {
  margin-bottom: 20px;
  padding: 14px 16px;
  border-left: 3px solid var(--el-color-primary);
  border-radius: 0 6px 6px 0;
  background: var(--el-fill-color-light);
}

.approval-summary__title {
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.approval-summary__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.approval-summary__meta code {
  color: var(--el-text-color-regular);
  font-family: Consolas, 'SFMono-Regular', monospace;
}
</style>
