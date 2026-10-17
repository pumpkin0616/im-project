import type { ApiError, Attachment, BlockedUser, Conversation, Friend, FriendRequest, MessagePage, RecallResult, TokenResponse, UploadTicket, User, UserSearchResult } from './types'
import { stableDeviceId } from './uuid'

const REFRESH_KEY = 'im.refreshToken'
const USER_KEY = 'im.user'

export class ApiClient {
  private accessToken: string | null = null
  private refreshPromise: Promise<TokenResponse | null> | null = null
  user: User | null = null

  constructor() {
    const saved = localStorage.getItem(USER_KEY)
    if (saved) {
      try { this.user = JSON.parse(saved) as User } catch { localStorage.removeItem(USER_KEY) }
    }
  }

  async restore(): Promise<User | null> {
    if (!localStorage.getItem(REFRESH_KEY)) return null
    const tokens = await this.refresh()
    return tokens?.user ?? null
  }

  async login(account: string, password: string): Promise<User> {
    const tokens = await this.raw<TokenResponse>('/api/v1/auth/login', {
      method: 'POST', body: JSON.stringify({ account, password, deviceId: stableDeviceId() })
    }, false)
    this.acceptTokens(tokens)
    return tokens.user
  }

  async register(account: string, nickname: string, password: string): Promise<User> {
    const tokens = await this.raw<TokenResponse>('/api/v1/auth/register', {
      method: 'POST', body: JSON.stringify({ account, nickname, password, deviceId: stableDeviceId() })
    }, false)
    this.acceptTokens(tokens)
    return tokens.user
  }

  async logout(): Promise<void> {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    try {
      if (refreshToken && this.accessToken) await this.request('/api/v1/auth/logout', {
        method: 'POST', body: JSON.stringify({ refreshToken })
      })
    } finally {
      this.clearSession()
    }
  }

  async conversations(): Promise<Conversation[]> {
    const values: Conversation[] = []
    let cursor: string | undefined
    do {
      const query = new URLSearchParams({ limit: '100' })
      if (cursor) query.set('cursor', cursor)
      const page = await this.request<{ items: Conversation[]; hasMore: boolean; nextCursor?: string }>(
        `/api/v1/conversations?${query}`)
      values.push(...page.items)
      cursor = page.hasMore ? page.nextCursor : undefined
    } while (cursor && values.length < 500)
    return values
  }

  conversationPage(cursor?: string): Promise<{ items: Conversation[]; hasMore: boolean; nextCursor?: string }> {
    const query = new URLSearchParams({ limit: '50' })
    if (cursor) query.set('cursor', cursor)
    return this.request(`/api/v1/conversations?${query}`)
  }

  conversation(id: string): Promise<Conversation> {
    return this.request(`/api/v1/conversations/${id}`)
  }

  messages(id: string, options: { afterSeq?: number; beforeSeq?: number; limit?: number } = {}): Promise<MessagePage> {
    const query = new URLSearchParams({ limit: String(options.limit ?? 50) })
    if (options.afterSeq !== undefined) query.set('afterSeq', String(options.afterSeq))
    if (options.beforeSeq !== undefined) query.set('beforeSeq', String(options.beforeSeq))
    return this.request(`/api/v1/conversations/${id}/messages?${query}`)
  }

  async sync(cursors: Array<{ conversationId: string; afterSeq: number }>): Promise<Array<{ conversationId: string; page: MessagePage }>> {
    const pages: Array<{ conversationId: string; page: MessagePage }> = []
    for (let offset = 0; offset < cursors.length; offset += 20) {
      const response = await this.request<{ conversations: Array<{ conversationId: string; page: MessagePage }> }>('/api/v1/sync', {
        method: 'POST', body: JSON.stringify({ conversations: cursors.slice(offset, offset + 20), limitPerConversation: 100 })
      })
      pages.push(...response.conversations)
    }
    return pages
  }

  createDirect(peerUserId: string): Promise<Conversation> {
    return this.request('/api/v1/conversations/direct', {
      method: 'POST', body: JSON.stringify({ peerUserId })
    })
  }

  createGroup(title: string, memberIds: string[]): Promise<Conversation> {
    return this.request('/api/v1/conversations/group', {
      method: 'POST', body: JSON.stringify({ title, memberIds })
    })
  }

  updateProfile(nickname: string): Promise<User> {
    return this.request<User>('/api/v1/users/me', {
      method: 'PUT', body: JSON.stringify({ nickname })
    }).then(user => this.acceptUser(user))
  }

  uploadAvatar(file: File): Promise<User> {
    const body = new FormData(); body.set('file', file)
    return this.request<User>('/api/v1/users/me/avatar', { method: 'POST', body })
      .then(user => this.acceptUser(user))
  }

  deleteAvatar(): Promise<void> {
    return this.request<void>('/api/v1/users/me/avatar', { method: 'DELETE' }).then(() => {
      if (this.user) this.acceptUser({ ...this.user, avatarKey: null })
    })
  }

  searchUsers(query: string): Promise<UserSearchResult[]> {
    return this.request(`/api/v1/users/search?q=${encodeURIComponent(query)}&limit=20`)
  }

  friends(): Promise<Friend[]> {
    return this.request('/api/v1/friends')
  }

  friendRequests(direction: 'incoming' | 'outgoing'): Promise<FriendRequest[]> {
    return this.request(`/api/v1/friend-requests?direction=${direction}`)
  }

  sendFriendRequest(recipientId: string, note: string): Promise<FriendRequest> {
    return this.request('/api/v1/friend-requests', {
      method: 'POST', body: JSON.stringify({ recipientId, note: note.trim() || null })
    })
  }

  handleFriendRequest(id: string, action: 'accept' | 'reject'): Promise<FriendRequest> {
    return this.request(`/api/v1/friend-requests/${id}/${action}`, { method: 'POST', body: '{}' })
  }

  cancelFriendRequest(id: string): Promise<FriendRequest> {
    return this.request(`/api/v1/friend-requests/${id}`, { method: 'DELETE' })
  }

  removeFriend(id: string): Promise<void> {
    return this.request(`/api/v1/friends/${id}`, { method: 'DELETE' })
  }

  blockUser(id: string): Promise<void> {
    return this.request(`/api/v1/blocks/${id}`, { method: 'PUT', body: '{}' })
  }

  blockedUsers(): Promise<BlockedUser[]> { return this.request('/api/v1/blocks') }
  unblockUser(id: string): Promise<void> { return this.request(`/api/v1/blocks/${id}`, { method: 'DELETE' }) }

  updateConversation(id: string, title: string): Promise<Conversation> {
    return this.request(`/api/v1/conversations/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) })
  }
  addMembers(id: string, userIds: string[]): Promise<Conversation> {
    return this.request(`/api/v1/conversations/${id}/members`, { method: 'POST', body: JSON.stringify({ userIds }) })
  }
  removeMember(id: string, userId: string): Promise<void> {
    return this.request(`/api/v1/conversations/${id}/members/${userId}`, { method: 'DELETE' })
  }
  updateMemberRole(id: string, userId: string, role: 'MEMBER' | 'ADMIN'): Promise<Conversation> {
    return this.request(`/api/v1/conversations/${id}/members/${userId}/role`, { method: 'PUT', body: JSON.stringify({ role }) })
  }
  leaveConversation(id: string): Promise<void> { return this.request(`/api/v1/conversations/${id}/leave`, { method: 'POST', body: '{}' }) }
  dissolveConversation(id: string): Promise<void> { return this.request(`/api/v1/conversations/${id}/dissolve`, { method: 'POST', body: '{}' }) }

  recallMessage(conversationId: string, seq: number): Promise<RecallResult> {
    return this.request(`/api/v1/conversations/${conversationId}/messages/${seq}/recall`, { method: 'POST', body: '{}' })
  }

  async uploadAttachment(conversationId: string, file: File): Promise<Attachment> {
    const ticket = await this.request<UploadTicket>(`/api/v1/conversations/${conversationId}/attachments`, {
      method: 'POST', body: JSON.stringify({ fileName: file.name, mimeType: file.type || 'application/octet-stream', sizeBytes: file.size })
    })
    await this.request<void>(ticket.uploadUrl, { method: 'PUT', body: file, headers: { 'Content-Type': 'application/octet-stream' } })
    return this.request(ticket.completeUrl, { method: 'POST', body: '{}' })
  }

  async downloadAttachment(url: string): Promise<Blob> {
    try { return await this.raw<Blob>(url, { headers: { Accept: '*/*' } }, true, true) }
    catch (error) {
      if ((error as ApiError).status !== 401 || !await this.refresh()) throw error
      return this.raw<Blob>(url, { headers: { Accept: '*/*' } }, true, true)
    }
  }

  async downloadAvatar(userId: string): Promise<Blob> {
    const path = `/api/v1/users/${userId}/avatar`
    try { return await this.raw<Blob>(path, { headers: { Accept: 'image/*' } }, true, true) }
    catch (error) {
      if ((error as ApiError).status !== 401 || !await this.refresh()) throw error
      return this.raw<Blob>(path, { headers: { Accept: 'image/*' } }, true, true)
    }
  }

  sendHttp(payload: Record<string, unknown>): Promise<Record<string, unknown>> {
    return this.request('/api/v1/messages', { method: 'POST', body: JSON.stringify(payload) })
  }

  markRead(conversationId: string, seq: number): Promise<unknown> {
    return this.request(`/api/v1/conversations/${conversationId}/read`, {
      method: 'PUT', body: JSON.stringify({ seq })
    })
  }

  ticket(): Promise<{ websocketUrl: string }> {
    return this.request('/api/v1/ws/tickets', { method: 'POST', body: '{}' })
  }

  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    try {
      return await this.raw<T>(path, init, true)
    } catch (error) {
      const apiError = error as ApiError
      if (apiError.status !== 401 || path.includes('/auth/refresh')) throw error
      const refreshed = await this.refresh()
      if (!refreshed) throw error
      return this.raw<T>(path, init, true)
    }
  }

  private async refresh(): Promise<TokenResponse | null> {
    if (this.refreshPromise) return this.refreshPromise
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (!refreshToken) return null
    this.refreshPromise = this.raw<TokenResponse>('/api/v1/auth/refresh', {
      method: 'POST', body: JSON.stringify({ refreshToken })
    }, false).then(tokens => {
      this.acceptTokens(tokens)
      return tokens
    }).catch(() => {
      this.clearSession()
      return null
    }).finally(() => { this.refreshPromise = null })
    return this.refreshPromise
  }

  private async raw<T>(path: string, init: RequestInit, authenticated: boolean, blob = false): Promise<T> {
    const headers = new Headers(init.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    if (init.body && !(init.body instanceof FormData) && !(init.body instanceof Blob) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (authenticated && this.accessToken) headers.set('Authorization', `Bearer ${this.accessToken}`)
    const response = await fetch(path, { ...init, headers })
    if (!response.ok) {
      const body = await response.json().catch(() => ({})) as Record<string, unknown>
      const error = new Error(String(body.message ?? `请求失败（${response.status}）`)) as ApiError
      error.status = response.status
      error.code = typeof body.code === 'string' ? body.code : undefined
      error.retryable = body.retryable === true
      throw error
    }
    if (response.status === 204) return undefined as T
    if (blob) return response.blob() as Promise<T>
    return response.json() as Promise<T>
  }

  private acceptTokens(tokens: TokenResponse): void {
    this.accessToken = tokens.accessToken
    this.user = tokens.user
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
    localStorage.setItem(USER_KEY, JSON.stringify(tokens.user))
  }

  private acceptUser(user: User): User {
    this.user = user
    localStorage.setItem(USER_KEY, JSON.stringify(user))
    return user
  }

  private clearSession(): void {
    this.accessToken = null
    this.user = null
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }
}
