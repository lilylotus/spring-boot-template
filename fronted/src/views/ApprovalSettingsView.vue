<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getApprovalSwitch,
  listApprovalChain,
  saveApprovalChain,
  updateApprovalSwitch,
} from '../api/approval-config'
import { listUsers } from '../api/user'
import type { ApprovalBizType, ApprovalChainLevel } from '../types/approval-config'
import type { UserVO } from '../types/user'

interface EditableLevel {
  key: number
  approverUserId: string
}

interface ChainState {
  bizType: ApprovalBizType
  title: string
  description: string
  levels: EditableLevel[]
  savedApproverIds: string[]
  loading: boolean
  saving: boolean
  error: string
}

let nextLevelKey = 1

function createChainState(
  bizType: ApprovalBizType,
  title: string,
  description: string,
): ChainState {
  return reactive({
    bizType,
    title,
    description,
    levels: [],
    savedApproverIds: [],
    loading: true,
    saving: false,
    error: '',
  })
}

const approvalEnabled = ref(false)
const switchLoading = ref(true)
const switchUpdating = ref(false)
const switchError = ref('')
const users = ref<UserVO[]>([])
const usersLoading = ref(true)
const usersError = ref('')
const createCardRef = ref<HTMLElement>()
const editCardRef = ref<HTMLElement>()

const createChain = createChainState(
  'USER_CREATE',
  '用户新增审批链',
  '新用户提交后，按以下顺序逐级审批。',
)
const editChain = createChainState(
  'USER_EDIT',
  '用户编辑审批链',
  '用户资料修改后，按以下顺序逐级审批。',
)
const chains = [createChain, editChain]

const activeUsers = computed(() => users.value.filter((user) => user.status === 'ACTIVE'))

function isDirty(chain: ChainState): boolean {
  if (chain.levels.length !== chain.savedApproverIds.length) {
    return true
  }
  return chain.levels.some(
    (level, index) => level.approverUserId !== chain.savedApproverIds[index],
  )
}

function findUser(userId: string): UserVO | undefined {
  return users.value.find((user) => String(user.id) === userId)
}

function isValidApprover(userId: string): boolean {
  return Boolean(userId) && findUser(userId)?.status === 'ACTIVE'
}

function invalidApproverLabel(userId: string): string {
  const user = findUser(userId)
  return user
    ? `${user.realName}（${user.username}，已失效）`
    : `用户 ID ${userId || '未选择'}（已失效）`
}

function chainIsComplete(chain: ChainState): boolean {
  return (
    !chain.loading &&
    !chain.error &&
    !usersError.value &&
    chain.levels.length > 0 &&
    chain.levels.every((level) => isValidApprover(level.approverUserId))
  )
}

function setLoadedLevels(chain: ChainState, levels: ApprovalChainLevel[]) {
  const ordered = [...levels].sort((left, right) => left.levelNo - right.levelNo)
  const approverIds = ordered.map((level) => level.approverUserId)
  chain.levels = approverIds.map((approverUserId) => ({
    key: nextLevelKey++,
    approverUserId,
  }))
  chain.savedApproverIds = [...approverIds]
}

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

async function loadUsersForSelection() {
  usersLoading.value = true
  usersError.value = ''
  try {
    users.value = await listUsers()
  } catch {
    users.value = []
    usersError.value = '审批人列表加载失败，暂时无法保存审批链。'
  } finally {
    usersLoading.value = false
  }
}

async function loadChain(chain: ChainState, preserveOnFailure = false) {
  chain.loading = true
  chain.error = ''
  try {
    setLoadedLevels(chain, await listApprovalChain(chain.bizType))
  } catch {
    if (!preserveOnFailure) {
      chain.levels = []
      chain.savedApproverIds = []
    }
    chain.error = '审批链加载失败，请重试。'
  } finally {
    chain.loading = false
  }
}

function addLevel(chain: ChainState) {
  chain.levels.push({ key: nextLevelKey++, approverUserId: '' })
}

function removeLevel(chain: ChainState, index: number) {
  chain.levels.splice(index, 1)
}

function moveLevel(chain: ChainState, index: number, offset: -1 | 1) {
  const targetIndex = index + offset
  if (targetIndex < 0 || targetIndex >= chain.levels.length) {
    return
  }
  const [level] = chain.levels.splice(index, 1)
  if (level) {
    chain.levels.splice(targetIndex, 0, level)
  }
}

async function saveChain(chain: ChainState) {
  if (chain.saving || !isDirty(chain)) {
    return
  }
  if (chain.levels.length === 0) {
    ElMessage.warning(`${chain.title}至少需要一级审批人`)
    return
  }
  if (usersError.value || chain.levels.some((level) => !isValidApprover(level.approverUserId))) {
    ElMessage.warning('请为每一级选择当前有效的审批人')
    return
  }

  chain.saving = true
  try {
    await saveApprovalChain(
      chain.bizType,
      chain.levels.map((level, index) => ({
        levelNo: index + 1,
        approverUserId: level.approverUserId,
      })),
    )
    ElMessage.success(`${chain.title}已保存`)
    await loadChain(chain, true)
  } catch {
    // 统一 HTTP 层负责提示，当前编辑内容保持不变。
  } finally {
    chain.saving = false
  }
}

async function focusChain(chain: ChainState) {
  await nextTick()
  const card = chain.bizType === 'USER_CREATE' ? createCardRef.value : editCardRef.value
  card?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  card?.focus({ preventScroll: true })
}

async function beforeSwitchChange(): Promise<boolean> {
  if (switchUpdating.value) {
    return false
  }

  const enabling = !approvalEnabled.value
  if (enabling) {
    const dirtyChain = chains.find((chain) => isDirty(chain))
    if (dirtyChain) {
      ElMessage.warning(`请先保存${dirtyChain.title}`)
      await focusChain(dirtyChain)
      return false
    }
    const incompleteChain = chains.find((chain) => !chainIsComplete(chain))
    if (incompleteChain) {
      ElMessage.warning(`请先完整配置${incompleteChain.title}`)
      await focusChain(incompleteChain)
      return false
    }
  } else {
    try {
      await ElMessageBox.confirm(
        '关闭后，仅影响后续新提交的用户新增与编辑，进行中的审批不会取消。',
        '确认关闭审批流程？',
        {
          confirmButtonText: '确认关闭',
          cancelButtonText: '取消',
          type: 'warning',
        },
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
    // before-change 返回 false，开关仍保持原值；HTTP 层负责错误提示。
    return false
  } finally {
    switchUpdating.value = false
  }
}

Promise.allSettled([
  loadSwitch(),
  loadUsersForSelection(),
  loadChain(createChain),
  loadChain(editChain),
])
</script>

<template>
  <section class="approval-settings">
    <header class="settings-status">
      <div>
        <p class="settings-status__eyebrow">流程控制</p>
        <h1>审批配置</h1>
        <p class="settings-status__description">
          先配置两条顺序审批链，再决定后续用户新增与编辑是否进入审批。
        </p>
      </div>
      <div class="switch-control">
        <div class="switch-control__copy">
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

    <el-alert
      v-if="switchError"
      class="settings-alert"
      :title="switchError"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default><el-button link type="primary" @click="loadSwitch">重新加载</el-button></template>
    </el-alert>
    <el-alert
      v-if="usersError"
      class="settings-alert"
      :title="usersError"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default>
        <el-button link type="primary" @click="loadUsersForSelection">重新加载</el-button>
      </template>
    </el-alert>

    <div class="chain-grid">
      <article
        v-for="chain in chains"
        :key="chain.bizType"
        :ref="(element) => {
          if (chain.bizType === 'USER_CREATE') createCardRef = element as HTMLElement
          else editCardRef = element as HTMLElement
        }"
        class="chain-card"
        tabindex="-1"
      >
        <header class="chain-card__header">
          <div>
            <h2>{{ chain.title }}</h2>
            <p>{{ chain.description }}</p>
          </div>
          <el-tag v-if="chain.loading" type="info" effect="plain">加载中</el-tag>
          <el-tag v-else-if="chain.error" type="danger" effect="plain">加载失败</el-tag>
          <el-tag v-else-if="isDirty(chain)" type="warning" effect="plain">未保存</el-tag>
          <el-tag v-else type="info" effect="plain">已同步</el-tag>
        </header>

        <el-alert
          v-if="chain.error"
          :title="chain.error"
          type="error"
          show-icon
          :closable="false"
        >
          <template #default>
            <el-button link type="primary" @click="loadChain(chain)">重新加载</el-button>
          </template>
        </el-alert>

        <div v-loading="chain.loading" class="chain-track">
          <el-empty
            v-if="!chain.loading && !chain.error && chain.levels.length === 0"
            :image-size="72"
            description="尚未配置审批级别"
          />

          <div
            v-for="(level, index) in chain.levels"
            :key="level.key"
            class="chain-level"
          >
            <div class="chain-level__rail" aria-hidden="true">
              <span>{{ index + 1 }}</span>
            </div>
            <div class="chain-level__content">
              <label :for="`${chain.bizType}-${level.key}`">第 {{ index + 1 }} 级审批人</label>
              <el-select
                :id="`${chain.bizType}-${level.key}`"
                v-model="level.approverUserId"
                filterable
                :loading="usersLoading"
                :disabled="chain.saving || usersLoading || Boolean(usersError)"
                placeholder="选择有效用户"
              >
                <el-option
                  v-if="level.approverUserId && !isValidApprover(level.approverUserId)"
                  :label="invalidApproverLabel(level.approverUserId)"
                  :value="level.approverUserId"
                  disabled
                />
                <el-option
                  v-for="user in activeUsers"
                  :key="user.id"
                  :label="`${user.realName}（${user.username}）`"
                  :value="String(user.id)"
                />
              </el-select>
              <p v-if="level.approverUserId && !isValidApprover(level.approverUserId)" class="invalid-hint">
                该审批人已失效，请重新选择后再保存。
              </p>
            </div>
            <div class="chain-level__actions">
              <el-button
                link
                :disabled="index === 0 || chain.saving"
                @click="moveLevel(chain, index, -1)"
              >上移</el-button>
              <el-button
                link
                :disabled="index === chain.levels.length - 1 || chain.saving"
                @click="moveLevel(chain, index, 1)"
              >下移</el-button>
              <el-button link type="danger" :disabled="chain.saving" @click="removeLevel(chain, index)">
                删除
              </el-button>
            </div>
          </div>
        </div>

        <footer class="chain-card__footer">
          <el-button :disabled="chain.loading || chain.saving" @click="addLevel(chain)">
            + 添加一级
          </el-button>
          <el-button
            type="primary"
            :loading="chain.saving"
            :disabled="chain.loading || !isDirty(chain)"
            @click="saveChain(chain)"
          >保存审批链</el-button>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.approval-settings {
  width: min(1180px, 100%);
  margin: 0 auto;
  padding: 28px 24px 40px;
}

.settings-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  padding: 24px 28px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 3px rgb(31 45 61 / 5%);
}

.settings-status__eyebrow {
  margin: 0 0 6px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.settings-status h1 {
  margin: 0 0 8px;
  color: var(--el-text-color-primary);
  font-size: 26px;
  line-height: 1.25;
}

.settings-status__description,
.chain-card__header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.switch-control {
  display: flex;
  align-items: center;
  gap: 16px;
  flex: 0 0 auto;
  padding: 12px 16px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.switch-control__copy {
  display: grid;
  gap: 2px;
  text-align: right;
}

.switch-control__copy strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.switch-control__copy span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.settings-alert {
  margin-top: 16px;
}

.chain-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-top: 20px;
}

.chain-card {
  display: flex;
  min-width: 0;
  min-height: 430px;
  flex-direction: column;
  padding: 22px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 1px 3px rgb(31 45 61 / 5%);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.chain-card:focus {
  border-color: var(--el-color-primary-light-5);
  outline: none;
  box-shadow: 0 0 0 3px var(--el-color-primary-light-9);
}

.chain-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  min-height: 68px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.chain-card__header h2 {
  margin: 0 0 6px;
  color: var(--el-text-color-primary);
  font-size: 18px;
}

.chain-track {
  min-height: 260px;
  flex: 1;
  padding: 20px 0;
}

.chain-level {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  gap: 12px;
  position: relative;
  padding-bottom: 22px;
}

.chain-level:last-child {
  padding-bottom: 0;
}

.chain-level__rail {
  position: relative;
  display: flex;
  justify-content: center;
}

.chain-level__rail::after {
  position: absolute;
  top: 32px;
  bottom: -18px;
  left: 50%;
  width: 1px;
  background: var(--el-border-color);
  content: '';
}

.chain-level:last-child .chain-level__rail::after {
  display: none;
}

.chain-level__rail span {
  z-index: 1;
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid var(--el-color-primary-light-5);
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 700;
}

.chain-level__content {
  min-width: 0;
}

.chain-level__content label {
  display: block;
  margin-bottom: 7px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.chain-level__content :deep(.el-select) {
  width: 100%;
}

.invalid-hint {
  margin: 6px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.4;
}

.chain-level__actions {
  display: flex;
  align-items: flex-end;
  padding-bottom: 1px;
}

.chain-level__actions :deep(.el-button + .el-button) {
  margin-left: 8px;
}

.chain-card__footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}

@media (prefers-reduced-motion: reduce) {
  .chain-card {
    scroll-behavior: auto;
    transition: none;
  }
}

@media (max-width: 900px) {
  .chain-grid {
    grid-template-columns: 1fr;
  }

  .chain-card {
    min-height: 0;
  }
}

@media (max-width: 620px) {
  .approval-settings {
    padding: 20px 12px 32px;
  }

  .settings-status {
    align-items: stretch;
    flex-direction: column;
    gap: 18px;
    padding: 20px;
  }

  .switch-control {
    justify-content: space-between;
  }

  .chain-card {
    padding: 18px 14px;
  }

  .chain-level {
    grid-template-columns: 30px minmax(0, 1fr);
  }

  .chain-level__actions {
    grid-column: 2;
    justify-content: flex-start;
    margin-top: -4px;
  }
}
</style>
