import http from './http'
import type { UserCreateRequest, UserOperationResultVO, UserUpdateRequest, UserVO } from '../types/user'

/**
 * 用户管理接口封装，分别对接后端 `identity` 模块 `UserController`
 * （backend/src/main/java/com/example/template/identity/controller/UserController.java）：
 * - GET  /api/users       -> listUsers
 * - GET  /api/users/{id}  -> getUser
 * - POST /api/users       -> createUser
 * - PUT  /api/users/{id}  -> updateUser
 */

/** 查询用户列表（含待审批中的用户）。 */
export function listUsers(): Promise<UserVO[]> {
  return http.get<UserVO[]>('/api/users')
}

/** 查询单个用户详情，若存在待审批中的编辑变更会一并返回。 */
export function getUser(id: number): Promise<UserVO> {
  return http.get<UserVO>(`/api/users/${id}`)
}

/** 新增用户。 */
export function createUser(payload: UserCreateRequest): Promise<UserOperationResultVO> {
  return http.post<UserOperationResultVO>('/api/users', payload)
}

/** 编辑用户（账号不可编辑）。 */
export function updateUser(id: number, payload: UserUpdateRequest): Promise<UserOperationResultVO> {
  return http.put<UserOperationResultVO>(`/api/users/${id}`, payload)
}
