import http from './http'
import type {
  ApprovalBizType,
  ApprovalChainLevel,
  ApprovalChainSaveRequest,
  ApprovalSwitch,
} from '../types/approval-config'

/** 查询全局审批开关。 */
export function getApprovalSwitch(): Promise<ApprovalSwitch> {
  return http.get<ApprovalSwitch>('/api/approval/switch')
}

/** 更新全局审批开关。 */
export function updateApprovalSwitch(approvalEnabled: boolean): Promise<void> {
  return http.put<void>('/api/approval/switch', { approvalEnabled })
}

/** 查询指定业务类型的顺序审批链。 */
export function listApprovalChain(bizType: ApprovalBizType): Promise<ApprovalChainLevel[]> {
  return http.get<ApprovalChainLevel[]>(`/api/approval/chains/${bizType}`)
}

/** 整体替换指定业务类型的顺序审批链。 */
export function saveApprovalChain(
  bizType: ApprovalBizType,
  levels: ApprovalChainLevel[],
): Promise<void> {
  const request: ApprovalChainSaveRequest = { levels }
  return http.put<void>(`/api/approval/chains/${bizType}`, request)
}
