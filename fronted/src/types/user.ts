/**
 * 用户管理相关的请求/响应 TS 类型。
 *
 * 字段与后端 `com.example.template.identity` 模块的 DTO 保持一致，来源：
 * - backend/src/main/java/com/example/template/identity/dto/UserCreateRequest.java
 * - backend/src/main/java/com/example/template/identity/dto/UserUpdateRequest.java
 * - backend/src/main/java/com/example/template/identity/dto/UserVO.java
 * - backend/src/main/java/com/example/template/identity/dto/UserPendingChangeVO.java
 * - backend/src/main/java/com/example/template/identity/dto/UserOperationResultVO.java
 *
 * 手写维护，非自动生成；如后端 DTO 字段发生变化，需要同步更新本文件。
 */

/** 用户状态：待审批 / 已生效 / 已驳回。与后端 UserVO#status 取值保持一致。 */
export type UserStatus = 'PENDING' | 'ACTIVE' | 'REJECTED'

/** 新增用户请求。对应后端 UserCreateRequest。 */
export interface UserCreateRequest {
  /** 账号，必填。 */
  username: string
  /** 姓名，必填。 */
  realName: string
  /** 手机号，选填。 */
  mobile?: string
  /** 邮箱，选填，需符合邮箱格式。 */
  email?: string
}

/**
 * 编辑用户请求。对应后端 UserUpdateRequest。
 * 账号不可编辑，只编辑姓名/手机号/邮箱等基础信息。
 */
export interface UserUpdateRequest {
  /** 姓名，必填。 */
  realName: string
  /** 手机号，选填。 */
  mobile?: string
  /** 邮箱，选填，需符合邮箱格式。 */
  email?: string
}

/** 用户详情中展示的“待审批中的编辑变更”内容。对应后端 UserPendingChangeVO。 */
export interface UserPendingChangeVO {
  id: number
  /** 变更记录自身状态。 */
  status: UserStatus
  newRealName: string
  newMobile?: string
  newEmail?: string
  /** 后端 LocalDateTime 序列化为 "yyyy-MM-dd HH:mm:ss" 格式字符串。 */
  createdTime: string
}

/**
 * 用户列表/详情的展示对象：始终展示已生效信息 + 当前状态；
 * 若存在待审批中的编辑变更，通过 pendingChange 一并返回。
 * 对应后端 UserVO。
 */
export interface UserVO {
  id: number
  username: string
  realName: string
  mobile?: string
  email?: string
  /** PENDING（待审批） / ACTIVE（已生效） / REJECTED（已驳回）。 */
  status: UserStatus
  /** 待审批中的编辑变更内容，不存在时为 null/undefined。 */
  pendingChange?: UserPendingChangeVO | null
}

/**
 * 新增/编辑用户操作的响应：明确告知调用方本次是“已生效”还是“待审批”。
 * 对应后端 UserOperationResultVO。
 */
export interface UserOperationResultVO {
  /** 用户ID。 */
  userId: number
  /** 本次操作后用户/变更所处的状态（PENDING/ACTIVE）。 */
  status: UserStatus
  /** 是否已直接生效（true=已生效，false=待审批）。 */
  effective: boolean
  /** 面向前端展示的提示信息。 */
  message?: string
}
