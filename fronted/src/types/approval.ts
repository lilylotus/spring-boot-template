/** 审批动作，与后端 ApprovalAction 枚举保持一致。 */
export type ApprovalAction = 'AGREE' | 'REJECT'

/** 审批实例状态。 */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 完整审批流程中单个节点的状态。 */
export type ApprovalNodeStatus = 'APPROVED' | 'REJECTED' | 'PENDING' | 'WAITING' | 'SKIPPED'

/** 当前操作人的一条待审批任务。 */
export interface ApprovalTask {
  /** 稳定的审批实例标识。 */
  approvalId: number
  bizType: string
  bizId: string
  level: number
  taskTitle: string
  /** 后端 LocalDateTime 序列化后的字符串。 */
  createdTime: string
}

/** 当前操作人已经处理的一条审批记录。 */
export interface ApprovalRecord {
  /** 稳定的审批实例标识。 */
  approvalId: number
  bizType: string
  bizId: string
  level: number
  taskTitle: string
  action: ApprovalAction
  comment?: string | null
  /** 后端 LocalDateTime 序列化后的字符串。 */
  taskCreatedTime: string
  /** 后端 LocalDateTime 序列化后的字符串。 */
  actedTime: string
}

/** 提交审批动作的请求体，审批人由全局请求头提供。 */
export interface ApprovalActRequest {
  bizType: string
  bizId: string
  action: ApprovalAction
  comment?: string
}

/** 审批详情中的一个流程节点。 */
export interface ApprovalNode {
  level: number
  approverUserId: string
  status: ApprovalNodeStatus
  taskTitle?: string | null
  action?: ApprovalAction | null
  comment?: string | null
  taskCreatedTime?: string | null
  actedTime?: string | null
}

/** 一次审批实例的详情。 */
export interface ApprovalDetail {
  approvalId: number
  bizType: string
  bizId: string
  status: ApprovalStatus
  currentLevel?: number | null
  initiatorUserId?: string | null
  submittedTime?: string | null
  payloadSnapshot?: string | null
  payloadAvailable: boolean
  templateName?: string | null
  templateVersionNo?: number | null
  nodes: ApprovalNode[]
}

/** 用户审批快照中的业务字段。 */
export interface UserApprovalFields {
  username?: string | null
  realName?: string | null
  mobile?: string | null
  email?: string | null
}

/** 新版用户新增/编辑审批使用的前后值快照。 */
export interface UserApprovalPayload {
  before: UserApprovalFields | null
  after: UserApprovalFields
}
