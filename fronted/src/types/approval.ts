/** 审批动作，与后端 ApprovalAction 枚举保持一致。 */
export type ApprovalAction = 'AGREE' | 'REJECT'

/** 审批实例状态。 */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 完整审批流程中单个节点的状态。 */
export type ApprovalNodeStatus = 'APPROVED' | 'REJECTED' | 'PENDING' | 'WAITING' | 'SKIPPED'

/** 审批节点的指派类型：指定用户或用户组；旧数据可能缺失。 */
export type ApprovalAssigneeType = 'USER' | 'GROUP'

/** 当前操作人的一条待审批任务。 */
export interface ApprovalTask {
  /** 逻辑节点 ID，旧数据可能缺失。 */
  nodeId?: string | null
  /** 指派类型；缺失时有 groupId 视为 GROUP，否则视为 USER。 */
  assigneeType?: ApprovalAssigneeType | null
  /** 用户组节点关联的用户组 ID，指定用户节点为空。 */
  groupId?: number | null
  /** 用户组名称，指定用户节点为空。 */
  groupName?: string | null
  /** 为 true 时只允许确认驳回结果，不能同意。 */
  confirmOnly?: boolean
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
  /** 逻辑节点 ID，旧数据可能缺失。 */
  nodeId?: string | null
  /** 指派类型；缺失时有 groupId 视为 GROUP，否则视为 USER。 */
  assigneeType?: ApprovalAssigneeType | null
  /** 用户组节点关联的用户组 ID，指定用户节点为空。 */
  groupId?: number | null
  /** 处理时的用户组名称，不随后续改名变化；指定用户节点为空。 */
  groupName?: string | null
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

/** 用户组节点中一位成员实际提交的审批动作。 */
export interface ApprovalMemberAction {
  userId: string
  action: ApprovalAction
  comment?: string | null
  /** 后端 LocalDateTime 序列化后的字符串。 */
  actedTime: string
  /** 处理时的用户组名称。 */
  groupName?: string | null
}

/** 审批详情中的一个流程节点。 */
export interface ApprovalNode {
  /** 逻辑节点 ID，旧数据可能缺失。 */
  nodeId?: string | null
  /** 指派类型；缺失时有 groupId 视为 GROUP，否则视为 USER。 */
  assigneeType?: ApprovalAssigneeType | null
  /** 用户组节点关联的用户组 ID，指定用户节点为空。 */
  groupId?: number | null
  /** 用户组名称，指定用户节点为空。 */
  groupName?: string | null
  /** 用户组节点当前最新有效成员的用户 ID。 */
  currentMemberIds?: string[] | null
  /** 用户组节点的历史成员动作，成员移出后仍保留。 */
  memberActions?: ApprovalMemberAction[] | null
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
