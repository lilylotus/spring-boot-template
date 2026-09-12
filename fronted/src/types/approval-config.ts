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
