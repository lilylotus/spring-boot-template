import http from './http'
import type { ApprovalActRequest, ApprovalDetail, ApprovalRecord, ApprovalTask } from '../types/approval'

/** 查询当前操作人的待审批任务。 */
export function listPendingApprovalTasks(): Promise<ApprovalTask[]> {
  return http.get<ApprovalTask[]>('/api/approval/tasks')
}

/** 查询当前操作人已经处理的审批记录。 */
export function listApprovalRecords(): Promise<ApprovalRecord[]> {
  return http.get<ApprovalRecord[]>('/api/approval/records')
}

/** 以当前操作人身份同意或驳回一条审批任务。 */
export function actOnApprovalTask(request: ApprovalActRequest): Promise<void> {
  return http.post<void>('/api/approval/tasks/act', request)
}

/** 按稳定审批实例 ID 查询当前操作人可访问的审批详情。 */
export function getApprovalDetail(approvalId: number): Promise<ApprovalDetail> {
  return http.get<ApprovalDetail>(`/api/approval/instances/${approvalId}`)
}
