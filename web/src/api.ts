import type { ApiError, Conversation, MessagePage, TokenResponse, User } from './types'
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

  private async raw<T>(path: string, init: RequestInit, authenticated: boolean): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    if (init.body) headers.set('Content-Type', 'application/json')
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
    return response.json() as Promise<T>
  }

  private acceptTokens(tokens: TokenResponse): void {
    this.accessToken = tokens.accessToken
    this.user = tokens.user
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken)
    localStorage.setItem(USER_KEY, JSON.stringify(tokens.user))
  }

  private clearSession(): void {
    this.accessToken = null
    this.user = null
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }
}
