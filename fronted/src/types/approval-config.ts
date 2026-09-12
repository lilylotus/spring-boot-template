/** 当前由全局审批开关控制的业务类型。 */
export type ApprovalBizType = 'USER_CREATE' | 'USER_EDIT'

/** 全局审批开关查询结果。 */
export interface ApprovalSwitch {
  approvalEnabled: boolean
}

/** 审批链中的一个顺序级别。 */
export interface ApprovalChainLevel {
  levelNo: number
  approverUserId: string
}

/** 整体保存审批链的请求体。 */
export interface ApprovalChainSaveRequest {
  levels: ApprovalChainLevel[]
}

export type ProcessNodeType = 'START' | 'APPROVAL' | 'END'

export interface ProcessDesignNode {
  id: string
  type: ProcessNodeType
  x: number
  y: number
  approverUserId?: string
}

export interface ProcessDesignEdge {
  id: string
  sourceNodeId: string
  targetNodeId: string
}

export interface ProcessTemplate {
  templateId?: number | null
  bizType: ApprovalBizType
  scope: 'GLOBAL'
  name: string
  draftRevision: number
  activeVersionNo?: number | null
  persisted: boolean
  nodes: ProcessDesignNode[]
  edges: ProcessDesignEdge[]
}

export interface ProcessTemplateDraftSaveRequest {
  expectedDraftRevision: number
  nodes: ProcessDesignNode[]
  edges: ProcessDesignEdge[]
}
