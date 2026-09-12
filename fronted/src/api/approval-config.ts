import http from './http'
import type {
  ApprovalBizType,
  ApprovalChainLevel,
  ApprovalChainSaveRequest,
  ApprovalSwitch,
  ProcessTemplate,
  ProcessTemplateDraftSaveRequest,
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

export function getProcessTemplate(bizType: ApprovalBizType): Promise<ProcessTemplate> {
  return http.get<ProcessTemplate>(`/api/approval/templates/${bizType}`)
}

export function saveProcessTemplateDraft(
  bizType: ApprovalBizType,
  request: ProcessTemplateDraftSaveRequest,
): Promise<ProcessTemplate> {
  return http.put<ProcessTemplate>(`/api/approval/templates/${bizType}/draft`, request)
}

export function publishProcessTemplate(
  bizType: ApprovalBizType,
  expectedDraftRevision: number,
): Promise<ProcessTemplate> {
  return http.post<ProcessTemplate>(`/api/approval/templates/${bizType}/publish`, {
    expectedDraftRevision,
  })
}
