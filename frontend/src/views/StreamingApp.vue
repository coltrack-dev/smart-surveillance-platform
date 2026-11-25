<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCurrentIdentity, logout, requireLogin } from '@/auth/oidc'
import { getSelectedTenant, setSelectedTenant } from '@/api/http'
import { saasApi, type Broadcast, type Camera, type Invitation, type Member, type Membership, type Organization, type ResourceUsage, type Role } from '@/api/saas'

const route = useRoute()
const router = useRouter()
const identity = ref(getCurrentIdentity())
const memberships = ref<Membership[]>([])
const organizations = ref<Organization[]>([])
const organization = ref<Organization | null>(null)
const members = ref<Member[]>([])
const invitations = ref<Invitation[]>([])
const cameras = ref<Camera[]>([])
const broadcasts = ref<Broadcast[]>([])
const cameraUsage = ref<ResourceUsage | null>(null)
const broadcastUsage = ref<ResourceUsage | null>(null)
const viewerUsage = ref<ResourceUsage | null>(null)
const selectedTenant = ref(getSelectedTenant() ?? '')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const invitationUrl = ref('')
const newOrgName = ref('')
const cameraForm = ref({ name: '', rtspUrl: '', rtspUsername: '', rtspPassword: '' })
const cameraIdempotencyKey = ref('')
const editCameraId = ref('')
const editCameraForm = ref({ name: '', rtspUrl: '', rtspUsername: '', rtspPassword: '' })
const inviteForm = ref({ email: '', role: 'VIEWER' as Role })
const broadcastCamera = ref('')
const broadcastVisibility = ref<'PUBLIC' | 'PRIVATE'>('PRIVATE')
const invitationToken = ref('')
const identityUser = ref<Record<string, unknown> | null>(null)
let tenantLoadGeneration = 0
let refreshingAccess = false

const section = computed(() => route.name === 'invitation-acceptance' ? 'members' : String(route.params.section ?? 'overview'))
const role = computed(() => memberships.value.find(m => m.tenantId === selectedTenant.value)?.role ?? null)
const canManage = computed(() => role.value === 'OWNER' || role.value === 'ADMIN')
const canOperate = computed(() => canManage.value || role.value === 'OPERATOR')
const navigation = [
  { id: 'overview', label: 'Overview', icon: '◫' },
  { id: 'cameras', label: 'Cameras', icon: '◉' },
  { id: 'broadcasts', label: 'Broadcasts', icon: '▷' },
  { id: 'members', label: 'Members & invites', icon: '♙' }
]

function messageFor(err: unknown): string {
  const status = typeof err === 'object' && err !== null && 'response' in err
    ? (err as { response?: { status?: number; data?: { code?: string } } }).response?.status : undefined
  const code = typeof err === 'object' && err !== null && 'response' in err
    ? (err as { response?: { data?: { code?: string } } }).response?.data?.code : undefined
  if (code === 'CAMERA_QUOTA_EXCEEDED') return 'Camera limit reached. Delete a camera or change the organization plan.'
  if (code === 'ACTIVE_BROADCAST_QUOTA_EXCEEDED') return 'Active broadcast limit reached. Disable a broadcast or change the organization plan.'
  if (code === 'VIEWER_QUOTA_EXCEEDED') return 'The organization viewer limit is full. Close an active player or wait for an inactive session to expire.'
  if (code === 'QUOTA_AUTHORITY_UNAVAILABLE') return 'Quota information is temporarily unavailable. Try again shortly.'
  if (code === 'IDEMPOTENCY_KEY_REUSED') return 'This camera request key was already used with different details. Submit the request again.'
  if (status === 401) return 'Your session expired. Sign in again to continue.'
  if (status === 403) return 'Your organization role does not allow this action.'
  if (status === 404) return 'This organization or resource is no longer available.'
  if (status === 409) return 'This change conflicts with current organization data.'
  return 'The request could not be completed. Please try again.'
}

async function loadBase() {
  loading.value = true; error.value = ''
  try {
    const [me, orgs, membershipsResult] = await Promise.all([saasApi.me(), saasApi.organizations(), saasApi.memberships()])
    identityUser.value = me
    identity.value = { username: String(me.displayName || me.email || 'Account'), email: String(me.email || ''), emailVerified: me.emailVerified === true }
    organizations.value = orgs
    memberships.value = membershipsResult
    if (!orgs.some(o => o.id === selectedTenant.value)) selectedTenant.value = orgs[0]?.id ?? ''
    setSelectedTenant(selectedTenant.value || null)
    await loadTenantData()
  } catch (e) { error.value = messageFor(e) }
  finally { loading.value = false }
}

async function loadTenantData() {
  const generation = ++tenantLoadGeneration
  if (!selectedTenant.value) { organization.value = null; members.value = []; invitations.value = []; cameras.value = []; broadcasts.value = []; cameraUsage.value = null; broadcastUsage.value = null; viewerUsage.value = null; return }
  error.value = ''
  try {
    const currentRole = memberships.value.find(m => m.tenantId === selectedTenant.value)?.role
    const results = await Promise.all([
      saasApi.organization(selectedTenant.value),
      saasApi.cameras(),
      saasApi.broadcasts(),
      saasApi.cameraUsage(),
      saasApi.broadcastUsage(),
      saasApi.viewerUsage(),
      saasApi.members(selectedTenant.value),
      currentRole === 'OWNER' || currentRole === 'ADMIN' ? saasApi.invitations(selectedTenant.value) : Promise.resolve([])
    ])
    if (generation !== tenantLoadGeneration) return
    organization.value = results[0]; cameras.value = results[1]; broadcasts.value = results[2]; cameraUsage.value = results[3]; broadcastUsage.value = results[4]; viewerUsage.value = results[5]; members.value = results[6]; invitations.value = results[7]
  } catch (e) { if (generation === tenantLoadGeneration) error.value = messageFor(e) }
}

async function selectOrganization(id: string) {
  selectedTenant.value = id; setSelectedTenant(id || null)
  // Remove all organization-scoped data before requesting the new tenant.
  organization.value = null; members.value = []; invitations.value = []; cameras.value = []; broadcasts.value = []; cameraUsage.value = null; broadcastUsage.value = null; viewerUsage.value = null
  invitationUrl.value = ''
  await loadTenantData()
}

async function createOrganization() {
  if (!newOrgName.value.trim()) return
  busy.value = true; error.value = ''
  try {
    const slug = newOrgName.value.trim().toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 63)
    const created = await saasApi.createOrganization(newOrgName.value.trim(), slug)
    newOrgName.value = ''
    await loadBase()
    await selectOrganization(created.id)
    notice.value = 'Organization created. You are its OWNER.'
  } catch (e) { error.value = messageFor(e) }
  finally { busy.value = false }
}

async function promptCreateOrganization() {
  const name = window.prompt('New organization name')
  if (!name?.trim()) return
  newOrgName.value = name.trim()
  await createOrganization()
}

async function renameOrganization() {
  if (!organization.value) return
  const name = window.prompt('Organization name', organization.value.name)
  if (!name?.trim()) return
  await perform(async () => { organization.value = await saasApi.renameOrganization(selectedTenant.value, name.trim()); notice.value = 'Organization renamed.' })
}

async function createCamera() {
  if (!cameraIdempotencyKey.value) cameraIdempotencyKey.value = crypto.randomUUID()
  await perform(async () => {
    const camera = await saasApi.createCamera({ name: cameraForm.value.name, rtspUrl: cameraForm.value.rtspUrl, rtspUsername: cameraForm.value.rtspUsername || null, rtspPassword: cameraForm.value.rtspPassword || null, autoStart: false }, cameraIdempotencyKey.value)
    cameraIdempotencyKey.value = ''; cameras.value = [camera, ...cameras.value]; cameraUsage.value = await saasApi.cameraUsage(); cameraForm.value = { name: '', rtspUrl: '', rtspUsername: '', rtspPassword: '' }; notice.value = 'Camera added.'
  })
}

function beginCameraEdit(camera: Camera) {
  editCameraId.value = camera.id
  // Credentials are deliberately not fetched or prefilled; the operator must re-enter the source URL.
  editCameraForm.value = { name: camera.name, rtspUrl: '', rtspUsername: '', rtspPassword: '' }
}

async function saveCameraEdit() {
  await perform(async () => {
    const updated = await saasApi.updateCamera(editCameraId.value, { name: editCameraForm.value.name, rtspUrl: editCameraForm.value.rtspUrl, rtspUsername: editCameraForm.value.rtspUsername || null, rtspPassword: editCameraForm.value.rtspPassword || null, autoStart: false })
    cameras.value = cameras.value.map(camera => camera.id === updated.id ? updated : camera)
    editCameraId.value = ''; notice.value = 'Camera details updated.'
  })
}

async function createBroadcast() {
  if (!broadcastCamera.value) return
  await perform(async () => { const b = await saasApi.createBroadcast(broadcastCamera.value, broadcastVisibility.value); broadcasts.value = [b, ...broadcasts.value.filter(item => item.id !== b.id)]; broadcastUsage.value = await saasApi.broadcastUsage(); notice.value = 'Broadcast saved.' })
}

async function toggleBroadcast(b: Broadcast) {
  await perform(async () => { const updated = await saasApi.setBroadcastEnabled(b.id, !b.enabled); broadcasts.value = broadcasts.value.map(x => x.id === b.id ? updated : x); broadcastUsage.value = await saasApi.broadcastUsage(); notice.value = updated.enabled ? 'Broadcast enabled.' : 'Broadcast disabled.' })
}

async function changeVisibility(b: Broadcast, value: 'PUBLIC' | 'PRIVATE') {
  await perform(async () => { const updated = await saasApi.setBroadcastVisibility(b.id, value); broadcasts.value = broadcasts.value.map(x => x.id === b.id ? updated : x) })
}

async function copyBroadcast(b: Broadcast, embed = false) {
  await perform(async () => {
    const info = await saasApi.playback(b.id)
    const watch = new URL(`/watch/${info.publicId}`, window.location.origin)
    if (info.visibility === 'PRIVATE') watch.searchParams.set('tenant', selectedTenant.value)
    const url = watch.toString()
    if (embed) watch.searchParams.set('embed', '1')
    await navigator.clipboard.writeText(embed ? `<iframe src="${watch}" title="Live broadcast" referrerpolicy="no-referrer" allow="autoplay; fullscreen; picture-in-picture" allowfullscreen loading="lazy"></iframe>` : url)
    notice.value = embed ? 'iframe HTML copied.' : 'Permanent watch URL copied.'
  })
}

async function invite() {
  await perform(async () => { const result = await saasApi.invite(selectedTenant.value, inviteForm.value.email.trim(), inviteForm.value.role); invitations.value = [result, ...invitations.value]; invitationUrl.value = result.invitationUrl ?? ''; inviteForm.value.email = ''; notice.value = invitationUrl.value ? 'Invitation created. Copy the invitation link now.' : 'Invitation created. Email delivery is not configured.' })
}

async function copyInvitationLink() {
  if (!invitationUrl.value) return
  try {
    await navigator.clipboard.writeText(invitationUrl.value)
    notice.value = 'Invitation link copied.'
  } catch {
    error.value = 'The invitation link could not be copied in this browser.'
  }
}

async function acceptInvitation() {
  const token = invitationToken.value || sessionStorage.getItem('streaming.pendingInvitationToken') || decodeURIComponent(window.location.hash.slice(1).replace(/^token=/, ''))
  if (!token) { error.value = 'Invitation token is missing.'; return }
  await perform(async () => { await saasApi.acceptInvitation(token); sessionStorage.removeItem('streaming.pendingInvitationToken'); sessionStorage.removeItem('streaming.postLoginPath'); invitationToken.value = ''; await router.replace('/app/members'); await loadBase(); notice.value = 'Invitation accepted.' })
}

async function perform(action: () => Promise<void>) {
  error.value = ''; notice.value = ''; busy.value = true
  try { await action() } catch (e) { error.value = messageFor(e) }
  finally { busy.value = false }
}

async function changeMemberRole(member: Member, event: Event) {
  const next = (event.target as HTMLSelectElement).value as Role
  await perform(async () => { await saasApi.changeRole(selectedTenant.value, member.userId, next); members.value = await saasApi.members(selectedTenant.value); notice.value = 'Member role updated.' })
}

async function removeMember(member: Member) {
  if (!window.confirm(`Remove ${member.email || member.displayName || 'this member'} from ${organization.value?.name}?`)) return
  await perform(async () => { await saasApi.removeMember(selectedTenant.value, member.userId); members.value = await saasApi.members(selectedTenant.value); memberships.value = await saasApi.memberships(); notice.value = 'Membership removed.' })
}

async function leaveOrganization() {
  if (!window.confirm(`Leave ${organization.value?.name}?`)) return
  await perform(async () => { await saasApi.leave(selectedTenant.value); await loadBase(); notice.value = 'You left the organization.' })
}

async function revokeInvitation(invitation: Invitation) {
  await perform(async () => { await saasApi.revokeInvitation(selectedTenant.value, invitation.id); invitations.value = invitations.value.filter(i => i.id !== invitation.id); notice.value = 'Invitation revoked.' })
}

function clearError() {
  error.value = 'Your organization access changed or was revoked. Refreshing your organizations.'
  if (refreshingAccess) return
  refreshingAccess = true
  void loadBase().finally(() => { refreshingAccess = false })
}
function expireSession() { error.value = 'Your session expired. Redirecting to sign in…'; void requireLogin() }

onMounted(() => { window.addEventListener('streaming:access-changed', clearError); window.addEventListener('streaming:session-expired', expireSession); void loadBase() })
onUnmounted(() => { window.removeEventListener('streaming:access-changed', clearError); window.removeEventListener('streaming:session-expired', expireSession) })
</script>

<template>
  <div class="saas-shell">
    <aside class="saas-sidebar">
      <div class="brand"><span class="brand-mark">S</span><span>Stream<span class="brand-light">line</span></span></div>
      <label class="org-label" for="org-select">WORKSPACE</label>
      <select id="org-select" :value="selectedTenant" @change="selectOrganization(($event.target as HTMLSelectElement).value)">
        <option value="" disabled>Select organization</option>
        <option v-for="org in organizations" :key="org.id" :value="org.id">{{ org.name }}</option>
      </select>
      <button class="new-org-button" @click="promptCreateOrganization">＋ New organization</button>
      <nav aria-label="SaaS navigation">
        <RouterLink v-for="item in navigation" :key="item.id" :to="`/app/${item.id}`" :class="{ active: section === item.id }"><span>{{ item.icon }}</span>{{ item.label }}</RouterLink>
      </nav>
      <div class="sidebar-bottom"><span class="role-pill">{{ role ?? 'No workspace' }}</span><button v-if="selectedTenant" class="link-button" @click="leaveOrganization">Leave organization</button></div>
    </aside>
    <main class="saas-main">
      <header class="saas-topbar"><div><span class="eyebrow">{{ organization?.name ?? 'Streaming workspace' }}</span><h1>{{ navigation.find(i => i.id === section)?.label ?? 'Invitation' }}</h1></div><div class="account"><span class="avatar">{{ (identity?.username ?? 'U').slice(0, 1).toUpperCase() }}</span><span>{{ identity?.username ?? 'Account' }}<small>{{ identity?.email }}</small></span><button class="quiet" @click="logout">Sign out</button></div></header>
      <div v-if="loading" class="panel loading-state">Loading your workspace…</div>
      <template v-else>
        <div v-if="error" class="alert error" role="alert">{{ error }} <button class="quiet" @click="error = ''; loadBase()">Retry</button></div>
        <div v-if="notice" class="alert success" role="status"><span>{{ notice }}</span> <button v-if="invitationUrl" class="quiet" @click="copyInvitationLink">Copy invitation link</button> <button class="quiet" @click="notice = ''; invitationUrl = ''">Dismiss</button></div>
        <section v-if="route.name === 'invitation-acceptance'" class="panel onboarding"><div class="icon-tile">✉</div><h2>Accept organization invitation</h2><p>Sign in with the invited, verified email address to accept.</p><form class="inline-form" @submit.prevent="acceptInvitation"><input v-model="invitationToken" placeholder="Invitation token (or use invite link fragment)"><button :disabled="busy">Accept invitation</button></form><small v-if="identityUser && (identityUser.emailVerified !== true)">Your identity provider must mark this email as verified before acceptance.</small></section>
        <section v-else-if="!organizations.length" class="panel onboarding"><div class="icon-tile">✦</div><h2>Create your first organization</h2><p>Your cameras, broadcasts and members will be organized in one workspace.</p><form @submit.prevent="createOrganization"><input v-model="newOrgName" required maxlength="120" placeholder="Organization name"><button :disabled="busy">Create organization</button></form></section>
        <template v-else>
          <section v-if="section === 'overview'" class="content-grid">
            <div class="welcome panel"><div><span class="eyebrow">WORKSPACE OVERVIEW</span><h2>Good to see you, {{ identity?.username ?? 'there' }}.</h2><p>Manage your live camera sources and share broadcasts from {{ organization?.name }}.</p><button v-if="role === 'OWNER'" class="link-button rename-action" @click="renameOrganization">Rename organization</button></div><RouterLink class="primary-link" to="/app/cameras">Add a camera <span>→</span></RouterLink></div>
            <div class="stat-card panel"><span>CAMERAS</span><strong>{{ cameraUsage ? `${cameraUsage.used} / ${cameraUsage.limit}` : cameras.length }}</strong><small>{{ cameraUsage?.used != null && cameraUsage.used >= cameraUsage.limit ? 'Camera limit reached' : 'Registered cameras' }}</small></div><div class="stat-card panel"><span>ACTIVE BROADCASTS</span><strong>{{ broadcastUsage ? `${broadcastUsage.used} / ${broadcastUsage.limit}` : broadcasts.filter(b => b.enabled).length }}</strong><small>{{ broadcastUsage?.used != null && broadcastUsage.used >= broadcastUsage.limit ? 'Active broadcast limit reached' : 'Enabled broadcasts' }}</small></div><div class="stat-card panel"><span>VIEWERS</span><strong>{{ viewerUsage ? `${viewerUsage.used} / ${viewerUsage.limit}` : 'unavailable' }}</strong><small>Active playback sessions</small></div><div class="stat-card panel"><span>MEMBERS</span><strong>{{ members.length }}</strong><small>{{ role }} access</small></div>
            <p v-if="viewerUsage && viewerUsage.remaining === 0" class="alert error wide">Viewer limit reached. New players cannot start until an active player closes or expires.</p>
            <div class="panel wide"><div class="panel-heading"><div><h3>Recent broadcasts</h3><p>Share stable watch links; media authorization renews separately.</p></div><RouterLink to="/app/broadcasts">View all</RouterLink></div><p v-if="!broadcasts.length" class="empty-copy">No broadcasts yet. Create one from an owned camera.</p><div v-else class="compact-list"><div v-for="b in broadcasts.slice(0, 4)" :key="b.id"><span class="live-indicator" :class="{ muted: !b.enabled }"></span><strong>{{ cameras.find(c => c.id === b.cameraId)?.name ?? 'Camera' }}</strong><span>{{ b.enabled ? 'Enabled' : 'Offline' }} · {{ b.visibility }}</span><RouterLink :to="`/app/broadcasts`">Manage</RouterLink></div></div></div>
          </section>
          <section v-else-if="section === 'cameras'" class="stack">
            <div v-if="canOperate" class="panel"><div class="panel-heading"><div><h3>Add camera</h3><p>Connection details are sent securely and never shown in camera listings.</p></div></div><form class="form-grid" @submit.prevent="createCamera"><label>Camera name<input v-model="cameraForm.name" required maxlength="120" placeholder="Front entrance"></label><label class="span-2">RTSP URL<input v-model="cameraForm.rtspUrl" required type="password" placeholder="rtsp://host:554/path" autocomplete="off"></label><label>RTSP username<input v-model="cameraForm.rtspUsername" autocomplete="off"></label><label>RTSP password<input v-model="cameraForm.rtspPassword" type="password" autocomplete="new-password"></label><div class="span-2 form-actions"><button :disabled="busy">Add camera</button></div></form></div>
            <div class="panel"><div class="panel-heading"><div><h3>Your cameras</h3><p>Usage: {{ cameraUsage ? `${cameraUsage.used} of ${cameraUsage.limit}` : 'unavailable' }} cameras. Limits are enforced by the server.</p></div><button class="secondary" @click="loadTenantData">Refresh</button></div><p v-if="cameraUsage && cameraUsage.remaining === 0" class="alert error">Camera limit reached. Delete a camera or change the organization plan to add another.</p><p v-if="!cameras.length" class="empty-copy">No cameras in this workspace yet.</p><div v-else class="table-wrap"><table><thead><tr><th>Camera</th><th>Status</th><th>Last seen</th><th>Broadcast</th><th></th></tr></thead><tbody><tr v-for="camera in cameras" :key="camera.id"><td><strong>{{ camera.name }}</strong><small>#{{ camera.cameraNumber ?? camera.id.slice(0, 8) }}</small></td><td><span class="status-tag" :class="String(camera.status).toLowerCase()">{{ camera.status }}</span></td><td>{{ camera.lastHeartbeat ? new Date(camera.lastHeartbeat).toLocaleString() : '—' }}</td><td>{{ broadcasts.some(b => b.cameraId === camera.id) ? 'Configured' : '—' }}</td><td><button v-if="canOperate && editCameraId !== camera.id" class="link-button" @click="beginCameraEdit(camera)">Edit</button></td></tr></tbody></table></div><form v-if="editCameraId" class="form-grid camera-edit" @submit.prevent="saveCameraEdit"><p class="span-2 empty-copy">For security, the saved RTSP URL and credentials are not shown. Re-enter the source URL to update this camera.</p><label>Camera name<input v-model="editCameraForm.name" required></label><label>New RTSP URL<input v-model="editCameraForm.rtspUrl" type="password" required autocomplete="off"></label><label>RTSP username<input v-model="editCameraForm.rtspUsername" autocomplete="off"></label><label>RTSP password<input v-model="editCameraForm.rtspPassword" type="password" autocomplete="new-password"></label><div class="span-2 form-actions"><button :disabled="busy">Save camera</button><button type="button" class="secondary" @click="editCameraId = ''">Cancel</button></div></form></div>
          </section>
          <section v-else-if="section === 'broadcasts'" class="stack"><div v-if="canOperate" class="panel"><div class="panel-heading"><div><h3>Create broadcast</h3><p>Broadcasts reuse the organization camera stream.</p></div></div><form class="form-grid" @submit.prevent="createBroadcast"><label>Camera<select v-model="broadcastCamera" required><option value="" disabled>Select camera</option><option v-for="camera in cameras" :key="camera.id" :value="camera.id">{{ camera.name }}</option></select></label><label>Visibility<select v-model="broadcastVisibility"><option value="PRIVATE">Private</option><option value="PUBLIC">Public</option></select></label><div class="form-actions"><button :disabled="busy || !cameras.length">Create broadcast</button></div></form></div>
            <div class="panel"><div class="panel-heading"><div><h3>Broadcasts</h3><p>Active usage: {{ broadcastUsage ? `${broadcastUsage.used} of ${broadcastUsage.limit}` : 'unavailable' }}. Permanent URLs contain no playback grants.</p></div><button class="secondary" @click="loadTenantData">Refresh</button></div><p v-if="broadcastUsage && broadcastUsage.remaining === 0" class="alert error">Active broadcast limit reached. Disable a broadcast or change the organization plan to enable another.</p><p v-if="!broadcasts.length" class="empty-copy">No broadcasts yet. Select an owned camera to get started.</p><div v-else class="broadcast-list"><article v-for="b in broadcasts" :key="b.id" class="broadcast-card"><div class="broadcast-top"><div><span class="status-tag" :class="b.enabled ? 'online' : 'offline'">{{ b.enabled ? 'LIVE' : 'DISABLED' }}</span><h3>{{ cameras.find(c => c.id === b.cameraId)?.name ?? 'Camera' }}</h3><small>{{ b.publicId }}</small></div><label class="visibility-select">Visibility<select :value="b.visibility" :disabled="!canOperate" @change="changeVisibility(b, ($event.target as HTMLSelectElement).value as 'PUBLIC'|'PRIVATE')"><option>PRIVATE</option><option>PUBLIC</option></select></label></div><div class="broadcast-actions"><button v-if="canOperate" :class="b.enabled ? 'danger' : ''" @click="toggleBroadcast(b)">{{ b.enabled ? 'Disable' : 'Enable' }}</button><a class="secondary-button" :href="`/watch/${b.publicId}${b.visibility === 'PRIVATE' ? `?tenant=${selectedTenant}` : ''}`" target="_blank" rel="noopener noreferrer">Open watch page</a><button class="secondary" @click="copyBroadcast(b)">Copy watch URL</button><button class="secondary" @click="copyBroadcast(b, true)">Copy iframe HTML</button></div></article></div></div></section>
          <section v-else-if="section === 'members'" class="stack">
            <div v-if="canManage" class="panel"><div class="panel-heading"><div><h3>Invite a member</h3><p>No email is sent yet; development invite links are shown once.</p></div></div><form class="inline-form" @submit.prevent="invite"><input v-model="inviteForm.email" type="email" required placeholder="name@example.com"><select v-model="inviteForm.role"><option value="VIEWER">VIEWER</option><option value="OPERATOR">OPERATOR</option><option v-if="role === 'OWNER'" value="ADMIN">ADMIN</option><option v-if="role === 'OWNER'" value="OWNER">OWNER</option></select><button :disabled="busy">Create invitation</button></form></div>
            <div class="panel"><div class="panel-heading"><div><h3>Members</h3><p>Organization roles determine the available actions.</p></div></div><div class="table-wrap"><table><thead><tr><th>Member</th><th>Role</th><th>Joined</th><th></th></tr></thead><tbody><tr v-for="member in members" :key="member.userId"><td><strong>{{ member.displayName || member.email || 'Member' }}</strong><small>{{ member.email }}</small></td><td><select v-if="canManage && (role === 'OWNER' || ['OPERATOR','VIEWER'].includes(member.role))" :value="member.role" @change="changeMemberRole(member, $event)"><option value="VIEWER">VIEWER</option><option value="OPERATOR">OPERATOR</option><option v-if="role === 'OWNER'" value="ADMIN">ADMIN</option><option v-if="role === 'OWNER'" value="OWNER">OWNER</option></select><span v-else class="role-pill">{{ member.role }}</span></td><td>{{ new Date(member.joinedAt).toLocaleDateString() }}</td><td><button v-if="canManage && member.userId !== identityUser?.id && (role === 'OWNER' || ['OPERATOR','VIEWER'].includes(member.role))" class="link-button danger-text" @click="removeMember(member)">Remove</button></td></tr></tbody></table></div><p v-if="!members.length" class="empty-copy">No members found.</p></div>
            <div v-if="canManage" class="panel"><div class="panel-heading"><div><h3>Pending invitations</h3><p>Invitations expire automatically after seven days.</p></div></div><p v-if="!invitations.length" class="empty-copy">No pending invitations.</p><div v-else class="compact-list"><div v-for="invitation in invitations" :key="invitation.id"><strong>{{ invitation.email }}</strong><span>{{ invitation.role }} · expires {{ new Date(invitation.expiresAt).toLocaleDateString() }}</span><button v-if="role === 'OWNER' || ['OPERATOR','VIEWER'].includes(invitation.role)" class="link-button danger-text" @click="revokeInvitation(invitation)">Revoke</button></div></div></div>
          </section>
          <section v-else class="panel onboarding"><h2>Page not found</h2><RouterLink to="/app">Return to overview</RouterLink></section>
          <footer class="app-footer">Streaming workspace · {{ organization?.slug ?? 'No organization' }}<span>Access is always verified by the server.</span></footer>
        </template>
      </template>
    </main>
  </div>
</template>

<style scoped>
.saas-shell{min-height:100vh;background:#f3f6fb;color:#172338;display:grid;grid-template-columns:250px minmax(0,1fr);font-family:Inter,system-ui,sans-serif}.saas-sidebar{position:sticky;top:0;height:100vh;background:#111d32;color:#d9e2f2;padding:25px 16px;display:flex;flex-direction:column;gap:16px}.brand{display:flex;align-items:center;gap:11px;font-weight:750;font-size:19px;padding:0 8px;color:white}.brand-mark,.avatar{display:grid;place-items:center;width:36px;height:36px;border-radius:11px;background:#526fe0;color:white}.org-label,.eyebrow{font-size:10px;font-weight:800;letter-spacing:1px;color:#8494ad}.org-label{margin:10px 8px -10px}.saas-sidebar select,.saas-main select,.saas-main input{min-width:0;border:1px solid #d9e1ec;border-radius:8px;background:white;padding:10px 11px;color:#1d2b40;font:inherit}.saas-sidebar select{background:#1c2b44;color:white;border-color:#34445f}.new-org-button{width:100%;justify-content:flex-start;background:transparent;color:#aab9cf;padding:7px 9px;font-size:12px}.new-org-button:hover{background:#253650;color:white}.saas-sidebar nav{display:grid;gap:6px}.saas-sidebar nav a{display:flex;align-items:center;gap:12px;padding:11px 12px;border-radius:9px;color:#aab9cf;text-decoration:none;font-size:14px}.saas-sidebar nav a:hover,.saas-sidebar nav a.active{background:#253650;color:white}.sidebar-bottom{margin-top:auto;display:grid;gap:14px;border-top:1px solid #2c3b52;padding:17px 8px 0}.role-pill{display:inline-flex;width:max-content;padding:5px 9px;border-radius:99px;background:#edf2fa;color:#495975;font-size:10px;font-weight:800}.sidebar-bottom .role-pill{background:#2b3c57;color:#c9d7ea}.link-button,.quiet{border:0;background:none;color:inherit;padding:4px;cursor:pointer;text-align:left;font:inherit;font-size:12px}.saas-main{min-width:0;max-width:1500px;width:100%;padding:0 clamp(20px,4vw,56px) 30px;margin:auto}.saas-topbar{min-height:105px;display:flex;align-items:center;justify-content:space-between;gap:20px;border-bottom:1px solid #e1e7ef;margin-bottom:26px}.saas-topbar h1{font-size:24px;margin:5px 0 0}.account{display:flex;align-items:center;gap:10px;font-size:13px;font-weight:700}.account small{display:block;font-weight:400;color:#8390a3;margin-top:3px}.account .quiet{color:#63748c;margin-left:8px}.panel{background:#fff;border:1px solid #e3e9f1;border-radius:14px;padding:22px;box-shadow:0 3px 14px #243b5c08}.panel h3{margin:0 0 5px;font-size:16px}.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:18px}.panel-heading p,.onboarding p,.welcome p{color:#718099;font-size:13px;margin:0}.loading-state{text-align:center;color:#697992;padding:55px}.alert{border-radius:10px;padding:13px 16px;margin:0 0 16px;font-size:13px}.alert.error{background:#fff0ef;color:#a53c36}.alert.success{background:#eaf8f1;color:#21704c}.alert .quiet{float:right}.content-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px}.welcome{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;gap:24px;padding:28px}.welcome h2{font-size:23px;margin:9px 0}.rename-action{display:block;color:#426fe0;margin:12px 0 0}.primary-link,button,.secondary-button{display:inline-flex;align-items:center;justify-content:center;gap:8px;border:0;border-radius:8px;background:#416fe4;color:#fff;padding:10px 14px;font:inherit;font-size:13px;font-weight:700;cursor:pointer;text-decoration:none}.primary-link{white-space:nowrap}.stat-card{display:grid;gap:8px}.stat-card>span{font-size:10px;letter-spacing:.8px;color:#8190a6;font-weight:800}.stat-card strong{font-size:31px}.stat-card small{color:#8390a3}.wide{grid-column:1/-1}.stack{display:grid;grid-template-columns:minmax(0,1fr);min-width:0;gap:16px}.empty-copy{color:#8a96a8;font-size:13px;padding:14px 0 4px}.compact-list{display:grid}.compact-list>div{display:flex;align-items:center;gap:12px;padding:13px 0;border-top:1px solid #edf0f5;font-size:12px}.compact-list strong{min-width:130px}.compact-list span:not(.live-indicator){color:#7b899e;flex:1}.compact-list a{color:#416fe4;text-decoration:none;font-weight:700}.live-indicator{width:8px;height:8px;border-radius:50%;background:#21b77a}.live-indicator.muted{background:#a7b1c0}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:15px}.form-grid label,.visibility-select{display:grid;gap:7px;color:#64738a;font-size:12px;font-weight:650}.span-2{grid-column:span 2}.form-actions{display:flex;justify-content:flex-end;gap:8px}.inline-form{display:flex;gap:10px}.inline-form input{flex:1}.inline-form select{width:150px}.secondary{background:#eef2f8;color:#52627a}.danger{background:#b74343}.secondary-button{background:#eef2f8;color:#52627a}.table-wrap{overflow-x:auto}table{border-collapse:collapse;width:100%;text-align:left;font-size:13px}th{padding:9px 12px;color:#8995a6;font-size:10px;letter-spacing:.6px;text-transform:uppercase}td{padding:13px 12px;border-top:1px solid #edf0f5;color:#57667d}td strong,td small{display:block}td strong{color:#26364d}td small{color:#8a96a7;font-size:11px;margin-top:3px}td select{padding:7px!important;font-size:12px!important}.status-tag{display:inline-block;padding:5px 8px;border-radius:20px;font-size:10px;font-weight:800;background:#edf1f6;color:#65748b}.status-tag.online{background:#e4f7ed;color:#258153}.status-tag.offline{background:#fff0ee;color:#b14d44}.broadcast-list{display:grid;gap:12px}.broadcast-card{border:1px solid #e8edf4;border-radius:11px;padding:16px}.broadcast-top{display:flex;justify-content:space-between;gap:18px}.broadcast-top h3{margin:8px 0 3px}.broadcast-top small{font-size:11px;color:#8995a7}.visibility-select{min-width:140px}.broadcast-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:15px}.broadcast-actions button,.broadcast-actions a{font-size:11px;padding:8px 10px}.danger-text{color:#b74343!important}.onboarding{max-width:560px;margin:7vh auto;text-align:center;padding:40px}.onboarding h2{margin:0 0 9px}.onboarding form{display:flex;gap:9px;margin-top:22px}.onboarding input{flex:1}.app-footer{display:flex;justify-content:space-between;gap:10px;color:#8a96a7;font-size:11px;padding:22px 2px}
@media(max-width:850px){.saas-shell{grid-template-columns:minmax(0,1fr)}.saas-sidebar{position:relative;min-width:0;width:100%;height:auto;padding:13px 16px;gap:12px}.brand{font-size:17px}.org-label{display:none}.saas-sidebar select{width:100%;max-width:100%}.saas-sidebar nav{display:flex;overflow:auto}.saas-sidebar nav a{white-space:nowrap;padding:9px}.sidebar-bottom{display:none}.saas-main{padding:0 16px 24px}.saas-topbar{min-height:85px}.content-grid{grid-template-columns:1fr 1fr}.welcome,.wide{grid-column:1/-1}}
@media(max-width:560px){.saas-topbar{align-items:flex-start;padding:17px 0;flex-direction:column}.account{width:100%}.content-grid{grid-template-columns:1fr}.welcome{align-items:flex-start;flex-direction:column}.form-grid{grid-template-columns:1fr}.span-2{grid-column:auto}.inline-form{flex-direction:column}.inline-form select{width:100%}.broadcast-top{flex-direction:column}.visibility-select{max-width:180px}.app-footer{flex-direction:column}.onboarding form{flex-direction:column}}
</style>
