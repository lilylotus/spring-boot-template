<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { getApprovalDetail } from '../api/approval'
import type {
  ApprovalDetail,
  ApprovalNodeStatus,
  ApprovalStatus,
  UserApprovalFields,
  UserApprovalPayload,
} from '../types/approval'
import type { UserVO } from '../types/user'

type PayloadPresentation =
  | { kind: 'unavailable' }
  | { kind: 'empty' }
  | { kind: 'create'; after: UserApprovalFields }
  | { kind: 'edit'; before: UserApprovalFields; after: UserApprovalFields }
  | { kind: 'legacy'; data: UserApprovalFields }
  | { kind: 'raw'; text: string }

const props = defineProps<{
  modelValue: boolean
  approvalId: number | null
  users: UserVO[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const detail = ref<ApprovalDetail | null>(null)
const loading = ref(false)
const error = ref('')
const headingRef = ref<HTMLElement>()
let requestSequence = 0

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const fieldDefinitions: Array<{ key: keyof UserApprovalFields; label: string }> = [
  { key: 'username', label: '账号' },
  { key: 'realName', label: '姓名' },
  { key: 'mobile', label: '手机号' },
  { key: 'email', label: '邮箱' },
]

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isUserFields(value: unknown): value is UserApprovalFields {
  if (!isObject(value)) {
    return false
  }
  return fieldDefinitions.some(({ key }) => Object.prototype.hasOwnProperty.call(value, key))
}

function parsePayload(current: ApprovalDetail | null): PayloadPresentation {
  if (!current?.payloadAvailable) {
    return { kind: 'unavailable' }
  }
  const source = current.payloadSnapshot
  if (!source?.trim()) {
    return { kind: 'empty' }
  }

  try {
    const parsed: unknown = JSON.parse(source)
    if (
      isObject(parsed) &&
      Object.prototype.hasOwnProperty.call(parsed, 'after') &&
      isUserFields(parsed.after) &&
      (parsed.before === null || isUserFields(parsed.before))
    ) {
      const payload = parsed as unknown as UserApprovalPayload
      if (current.bizType === 'USER_CREATE' && payload.before === null) {
        return { kind: 'create', after: payload.after }
      }
      if (current.bizType === 'USER_EDIT' && payload.before) {
        return { kind: 'edit', before: payload.before, after: payload.after }
      }
    }
    if (isUserFields(parsed)) {
      return { kind: 'legacy', data: parsed }
    }
    return { kind: 'raw', text: JSON.stringify(parsed, null, 2) }
  } catch {
    return { kind: 'raw', text: source }
  }
}

const payload = computed(() => parsePayload(detail.value))

function bizTypeLabel(bizType?: string): string {
  if (bizType === 'USER_CREATE') return '新增用户'
  if (bizType === 'USER_EDIT') return '编辑用户'
  return bizType || '审批'
}

function statusLabel(status: ApprovalStatus): string {
  const labels: Record<ApprovalStatus, string> = {
    PENDING: '进行中',
    APPROVED: '已通过',
    REJECTED: '已驳回',
  }
  return labels[status]
}

function statusTagType(status: ApprovalStatus): 'primary' | 'success' | 'danger' {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'primary'
}

function nodeStatusLabel(status: ApprovalNodeStatus): string {
  const labels: Record<ApprovalNodeStatus, string> = {
    APPROVED: '已同意',
    REJECTED: '已驳回',
    PENDING: '待审批',
    WAITING: '未开始',
    SKIPPED: '已跳过',
  }
  return labels[status]
}

function actionLabel(action: 'AGREE' | 'REJECT'): string {
  return action === 'AGREE' ? '同意' : '驳回'
}

function userLabel(userId?: string | null): string {
  if (!userId) return '历史数据不可用'
  const user = props.users.find((item) => String(item.id) === userId)
  return user ? `${user.realName}（${user.username}）` : `用户 ID ${userId}`
}

function fieldValue(fields: UserApprovalFields, key: keyof UserApprovalFields): string {
  const value = fields[key]
  return value === null || value === undefined || value === '' ? '-' : String(value)
}

async function loadDetail() {
  const approvalId = props.approvalId
  if (!approvalId) {
    return
  }
  const currentSequence = ++requestSequence
  detail.value = null
  error.value = ''
  loading.value = true
  try {
    const result = await getApprovalDetail(approvalId)
    if (currentSequence === requestSequence && props.modelValue) {
      detail.value = result
    }
  } catch {
    if (currentSequence === requestSequence && props.modelValue) {
      error.value = '审批详情加载失败，请重试。'
    }
  } finally {
    if (currentSequence === requestSequence) {
      loading.value = false
    }
  }
}

function handleOpened() {
  nextTick(() => headingRef.value?.focus())
}

function handleClosed() {
  ++requestSequence
  detail.value = null
  error.value = ''
  loading.value = false
}

watch(
  () => [props.modelValue, props.approvalId] as const,
  ([isVisible]) => {
    if (isVisible) {
      loadDetail()
    } else {
      ++requestSequence
    }
  },
)
</script>

<template>
  <el-dialog
    v-model="visible"
    width="min(920px, calc(100vw - 24px))"
    class="approval-detail-dialog"
    destroy-on-close
    @opened="handleOpened"
    @closed="handleClosed"
  >
    <template #header>
      <div ref="headingRef" class="detail-heading" tabindex="-1">
        <span>{{ bizTypeLabel(detail?.bizType) }} · 审批详情</span>
        <code v-if="detail">#{{ detail.approvalId }}</code>
      </div>
    </template>

    <div v-loading="loading" class="detail-body">
      <el-result v-if="error" icon="error" title="无法加载审批详情" :sub-title="error">
        <template #extra>
          <el-button type="primary" @click="loadDetail">重新加载</el-button>
        </template>
      </el-result>

      <template v-else-if="detail">
        <section class="detail-conclusion" :class="`is-${detail.status.toLowerCase()}`">
          <div>
            <el-tag :type="statusTagType(detail.status)" effect="dark">
              {{ statusLabel(detail.status) }}
            </el-tag>
            <strong v-if="detail.status === 'PENDING' && detail.currentLevel">
              当前第 {{ detail.currentLevel }} 级
            </strong>
            <strong v-else-if="detail.status === 'APPROVED'">全部审批节点已通过</strong>
            <strong v-else>流程已在驳回节点结束</strong>
          </div>
          <dl class="detail-meta">
            <div><dt>业务标识</dt><dd><code>{{ detail.bizId }}</code></dd></div>
            <div><dt>发起人</dt><dd>{{ userLabel(detail.initiatorUserId) }}</dd></div>
            <div><dt>发起时间</dt><dd>{{ detail.submittedTime || '历史数据不可用' }}</dd></div>
            <div><dt>流程模板</dt><dd>{{ detail.templateName || '历史数据不可用' }}</dd></div>
            <div><dt>模板版本</dt><dd>{{ detail.templateVersionNo ? `v${detail.templateVersionNo}` : '历史数据不可用' }}</dd></div>
          </dl>
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <h3>审批数据</h3>
            <span>本次申请提交时的数据快照</span>
          </div>

          <el-alert
            v-if="payload.kind === 'unavailable'"
            title="历史数据不可用"
            description="该审批创建较早，且流程历史中已无法恢复本次申请的数据快照。"
            type="warning"
            show-icon
            :closable="false"
          />
          <el-empty
            v-else-if="payload.kind === 'empty'"
            :image-size="64"
            description="本次审批未提供数据"
          />

          <div v-else-if="payload.kind === 'edit'" class="payload-table payload-table--compare">
            <div class="payload-table__head"><span>字段</span><span>修改前</span><span>修改后</span></div>
            <div v-for="field in fieldDefinitions" :key="field.key" class="payload-table__row">
              <strong>{{ field.label }}</strong>
              <span>{{ fieldValue(payload.before, field.key) }}</span>
              <span :class="{ changed: fieldValue(payload.before, field.key) !== fieldValue(payload.after, field.key) }">
                {{ fieldValue(payload.after, field.key) }}
              </span>
            </div>
          </div>

          <div
            v-else-if="payload.kind === 'create' || payload.kind === 'legacy'"
            class="payload-table payload-table--single"
          >
            <div class="payload-table__head"><span>字段</span><span>申请数据</span></div>
            <div v-for="field in fieldDefinitions" :key="field.key" class="payload-table__row">
              <strong>{{ field.label }}</strong>
              <span>{{ fieldValue(payload.kind === 'create' ? payload.after : payload.data, field.key) }}</span>
            </div>
          </div>

          <pre v-else class="raw-payload">{{ payload.text }}</pre>
        </section>

        <section class="detail-section">
          <div class="section-heading">
            <h3>完整审批流程</h3>
            <span>流程按发起版本展示，用户组成员实时读取，历史动作永久保留</span>
          </div>

          <el-empty v-if="detail.nodes.length === 0" :image-size="64" description="历史流程节点不可用" />
          <ol v-else class="process-track">
            <li
              v-for="node in detail.nodes"
              :key="`${node.level}-${node.approverUserId}`"
              class="process-node"
              :class="`is-${node.status.toLowerCase()}`"
            >
              <div class="process-node__marker" aria-hidden="true">{{ node.level }}</div>
              <div class="process-node__content">
                <div class="process-node__topline">
                  <div>
                    <strong>第 {{ node.level }} 级 · {{ node.groupId ? '用户组：' + node.groupName : userLabel(node.approverUserId) }}</strong>
                    <span v-if="node.taskTitle">{{ node.taskTitle }}</span>
                  </div>
                  <el-tag
                    :type="node.status === 'APPROVED' ? 'success' : node.status === 'REJECTED' ? 'danger' : node.status === 'PENDING' ? 'primary' : 'info'"
                    effect="light"
                  >{{ nodeStatusLabel(node.status) }}</el-tag>
                </div>
                <div v-if="node.status === 'PENDING'" class="current-point">当前审批点</div>
                <template v-if="node.groupId">
                  <p>当前有效成员：{{ node.currentMemberIds?.map(id => userLabel(id)).join('、') || '暂无（节点不可处理）' }}</p>
                  <p v-for="vote in node.memberActions" :key="vote.userId">
                    {{ userLabel(vote.userId) }} · {{ actionLabel(vote.action) }} · {{ vote.comment || '无意见' }} · {{ vote.actedTime }}
                    <span v-if="vote.groupName">（处理时用户组：{{ vote.groupName }}）</span>
                  </p>
                </template>
                <dl class="node-meta">
                  <div v-if="node.action"><dt>审批动作</dt><dd>{{ actionLabel(node.action) }}</dd></div>
                  <div v-if="node.comment"><dt>审批意见</dt><dd>{{ node.comment }}</dd></div>
                  <div v-if="node.taskCreatedTime"><dt>到达时间</dt><dd>{{ node.taskCreatedTime }}</dd></div>
                  <div v-if="node.actedTime"><dt>处理时间</dt><dd>{{ node.actedTime }}</dd></div>
                </dl>
              </div>
            </li>
          </ol>
        </section>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.detail-heading {
  display: flex;
  align-items: baseline;
  gap: 10px;
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
  outline: none;
}

.detail-heading:focus-visible {
  border-radius: 4px;
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 3px;
}

.detail-heading code {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.detail-body {
  min-height: 260px;
}

.detail-conclusion {
  padding: 18px 20px;
  border: 1px solid var(--el-border-color-lighter);
  border-left: 4px solid var(--el-color-primary);
  border-radius: 6px;
  background: var(--el-color-primary-light-9);
}

.detail-conclusion.is-approved {
  border-left-color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.detail-conclusion.is-rejected {
  border-left-color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.detail-conclusion > div:first-child {
  display: flex;
  align-items: center;
  gap: 12px;
}

.detail-meta,
.node-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 24px;
  margin: 16px 0 0;
}

.detail-meta div,
.node-meta div {
  display: flex;
  gap: 7px;
}

.detail-meta dt,
.node-meta dt {
  color: var(--el-text-color-secondary);
}

.detail-meta dd,
.node-meta dd {
  margin: 0;
  color: var(--el-text-color-regular);
}

.detail-meta,
.node-meta {
  font-size: 13px;
}

.detail-meta code {
  font-family: Consolas, 'SFMono-Regular', monospace;
}

.detail-section {
  margin-top: 26px;
}

.section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 13px;
}

.section-heading h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 16px;
}

.section-heading span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.payload-table {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.payload-table__head,
.payload-table__row {
  display: grid;
  gap: 16px;
  padding: 11px 14px;
}

.payload-table--compare .payload-table__head,
.payload-table--compare .payload-table__row {
  grid-template-columns: minmax(72px, 0.6fr) repeat(2, minmax(0, 1fr));
}

.payload-table--single .payload-table__head,
.payload-table--single .payload-table__row {
  grid-template-columns: minmax(90px, 0.6fr) minmax(0, 2fr);
}

.payload-table__head {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.payload-table__row + .payload-table__row {
  border-top: 1px solid var(--el-border-color-lighter);
}

.payload-table__row {
  color: var(--el-text-color-regular);
  font-size: 14px;
  overflow-wrap: anywhere;
}

.payload-table__row strong {
  font-weight: 500;
}

.payload-table__row .changed {
  color: var(--el-color-primary);
  font-weight: 600;
}

.raw-payload {
  overflow: auto;
  max-height: 300px;
  margin: 0;
  padding: 14px;
  border-radius: 6px;
  background: #f5f7fa;
  color: var(--el-text-color-regular);
  font: 13px/1.6 Consolas, 'SFMono-Regular', monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.process-track {
  margin: 0;
  padding: 0;
  list-style: none;
}

.process-node {
  position: relative;
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr);
  gap: 12px;
  padding-bottom: 22px;
}

.process-node:last-child {
  padding-bottom: 0;
}

.process-node::before {
  position: absolute;
  top: 32px;
  bottom: 0;
  left: 17px;
  width: 2px;
  background: var(--el-border-color-light);
  content: '';
}

.process-node:last-child::before {
  display: none;
}

.process-node__marker {
  z-index: 1;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 2px solid var(--el-border-color);
  border-radius: 50%;
  background: #fff;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 700;
}

.process-node.is-approved .process-node__marker {
  border-color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.process-node.is-rejected .process-node__marker {
  border-color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.process-node.is-pending .process-node__marker {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  box-shadow: 0 0 0 4px var(--el-color-primary-light-9);
}

.process-node.is-skipped .process-node__marker {
  border-style: dashed;
}

.process-node__content {
  min-width: 0;
  padding-top: 2px;
}

.process-node__topline {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.process-node__topline > div {
  display: grid;
  gap: 4px;
}

.process-node__topline strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.process-node__topline span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.current-point {
  margin-top: 8px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
}

.node-meta {
  margin-top: 8px;
}

@media (max-width: 620px) {
  .detail-conclusion {
    padding: 15px;
  }

  .detail-conclusion > div:first-child,
  .section-heading,
  .process-node__topline {
    align-items: flex-start;
    flex-direction: column;
  }

  .detail-meta,
  .node-meta {
    display: grid;
    gap: 7px;
  }

  .payload-table--compare .payload-table__head {
    display: none;
  }

  .payload-table--compare .payload-table__row {
    grid-template-columns: 1fr 1fr;
    gap: 8px 12px;
    padding: 13px;
  }

  .payload-table--compare .payload-table__row strong {
    grid-column: 1 / -1;
  }

  .payload-table--compare .payload-table__row span::before {
    display: block;
    margin-bottom: 3px;
    color: var(--el-text-color-secondary);
    font-size: 11px;
  }

  .payload-table--compare .payload-table__row span:nth-child(2)::before {
    content: '修改前';
  }

  .payload-table--compare .payload-table__row span:nth-child(3)::before {
    content: '修改后';
  }
}
</style>
