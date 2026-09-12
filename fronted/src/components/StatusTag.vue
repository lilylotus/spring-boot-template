<script setup lang="ts">
import { computed } from 'vue'
import type { UserStatus } from '../types/user'

/** 用户状态标签：把后端返回的状态码转换成中文展示。 */
const props = defineProps<{
  status: UserStatus
}>()

const STATUS_MAP: Record<UserStatus, { text: string; type: 'success' | 'warning' | 'danger' }> = {
  ACTIVE: { text: '已生效', type: 'success' },
  PENDING: { text: '待审批', type: 'warning' },
  REJECTED: { text: '已驳回', type: 'danger' },
}

const meta = computed(() => STATUS_MAP[props.status])
</script>

<template>
  <el-tag :type="meta.type">{{ meta.text }}</el-tag>
</template>
