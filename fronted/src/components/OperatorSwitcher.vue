<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listUsers } from '../api/user'
import type { UserVO } from '../types/user'
import { useOperator } from '../composables/useOperator'

/**
 * 全局"当前操作人"选择器（页面左上角常驻）。
 *
 * 下拉选项数据源直接复用已有的用户列表接口 `GET /api/users`（`listUsers()`），
 * 当前选中项与 `useOperator()` 状态双向绑定；选择后立即调用 `setOperator`
 * 写回 localStorage，后续所有请求自动携带切换后的操作人请求头
 * （见 `src/api/http.ts` 请求拦截器）。
 *
 * 本组件不做任何后端持久化，仅为纯客户端行为。
 */

const { operator, setOperator } = useOperator()

const users = ref<UserVO[]>([])
const loading = ref(false)

/**
 * `el-select` 的 model 只能是一个标量值，这里用用户 ID（字符串）作为选中值，
 * 通过 computed getter/setter 与 `useOperator()` 的 `{ id, name }` 对象互相转换：
 * - getter：返回当前操作人 ID，供下拉框回显选中项。
 * - setter：选择变化时，从已加载的用户列表中找到对应用户，取其 `id`/`realName`
 *   组装为操作人信息并写回；若因列表尚未加载完成等原因找不到对应用户，
 *   则直接忽略本次变更，保留原操作人不变。
 */
const selectedUserId = computed<string>({
  get: () => operator.value.id,
  set: (value) => {
    const target = users.value.find((user) => String(user.id) === value)
    if (!target) {
      return
    }
    setOperator({ id: String(target.id), name: target.realName })
  },
})

/** 加载用户列表作为下拉选项；加载失败时保留当前操作人状态，不阻塞页面使用。 */
async function loadUsers() {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch {
    // 全局请求已在 src/api/http.ts 响应拦截器中弹出错误提示，这里无需重复处理，
    // 仅保证下拉列表为空时不影响当前操作人的既有选择继续生效。
  } finally {
    loading.value = false
  }
}

/** 每次展开下拉框时刷新选项，确保刚新增的用户无需刷新页面即可被选择。 */
function handleVisibleChange(visible: boolean) {
  if (visible) {
    loadUsers()
  }
}

onMounted(() => {
  loadUsers()
})
</script>

<template>
  <div class="operator-switcher">
    <span class="operator-switcher__label">当前操作人</span>
    <el-select
      v-model="selectedUserId"
      size="default"
      placeholder="请选择操作人"
      :loading="loading"
      style="width: 180px"
      @visible-change="handleVisibleChange"
    >
      <el-option
        v-for="user in users"
        :key="user.id"
        :label="user.realName"
        :value="String(user.id)"
      />
    </el-select>
  </div>
</template>

<style scoped>
.operator-switcher {
  display: flex;
  align-items: center;
  gap: 8px;
}

.operator-switcher__label {
  color: var(--el-text-color-secondary);
  font-size: 14px;
  white-space: nowrap;
}
</style>
