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
  myLastReadSeq: number
  unreadCount: number
  lastMessage?: {
    seq: number
    type: 'TEXT' | 'IMAGE' | 'FILE' | 'UNKNOWN'
    status: 'NORMAL' | 'RECALLED'
    senderId: string
    summary: string
    createdAt: string
  } | null
  peer?: {
    userId: string
    account: string
    nickname: string
    avatarUrl?: string | null
  } | null
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
  status: 'NORMAL' | 'RECALLED'
  content: { text?: string; [key: string]: unknown }
  createdAt: string
  recalledBy?: string | null
  recalledAt?: string | null
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

export type Friend = {
  id: string
  account: string
  nickname: string
  avatarKey?: string | null
  friendsSince: string
}

export type FriendRequest = {
  id: string
  requesterId: string
  requesterAccount: string
  requesterNickname: string
  recipientId: string
  recipientAccount: string
  recipientNickname: string
  status: 0 | 1 | 2 | 3
  note?: string | null
  createdAt: string
  handledAt?: string | null
}

export type UserRelationship = 'SELF' | 'FRIEND' | 'INCOMING_PENDING' | 'OUTGOING_PENDING' | 'BLOCKED' | 'NONE'

export type UserSearchResult = {
  id: string
  account: string
  nickname: string
  avatarKey?: string | null
  relationship: UserRelationship
  pendingRequestId?: string | null
  friend: boolean
  pendingIncoming: boolean
  pendingOutgoing: boolean
  blockedByMe: boolean
}

export type BlockedUser = {
  id: string
  account: string
  nickname: string
  avatarKey?: string | null
  blockedAt: string
}

export type RecallResult = {
  conversationId: string
  messageId: string
  seq: number
  recalledBy: string
  recalledAt: string
  status: 'RECALLED'
}

export type UploadTicket = {
  id: string
  conversationId: string
  status: string
  uploadUrl: string
  completeUrl: string
  expiresAt: string
}
