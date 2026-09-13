import http from './http'

export interface UserGroup {
  id: number
  code: string
  name: string
  description: string | null
  status: 'ACTIVE' | 'DISABLED'
  revision: number
  memberIds: number[]
  activeMemberIds: string[]
}

export type UserGroupInput = Omit<UserGroup, 'id' | 'activeMemberIds'>

export function listUserGroups(): Promise<UserGroup[]> {
  return http.get<UserGroup[]>('/api/user-groups')
}

export function saveUserGroup(id: number | null, input: UserGroupInput): Promise<UserGroup> {
  return id === null
    ? http.post<UserGroup>('/api/user-groups', input)
    : http.put<UserGroup>(`/api/user-groups/${id}`, input)
}
