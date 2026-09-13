<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getProcessTemplate,
  publishProcessTemplate,
  saveProcessTemplateDraft,
} from '../api/approval-config'
import type {
  ApprovalBizType,
  ProcessDesignEdge,
  ProcessDesignNode,
  ProcessTemplate,
} from '../types/approval-config'
import type { UserVO } from '../types/user'
import { listUserGroups } from '../api/user-group'
import type { UserGroup } from '../api/user-group'

const props = defineProps<{
  bizType: ApprovalBizType
  title: string
  users: UserVO[]
}>()

const emit = defineEmits<{
  statusChange: []
}>()

const canvasRef = ref<HTMLElement>()
const groups = ref<UserGroup[]>([])
const groupsFailed = ref(false)
const template = ref<ProcessTemplate | null>(null)
const nodes = ref<ProcessDesignNode[]>([])
const edges = ref<ProcessDesignEdge[]>([])
const savedSnapshot = ref('')
const loading = ref(true)
const saving = ref(false)
const publishing = ref(false)
const error = ref('')
const selectedNodeId = ref<string | null>(null)
const pendingSourceId = ref<string | null>(null)
const markerId = `arrow-${props.bizType.toLowerCase()}`
let nextId = 1
let dragging: { id: string; offsetX: number; offsetY: number } | null = null

const activeUsers = computed(() => props.users.filter((user) => user.status === 'ACTIVE'))
const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value))
const snapshot = computed(() => JSON.stringify({ nodes: nodes.value, edges: edges.value }))
const isDirty = computed(() => snapshot.value !== savedSnapshot.value)
const canvasWidth = computed(() => Math.max(920, ...nodes.value.map((node) => node.x + 230)))
const canvasHeight = computed(() => Math.max(430, ...nodes.value.map((node) => node.y + 150)))
const validationErrors = computed(() => validateGraph())

function cloneNodes(source: ProcessDesignNode[]): ProcessDesignNode[] {
  return source.map((node) => ({ ...node }))
}

function cloneEdges(source: ProcessDesignEdge[]): ProcessDesignEdge[] {
  return source.map((edge) => ({ ...edge }))
}

function applyTemplate(value: ProcessTemplate) {
  template.value = value
  nodes.value = cloneNodes(value.nodes)
  edges.value = cloneEdges(value.edges)
  savedSnapshot.value = JSON.stringify({ nodes: nodes.value, edges: edges.value })
  selectedNodeId.value = null
  pendingSourceId.value = null
  emit('statusChange')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await refreshGroups()
    applyTemplate(await getProcessTemplate(props.bizType))
  } catch {
    error.value = '流程模板加载失败，请重试。'
  } finally {
    loading.value = false
    emit('statusChange')
  }
}

function addApprovalNode() {
  const id = `node-${Date.now()}-${nextId++}`
  nodes.value.push({
    id,
    type: 'APPROVAL',
    x: 260 + (nodes.value.length % 3) * 210,
    y: 80 + Math.floor(nodes.value.length / 3) * 120,
    approverUserId: '',
  })
  selectedNodeId.value = id
  emit('statusChange')
}

function removeNode(node: ProcessDesignNode) {
  if (node.type !== 'APPROVAL') return
  nodes.value = nodes.value.filter((item) => item.id !== node.id)
  edges.value = edges.value.filter(
    (edge) => edge.sourceNodeId !== node.id && edge.targetNodeId !== node.id,
  )
  if (selectedNodeId.value === node.id) selectedNodeId.value = null
  if (pendingSourceId.value === node.id) pendingSourceId.value = null
  emit('statusChange')
}

function startConnection(node: ProcessDesignNode) {
  if (node.type === 'END') return
  pendingSourceId.value = pendingSourceId.value === node.id ? null : node.id
}

function finishConnection(node: ProcessDesignNode) {
  const source = pendingSourceId.value
  if (!source || source === node.id || node.type === 'START') return
  edges.value = edges.value.filter(
    (edge) => edge.sourceNodeId !== source && edge.targetNodeId !== node.id,
  )
  edges.value.push({
    id: `edge-${Date.now()}-${nextId++}`,
    sourceNodeId: source,
    targetNodeId: node.id,
  })
  pendingSourceId.value = null
  emit('statusChange')
}

function removeEdge(edgeId: string) {
  edges.value = edges.value.filter((edge) => edge.id !== edgeId)
  emit('statusChange')
}

function nodeById(id: string): ProcessDesignNode | undefined {
  return nodes.value.find((node) => node.id === id)
}

function edgeLine(edge: ProcessDesignEdge) {
  const source = nodeById(edge.sourceNodeId)
  const target = nodeById(edge.targetNodeId)
  return {
    x1: (source?.x ?? 0) + 176,
    y1: (source?.y ?? 0) + 42,
    x2: target?.x ?? 0,
    y2: (target?.y ?? 0) + 42,
  }
}

function userLabel(userId?: string): string {
  if (!userId) return '未选择审批人'
  const user = props.users.find((item) => String(item.id) === userId)
  return user ? `${user.realName}（${user.username}）` : `用户 ID ${userId}（已失效）`
}

async function refreshGroups() {
  try {
    groups.value = await listUserGroups()
    groupsFailed.value = false
  } catch {
    groupsFailed.value = true
  }
}

function changeAssigneeType() {
  if (!selectedNode.value) return
  delete selectedNode.value.approverUserId
  delete selectedNode.value.approverGroupId
}

function assignmentLabel(node: ProcessDesignNode): string {
  if (node.assigneeType !== 'GROUP') return userLabel(node.approverUserId)
  const group = groups.value.find(item => item.id === node.approverGroupId)
  return group ? group.name + '（有效 ' + group.activeMemberIds.length + ' 人）' : '请选择有效用户组'
}

function nodeTitle(node: ProcessDesignNode): string {
  if (node.type === 'START') return '开始'
  if (node.type === 'END') return '结束'
  return '审批节点'
}

function handlePointerDown(event: PointerEvent, node: ProcessDesignNode) {
  if ((event.target as HTMLElement).closest('button')) return
  const rect = canvasRef.value?.getBoundingClientRect()
  if (!rect) return
  selectedNodeId.value = node.id
  dragging = {
    id: node.id,
    offsetX: event.clientX - rect.left + (canvasRef.value?.scrollLeft ?? 0) - node.x,
    offsetY: event.clientY - rect.top + (canvasRef.value?.scrollTop ?? 0) - node.y,
  }
  window.addEventListener('pointermove', handlePointerMove)
  window.addEventListener('pointerup', handlePointerUp, { once: true })
}

function handlePointerMove(event: PointerEvent) {
  if (!dragging || !canvasRef.value) return
  const node = nodeById(dragging.id)
  const rect = canvasRef.value.getBoundingClientRect()
  if (!node) return
  node.x = Math.max(12, event.clientX - rect.left + canvasRef.value.scrollLeft - dragging.offsetX)
  node.y = Math.max(12, event.clientY - rect.top + canvasRef.value.scrollTop - dragging.offsetY)
}

function handlePointerUp() {
  dragging = null
  window.removeEventListener('pointermove', handlePointerMove)
  emit('statusChange')
}

function validateGraph(): string[] {
  const problems: string[] = []
  const starts = nodes.value.filter((node) => node.type === 'START')
  const ends = nodes.value.filter((node) => node.type === 'END')
  const approvals = nodes.value.filter((node) => node.type === 'APPROVAL')
  if (starts.length !== 1 || ends.length !== 1) problems.push('必须且只能有一个开始和结束节点')
  if (approvals.length === 0) problems.push('至少添加一个审批节点')
  if (approvals.some((node) => node.assigneeType !== 'GROUP' && !node.approverUserId)) problems.push('个人节点必须选择审批人')
  const activeUserIds = new Set(activeUsers.value.map((user) => String(user.id)))
  if (approvals.some((node) => node.assigneeType !== 'GROUP' && node.approverUserId && !activeUserIds.has(node.approverUserId))) {
    problems.push('存在已失效的审批人，请重新选择')
  }
  if (approvals.some(node => node.assigneeType === 'GROUP' && (groupsFailed.value
      || !groups.value.some(group => group.id === node.approverGroupId && group.activeMemberIds.length > 0)))) {
    problems.push('用户组不存在、停用、无有效成员或加载失败，请刷新用户组')
  }
  const incoming = new Map<string, number>()
  const outgoing = new Map<string, number>()
  for (const edge of edges.value) {
    incoming.set(edge.targetNodeId, (incoming.get(edge.targetNodeId) ?? 0) + 1)
    outgoing.set(edge.sourceNodeId, (outgoing.get(edge.sourceNodeId) ?? 0) + 1)
  }
  if (nodes.value.some((node) => incoming.get(node.id)! > 1 || outgoing.get(node.id)! > 1)) {
    problems.push('首版流程不支持分叉或汇聚')
  }
  if (starts[0] && outgoing.get(starts[0].id) !== 1) problems.push('开始节点必须连接下一节点')
  if (ends[0] && incoming.get(ends[0].id) !== 1) problems.push('结束节点必须由上一节点连接')
  if (approvals.some((node) => incoming.get(node.id) !== 1 || outgoing.get(node.id) !== 1)) {
    problems.push('每个审批节点必须完整连接前后节点')
  }
  if (starts.length === 1) {
    const nextBySource = new Map(edges.value.map((edge) => [edge.sourceNodeId, edge.targetNodeId]))
    const visited = new Set<string>()
    let current: string | undefined = starts[0]?.id
    while (current && !visited.has(current)) {
      visited.add(current)
      current = nextBySource.get(current)
    }
    if (current || visited.size !== nodes.value.length || (ends[0] && !visited.has(ends[0].id))) {
      problems.push('流程存在环路或未连接节点')
    }
  }
  return [...new Set(problems)]
}

async function saveDraft() {
  if (!template.value || saving.value) return
  saving.value = true
  try {
    const result = await saveProcessTemplateDraft(props.bizType, {
      expectedDraftRevision: template.value.draftRevision,
      nodes: cloneNodes(nodes.value),
      edges: cloneEdges(edges.value),
    })
    applyTemplate(result)
    ElMessage.success(`${props.title}草稿已保存`)
  } catch {
    // HTTP 层统一提示，当前草稿保持不变。
  } finally {
    saving.value = false
  }
}

async function publish() {
  if (!template.value || publishing.value) return
  if (isDirty.value) {
    ElMessage.warning('请先保存当前草稿')
    return
  }
  await refreshGroups()
  if (validationErrors.value.length > 0) {
    ElMessage.warning(validationErrors.value[0])
    return
  }
  try {
    await ElMessageBox.confirm(
      `发布后，新发起的${props.title}将使用此流程，运行中审批不受影响。`,
      '确认发布流程？',
      { confirmButtonText: '发布生效', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  publishing.value = true
  try {
    applyTemplate(await publishProcessTemplate(props.bizType, template.value.draftRevision))
    ElMessage.success(`${props.title}已发布生效`)
  } catch {
    // 保留草稿。
  } finally {
    publishing.value = false
  }
}

function canEnable(): boolean {
  return Boolean(template.value?.activeVersionNo) && !isDirty.value && !loading.value && !error.value
}

function hasUnsavedChanges(): boolean {
  return isDirty.value
}

defineExpose({ canEnable, hasUnsavedChanges, load })

onMounted(load)
onBeforeUnmount(() => window.removeEventListener('pointermove', handlePointerMove))
</script>

<template>
  <article class="designer-card">
    <header class="designer-header">
      <div>
        <h2>{{ title }}</h2>
        <p>
          草稿修订 {{ template?.draftRevision ?? 0 }} ·
          {{ template?.activeVersionNo ? `当前生效 v${template.activeVersionNo}` : '尚未发布' }}
        </p>
      </div>
      <div class="designer-actions">
        <el-button @click="refreshGroups">刷新用户组</el-button>
        <el-tag v-if="isDirty" type="warning">未保存</el-tag>
        <el-button :disabled="loading" @click="addApprovalNode">添加审批节点</el-button>
        <el-button :loading="saving" :disabled="loading || !isDirty" @click="saveDraft">
          保存草稿
        </el-button>
        <el-button type="primary" :loading="publishing" :disabled="loading || isDirty" @click="publish">
          发布生效
        </el-button>
      </div>
    </header>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon>
      <template #default><el-button link type="primary" @click="load">重新加载</el-button></template>
    </el-alert>

    <div class="designer-layout">
      <div ref="canvasRef" v-loading="loading" class="canvas-scroll">
        <div class="canvas" :style="{ width: `${canvasWidth}px`, height: `${canvasHeight}px` }">
          <svg class="edges" :width="canvasWidth" :height="canvasHeight">
            <defs>
              <marker :id="markerId" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto">
                <path d="M0,0 L0,6 L9,3 z" fill="#82909c" />
              </marker>
            </defs>
            <line
              v-for="edge in edges"
              :key="edge.id"
              v-bind="edgeLine(edge)"
              class="edge"
              :marker-end="`url(#${markerId})`"
              @click="removeEdge(edge.id)"
            />
          </svg>

          <div
            v-for="node in nodes"
            :key="node.id"
            class="process-node"
            :class="[`process-node--${node.type.toLowerCase()}`, { selected: selectedNodeId === node.id }]"
            :style="{ left: `${node.x}px`, top: `${node.y}px` }"
            @pointerdown="handlePointerDown($event, node)"
            @click="selectedNodeId = node.id"
          >
            <button
              v-if="node.type !== 'START'"
              class="port port--input"
              type="button"
              title="连接到此节点"
              @click.stop="finishConnection(node)"
            />
            <strong>{{ nodeTitle(node) }}</strong>
            <span>{{ node.type === 'APPROVAL' ? assignmentLabel(node) : nodeTitle(node) }}</span>
            <button
              v-if="node.type !== 'END'"
              class="port port--output"
              :class="{ active: pendingSourceId === node.id }"
              type="button"
              title="从此节点连出"
              @click.stop="startConnection(node)"
            />
          </div>
        </div>
      </div>

      <aside class="inspector">
        <h3>节点属性</h3>
        <template v-if="selectedNode">
          <p class="node-type">{{ nodeTitle(selectedNode) }} · {{ selectedNode.id }}</p>
          <template v-if="selectedNode.type === 'APPROVAL'">
            <label>审批方式</label>
            <el-select v-model="selectedNode.assigneeType" placeholder="指定用户" @change="changeAssigneeType">
              <el-option label="指定用户" value="USER" />
              <el-option label="用户组（任一成员同意）" value="GROUP" />
            </el-select>
            <template v-if="selectedNode.assigneeType === 'GROUP'">
              <el-select v-model="selectedNode.approverGroupId" filterable placeholder="选择用户组">
                <el-option v-for="group in groups" :key="group.id" :label="group.name + '（' + group.activeMemberIds.length + '人）'" :value="group.id" :disabled="!group.activeMemberIds.length" />
              </el-select>
              <p>审批时使用组内最新有效成员，任一人同意即通过，全部当前成员驳回才终止。</p>
            </template>
            <template v-else>
            <label>审批人</label>
            <el-select v-model="selectedNode.approverUserId" filterable placeholder="选择有效用户">
              <el-option
                v-if="selectedNode.approverUserId && !activeUsers.some((user) => String(user.id) === selectedNode?.approverUserId)"
                :label="userLabel(selectedNode.approverUserId)"
                :value="selectedNode.approverUserId"
                disabled
              />
              <el-option
                v-for="user in activeUsers"
                :key="user.id"
                :label="`${user.realName}（${user.username}）`"
                :value="String(user.id)"
              />
            </el-select>
            </template>
            <el-button type="danger" plain @click="removeNode(selectedNode)">删除审批节点</el-button>
          </template>
          <p v-else>开始和结束节点不可删除，可拖动调整布局。</p>
        </template>
        <el-empty v-else :image-size="54" description="选择节点后编辑属性" />

        <div v-if="validationErrors.length" class="validation-list">
          <h4>发布前需处理</h4>
          <p v-for="problem in validationErrors" :key="problem">{{ problem }}</p>
        </div>
        <div class="connection-help">
          <strong>连线方法</strong>
          <span>点击上游节点右侧圆点，再点击下游节点左侧圆点。点击连线可删除。</span>
        </div>
      </aside>
    </div>
  </article>
</template>

<style scoped>
.designer-card { padding: 20px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: #fff; }
.designer-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 16px; }
.designer-header h2 { margin: 0 0 6px; font-size: 19px; }
.designer-header p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.designer-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.designer-layout { display: grid; grid-template-columns: minmax(0, 1fr) 250px; gap: 16px; }
.canvas-scroll { min-height: 430px; overflow: auto; border: 1px solid var(--el-border-color); border-radius: 6px; background-color: #f8fafc; background-image: radial-gradient(#d8dee8 1px, transparent 1px); background-size: 20px 20px; }
.canvas { position: relative; user-select: none; }
.edges { position: absolute; inset: 0; overflow: visible; }
.edge { stroke: #82909c; stroke-width: 2; cursor: pointer; }
.edge:hover { stroke: var(--el-color-danger); stroke-width: 4; }
.process-node { position: absolute; display: flex; width: 176px; min-height: 84px; box-sizing: border-box; flex-direction: column; justify-content: center; padding: 14px 18px; border: 2px solid #a8abb2; border-radius: 8px; background: #fff; box-shadow: 0 3px 10px rgb(31 45 61 / 10%); cursor: grab; touch-action: none; }
.process-node.selected { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-8); }
.process-node--start, .process-node--end { border-radius: 42px; text-align: center; }
.process-node--start { border-color: var(--el-color-success); }
.process-node--end { border-color: var(--el-color-danger); }
.process-node strong { color: var(--el-text-color-primary); font-size: 14px; }
.process-node span { overflow: hidden; margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.port { position: absolute; top: 34px; width: 16px; height: 16px; padding: 0; border: 2px solid #fff; border-radius: 50%; background: #82909c; box-shadow: 0 0 0 1px #82909c; cursor: crosshair; }
.port--input { left: -9px; }
.port--output { right: -9px; }
.port.active { background: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-7); }
.inspector { min-width: 0; padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; background: var(--el-fill-color-blank); }
.inspector h3 { margin: 0 0 14px; font-size: 16px; }
.inspector label { display: block; margin-bottom: 7px; color: var(--el-text-color-regular); font-size: 13px; }
.inspector :deep(.el-select) { width: 100%; }
.inspector :deep(.el-button) { width: 100%; margin-top: 12px; }
.node-type { overflow: hidden; color: var(--el-text-color-secondary); font-size: 12px; text-overflow: ellipsis; }
.validation-list { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--el-border-color-lighter); }
.validation-list h4 { margin: 0 0 8px; color: var(--el-color-danger); font-size: 13px; }
.validation-list p { margin: 5px 0; color: var(--el-color-danger); font-size: 12px; line-height: 1.45; }
.connection-help { display: grid; gap: 5px; margin-top: 18px; padding: 10px; border-radius: 5px; background: var(--el-fill-color-light); font-size: 12px; line-height: 1.5; }
.connection-help span { color: var(--el-text-color-secondary); }
@media (max-width: 900px) { .designer-layout { grid-template-columns: 1fr; } .inspector { min-height: 150px; } }
@media (max-width: 680px) { .designer-header { flex-direction: column; } .designer-actions { justify-content: flex-start; } }
</style>
