export type User = {
  id: string
  account: string
  nickname: string
  avatarKey?: string | null
  createdAt: string
}

export type TokenResponse = {
  tokenType: string
  accessToken: string
  expiresIn: number
  refreshToken: string
  user: User
}

export type Member = {
  userId: string
  account: string
  nickname: string
  avatarKey?: string | null
  role: 'OWNER' | 'ADMIN' | 'MEMBER'
  joinedSeq: number
  lastReadSeq: number
  joinedAt: string
}

export type Conversation = {
  id: string
  type: 'DIRECT' | 'GROUP'
  title?: string | null
  ownerId?: string | null
  status: string
  myRole: string
  lastSeq: number
  minAvailableSeq: number
  memberVersion: number
  memberCount: number
  lastMessageAt?: string | null
  createdAt: string
  members?: Member[] | null
}

export type Attachment = {
  id: string
  fileName: string
  mimeType: string
  sizeBytes: number
  sha256?: string
  downloadUrl: string
}

export type Message = {
  id: string
  seq: number
  senderId: string
  clientMsgId: string
  type: 'TEXT' | 'IMAGE' | 'FILE' | 'UNKNOWN'
  content: { text?: string; [key: string]: unknown }
  createdAt: string
  attachments: Attachment[]
  delivery?: 'sending' | 'sent' | 'failed'
}

export type MessagePage = {
  items: Message[]
  direction: 'AFTER' | 'BEFORE'
  hasMore: boolean
  nextBeforeSeq?: number | null
  nextAfterSeq?: number | null
  minAvailableSeq: number
  visibleFromSeq: number
  highWatermark: number
}

export type ApiError = Error & { code?: string; status?: number; retryable?: boolean }

export type GatewayEnvelope = {
  v: 1
  op: string
  requestId?: string
  data: Record<string, unknown>
}

export type ConnectionState = 'offline' | 'connecting' | 'online' | 'reconnecting'
