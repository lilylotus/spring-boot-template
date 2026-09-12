import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import { useOperator } from '../composables/useOperator'

/**
 * 响应拦截器实际上把 `AxiosResponse<RestResult<T>>` 转换成了裸的 `T`（见下方
 * `http.interceptors.response.use`），因此这里对 axios 的请求方法做类型层面的收窄，
 * 让调用方 `http.get<T>(url)` 等方法的返回类型直接是 `Promise<T>`，
 * 与拦截器的实际运行时行为保持一致，避免上层每次都要手动解包 `AxiosResponse`。
 */
declare module 'axios' {
  interface AxiosInstance {
    get<T = unknown>(url: string, config?: import('axios').AxiosRequestConfig): Promise<T>
    post<T = unknown>(
      url: string,
      data?: unknown,
      config?: import('axios').AxiosRequestConfig,
    ): Promise<T>
    put<T = unknown>(
      url: string,
      data?: unknown,
      config?: import('axios').AxiosRequestConfig,
    ): Promise<T>
  }
}

/**
 * 后端统一响应结构，对应 `com.example.template.common.RestResult<T>`
 * （继承自 `BaseResponse`）：
 * - code：`"0"` 表示成功，其余（目前固定为 `"500"`）表示失败。
 * - result：成功时的业务数据，失败时不返回（后端 `@JsonInclude(NON_NULL)` 会省略该字段）。
 * - error：失败时的错误提示信息。
 */
export interface RestResult<T> {
  code: string
  traceId?: string
  timestamp?: number
  error?: string
  result?: T
}

/**
 * Axios 实例：统一 baseURL、超时时间。
 * baseURL 留空，配合 `vite.config.ts` 中的开发代理，`/api` 前缀请求会被转发到后端
 * （见 backend UserController 路径前缀 `/api/users`，服务端口 54100）。
 * 生产环境可通过 `VITE_API_BASE_URL` 环境变量覆盖为实际后端地址。
 */
const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 10000,
})

/**
 * 请求拦截器：为每个请求写入当前操作人请求头 `X-User-Id`/`X-User-Name`。
 *
 * 项目尚未接入登录鉴权，后端 `identity`/`approval` 模块通过这两个请求头识别
 * "当前操作人"（缺失时后端会回退为默认管理员）。当前操作人由 `useOperator()`
 * 维护（模块级状态 + localStorage），此处每次请求前读取其最新值，
 * 这样切换操作人后无需刷新页面即可让后续请求生效。
 *
 * `X-User-Name` 可能包含中文（如默认操作人姓名"默认管理员"），浏览器
 * `XMLHttpRequest.setRequestHeader` 只接受 ISO-8859-1 范围内的字符作为请求头值，
 * 直接写入非 ASCII 字符串会抛 `TypeError` 导致请求根本发不出去，因此这里用
 * `encodeURIComponent` 做百分号编码后再写入；后端 `CurrentOperatorArgumentResolver`
 * 读取该请求头后用 `URLDecoder.decode` 解码还原。`X-User-Id` 始终是数字 ID 的
 * 字符串形式，不含非 ASCII 字符，不需要编码。
 */
http.interceptors.request.use((config) => {
  const { operator } = useOperator()
  config.headers.set('X-User-Id', operator.value.id)
  config.headers.set('X-User-Name', encodeURIComponent(operator.value.name))
  return config
})

/**
 * 响应拦截器：按 `RestResult<T>` 的 `code` 区分成功/失败。
 * - 成功（code === '0'）：直接把 `result` 作为 resolve 的值返回给调用方，
 *   调用方无需再关心统一响应结构。
 * - 失败：弹出错误提示，并 reject 一个携带后端 `error` 信息的 Error，方便调用方按需捕获。
 */
http.interceptors.response.use(
  ((response: AxiosResponse<RestResult<unknown>>) => {
    const body = response.data
    if (body && body.code === '0') {
      return body.result
    }
    const message = body?.error ?? '请求失败'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }) as (response: AxiosResponse) => AxiosResponse,
  (error) => {
    const message = error?.message ?? '网络异常，请稍后重试'
    ElMessage.error(message)
    return Promise.reject(error)
  },
)

export default http
