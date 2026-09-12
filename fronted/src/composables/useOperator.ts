import { ref, type Ref } from 'vue'

/**
 * "当前操作人"状态管理（过渡方案，见 openspec/changes/operator-header-identity）。
 *
 * 本项目尚未接入登录鉴权，后端通过请求头 `X-User-Id`/`X-User-Name` 识别操作人，
 * 前端提供一个全局可切换的"当前操作人"入口，选择结果只保存在浏览器 localStorage，
 * 不持久化到后端、不跨浏览器/跨设备同步。
 *
 * 不引入 Pinia：全局状态用一个模块级 `ref` 包一层即可满足"全局唯一、跨组件共享"的需求
 * （规模上不足以引入状态管理库，见 design.md D4）。
 */

/** localStorage 存储 key。 */
const STORAGE_KEY = 'operator'

/**
 * 默认操作人：与后端 D2 的种子数据/常量（`sys_user` 表 `id=1, real_name='默认管理员'`
 * 以及后端 `DefaultOperator` 常量）保持一致。若后端种子数据的 ID/姓名发生变化，
 * 需要同步修改这里。
 */
export const DEFAULT_OPERATOR: OperatorInfo = { id: '1', name: '默认管理员' }

/** 当前操作人信息：id 对应请求头 X-User-Id，name 对应请求头 X-User-Name。 */
export interface OperatorInfo {
  id: string
  name: string
}

/** 从 localStorage 读取操作人信息；缺失/格式非法/读取异常时返回 null，由调用方回退默认值。 */
function readFromStorage(): OperatorInfo | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw)
    if (
      parsed &&
      typeof parsed === 'object' &&
      typeof parsed.id === 'string' &&
      parsed.id.trim() !== '' &&
      typeof parsed.name === 'string'
    ) {
      return { id: parsed.id, name: parsed.name }
    }
    return null
  } catch {
    return null
  }
}

/** 模块级单例状态：整个应用共享同一份"当前操作人"。 */
const operator: Ref<OperatorInfo> = ref(readFromStorage() ?? DEFAULT_OPERATOR)

/** 切换当前操作人：更新内存状态并写回 localStorage。 */
function setOperator(next: OperatorInfo): void {
  operator.value = next
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // localStorage 写入失败（如隐私模式/存储配额已满）时忽略：
    // 内存状态仍然生效，只是刷新页面后无法保留本次选择，不影响当前会话使用。
  }
}

/** 全局"当前操作人"组合式函数：提供读取当前操作人的响应式引用与切换方法。 */
export function useOperator() {
  return {
    operator,
    setOperator,
  }
}
