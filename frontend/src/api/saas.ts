import http from './http'

export interface Organization { id: string; slug: string; name: string; createdAt: string }
export interface Membership { tenantId: string; userId: string; tenantSlug: string; tenantName: string; role: Role }
export interface Member { userId: string; email: string | null; displayName: string | null; role: Role; joinedAt: string }
export interface Invitation { id: string; tenantId: string; email: string; role: Role; status: string; expiresAt: string; createdAt: string; invitationUrl: string | null }
export interface Camera { id: string; cameraNumber?: number; name: string; status: string; createdAt?: string; lastHeartbeat?: string | null; autoStart?: boolean; lastError?: string | null }
export interface Broadcast { id: string; cameraId: string; publicId: string; visibility: 'PUBLIC' | 'PRIVATE'; enabled: boolean; createdAt?: string }
export interface ResourceUsage { tenantId: string; resource: 'CAMERA' | 'BROADCAST' | 'VIEWER'; used: number; limit: number; remaining: number }
export type Role = 'OWNER' | 'ADMIN' | 'OPERATOR' | 'VIEWER'

export const saasApi = {
  me: () => http.get('/v1/identity/me').then(r => r.data),
  memberships: () => http.get<Membership[]>('/v1/identity/me/memberships').then(r => r.data),
  organizations: () => http.get<Organization[]>('/v1/organizations').then(r => r.data),
  organization: (tenantId: string) => http.get<Organization>(`/v1/organizations/${tenantId}`).then(r => r.data),
  createOrganization: (name: string, slug: string) => http.post<Organization>('/v1/organizations', { name, slug }).then(r => r.data),
  renameOrganization: (tenantId: string, name: string) => http.patch<Organization>(`/v1/organizations/${tenantId}`, { name }).then(r => r.data),
  members: (tenantId: string) => http.get<Member[]>(`/v1/organizations/${tenantId}/members`).then(r => r.data),
  changeRole: (tenantId: string, userId: string, role: Role) => http.patch(`/v1/organizations/${tenantId}/members/${userId}`, { role }),
  removeMember: (tenantId: string, userId: string) => http.delete(`/v1/organizations/${tenantId}/members/${userId}`),
  leave: (tenantId: string) => http.post(`/v1/organizations/${tenantId}/leave`),
  invite: (tenantId: string, email: string, role: Role) => http.post<Invitation>(`/v1/organizations/${tenantId}/invitations`, { email, role }).then(r => r.data),
  invitations: (tenantId: string) => http.get<Invitation[]>(`/v1/organizations/${tenantId}/invitations`).then(r => r.data),
  revokeInvitation: (tenantId: string, invitationId: string) => http.delete(`/v1/organizations/${tenantId}/invitations/${invitationId}`),
  acceptInvitation: (token: string) => http.post('/v1/invitations/accept', { token }),
  cameras: () => http.get<unknown>('/v1/cameras', { params: { size: 100 } }).then(r => {
    if (Array.isArray(r.data)) return r.data as Camera[]
    if (typeof r.data === 'object' && r.data !== null && 'content' in r.data && Array.isArray((r.data as { content: unknown }).content)) return (r.data as { content: Camera[] }).content
    return []
  }),
  createCamera: (body: Record<string, unknown>, idempotencyKey: string) => http.post<Camera>('/v1/cameras', body, { headers: { 'Idempotency-Key': idempotencyKey } }).then(r => r.data),
  cameraUsage: () => http.get<ResourceUsage>('/v1/cameras/usage').then(r => r.data),
  updateCamera: (id: string, body: Record<string, unknown>) => http.put<Camera>(`/v1/cameras/${id}`, body).then(r => r.data),
  broadcasts: () => http.get<Broadcast[]>('/v1/broadcasts').then(r => r.data),
  broadcastUsage: () => http.get<ResourceUsage>('/v1/broadcasts/usage').then(r => r.data),
  viewerUsage: () => http.get<ResourceUsage>('/v1/broadcasts/viewer-usage').then(r => r.data),
  createBroadcast: (cameraId: string, visibility: Broadcast['visibility']) => http.post<Broadcast>('/v1/broadcasts', { cameraId, visibility }).then(r => r.data),
  setBroadcastEnabled: (id: string, enabled: boolean) => http.put<Broadcast>(`/v1/broadcasts/${id}/enabled`, { enabled }).then(r => r.data),
  setBroadcastVisibility: (id: string, visibility: Broadcast['visibility']) => http.put<Broadcast>(`/v1/broadcasts/${id}/visibility`, { visibility }).then(r => r.data),
  deleteBroadcast: (id: string) => http.delete(`/v1/broadcasts/${id}`),
  playback: (id: string) => http.get<{ publicId: string; visibility: string; enabled: boolean; playerUrl: string; embedHtml: string }>(`/v1/broadcasts/${id}/playback`).then(r => r.data)
}
