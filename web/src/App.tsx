import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, Ban, Check, CheckCheck, ChevronDown, CircleAlert, ContactRound, Inbox, LoaderCircle,
  Download, FileText, LogOut, MessageCircle, Paperclip, Pencil, Plus, RefreshCw, Search, Send,
  Settings, Shield, ShieldOff, Trash2, Undo2, UserMinus,
  UserPlus, UserRoundCheck, Users, Wifi, WifiOff, X
} from 'lucide-react'
import { ApiClient } from './api'
import { RealtimeClient } from './realtime'
import { loadMessages, saveMessages } from './storage'
import type { ApiError, BlockedUser, ConnectionState, Conversation, Friend, FriendRequest, GatewayEnvelope,
  Member, Message, User, UserSearchResult } from './types'
import { uuidV7 } from './uuid'

const api = new ApiClient()

function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  const byId = new Map<string, Message>()
  const byClient = new Map<string, string>()
  for (const message of current) {
    byId.set(message.id, message)
    byClient.set(message.clientMsgId, message.id)
  }
  for (const message of incoming) {
    const priorId = byClient.get(message.clientMsgId)
    if (priorId && priorId !== message.id) byId.delete(priorId)
    const existing = byId.get(message.id)
    byId.set(message.id, { ...existing, ...message, delivery: message.delivery ?? existing?.delivery ?? 'sent' })
    byClient.set(message.clientMsgId, message.id)
  }
  return Array.from(byId.values()).sort((a, b) => {
    if (!a.seq && !b.seq) return a.createdAt.localeCompare(b.createdAt)
    if (!a.seq) return 1
    if (!b.seq) return -1
    return a.seq - b.seq
  })
}

function messageFromGateway(data: Record<string, unknown>): Message {
  return {
    id: String(data.messageId), seq: Number(data.seq), senderId: String(data.senderId),
    clientMsgId: String(data.clientMsgId), type: String(data.type) as Message['type'],
    status: (data.status ?? 'NORMAL') as Message['status'],
    content: (data.content ?? {}) as Message['content'], createdAt: String(data.createdAt),
    attachments: (data.attachments ?? []) as Message['attachments'], delivery: 'sent'
  }
}

function conversationName(conversation: Conversation, currentUser: string, detail?: Conversation): string {
  if (conversation.type === 'GROUP') return conversation.title || '未命名群聊'
  if (conversation.peer) return conversation.peer.nickname || conversation.peer.account
  const peer = detail?.members?.find(member => member.userId !== currentUser)
  return peer?.nickname || peer?.account || `单聊 · ${conversation.id.slice(0, 8)}`
}

function initials(value: string): string {
  const clean = value.trim()
  return clean ? clean.slice(0, 2).toUpperCase() : 'IM'
}

export default function App() {
  const [booting, setBooting] = useState(true)
  const [user, setUser] = useState<User | null>(api.user)

  useEffect(() => {
    api.restore().then(setUser).finally(() => setBooting(false))
  }, [])

  if (booting) return <div className="splash"><LoaderCircle className="spin" /><span>正在恢复会话</span></div>
  if (!user) return <AuthScreen onAuthenticated={setUser} />
  return <ChatShell user={user} onLoggedOut={() => setUser(null)} />
}

function AuthScreen({ onAuthenticated }: { onAuthenticated: (user: User) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [account, setAccount] = useState('')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const authenticated = mode === 'login'
        ? await api.login(account.trim(), password)
        : await api.register(account.trim(), nickname.trim(), password)
      onAuthenticated(authenticated)
    } catch (failure) {
      setError((failure as Error).message)
    } finally { setBusy(false) }
  }

  return <main className="auth-page">
    <section className="auth-story">
      <div className="brand-mark"><MessageCircle size={23} /><span>LIN</span></div>
      <div className="story-copy">
        <p className="eyebrow">REALTIME, RELIABLE, YOURS</p>
        <h1>让每一次交流<br />都稳稳抵达。</h1>
        <p>可靠消息、断线续传与多端同步，安静地工作在每一句话背后。</p>
      </div>
      <div className="signal-card">
        <span className="pulse-dot" />
        <div><strong>服务在线</strong><small>端到端实时链路已就绪</small></div>
      </div>
    </section>
    <section className="auth-panel">
      <form className="auth-form" onSubmit={submit}>
        <div>
          <p className="eyebrow">欢迎回来</p>
          <h2>{mode === 'login' ? '登录你的账号' : '创建新的账号'}</h2>
          <p className="muted">{mode === 'login' ? '继续刚才的对话。' : '只需一分钟，即刻开始交流。'}</p>
        </div>
        <label>IM ID<input autoFocus required minLength={3} maxLength={128} value={account} onChange={e => setAccount(e.target.value)} placeholder="用于登录和添加好友" /></label>
        {mode === 'register' && <label>昵称<input required maxLength={64} value={nickname} onChange={e => setNickname(e.target.value)} placeholder="大家怎么称呼你" /></label>}
        <label>密码<input required minLength={8} type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="至少 8 位" /></label>
        {error && <div className="form-error"><CircleAlert size={16} />{error}</div>}
        <button className="primary-button" disabled={busy}>{busy && <LoaderCircle className="spin" size={18} />}{mode === 'login' ? '登录' : '注册并登录'}</button>
        <button type="button" className="text-button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError('') }}>
          {mode === 'login' ? '还没有账号？立即注册' : '已有账号？返回登录'}
        </button>
      </form>
    </section>
  </main>
}

function ChatShell({ user, onLoggedOut }: { user: User; onLoggedOut: () => void }) {
  const [profile, setProfile] = useState(user)
  const [section, setSection] = useState<'messages' | 'contacts'>('messages')
  const [conversations, setConversations] = useState<Conversation[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [details, setDetails] = useState<Record<string, Conversation>>({})
  const [messages, setMessages] = useState<Record<string, Message[]>>({})
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [loading, setLoading] = useState(true)
  const [loadingHistory, setLoadingHistory] = useState(false)
  const [composer, setComposer] = useState('')
  const [search, setSearch] = useState('')
  const [notice, setNotice] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [showMembers, setShowMembers] = useState(false)
  const [showProfile, setShowProfile] = useState(false)
  const [membersLoading, setMembersLoading] = useState(false)
  const [mobileList, setMobileList] = useState(true)
  const [friends, setFriends] = useState<Friend[]>([])
  const [incoming, setIncoming] = useState<FriendRequest[]>([])
  const [outgoing, setOutgoing] = useState<FriendRequest[]>([])
  const [blocked, setBlocked] = useState<BlockedUser[]>([])
  const [contactsLoading, setContactsLoading] = useState(true)
  const [contactView, setContactView] = useState<'friends' | 'requests' | 'add' | 'blocks'>('friends')
  const [selectedFriendId, setSelectedFriendId] = useState<string | null>(null)
  const [contactFilter, setContactFilter] = useState('')
  const [nextConversationCursor, setNextConversationCursor] = useState<string | undefined>()
  const [loadingMoreConversations, setLoadingMoreConversations] = useState(false)
  const [uploading, setUploading] = useState(false)
  const realtimeRef = useRef<RealtimeClient | null>(null)
  const conversationsRef = useRef<Conversation[]>([])
  const messagesRef = useRef<Record<string, Message[]>>({})
  const selectedRef = useRef<string | null>(null)
  const pendingRef = useRef(new Map<string, { conversationId: string; clientMsgId: string }>())
  const discoveringRef = useRef(new Set<string>())
  const bottomRef = useRef<HTMLDivElement>(null)
  const fileRef = useRef<HTMLInputElement>(null)

  useEffect(() => { conversationsRef.current = conversations }, [conversations])
  useEffect(() => { messagesRef.current = messages }, [messages])
  useEffect(() => { selectedRef.current = selectedId }, [selectedId])

  const applyMessages = useCallback((conversationId: string, incoming: Message[]) => {
    setMessages(current => {
      const merged = mergeMessages(current[conversationId] ?? [], incoming)
      void saveMessages(user.id, conversationId, merged)
      return { ...current, [conversationId]: merged }
    })
    const high = incoming.reduce((maximum, message) => Math.max(maximum, message.seq), 0)
    if (high) setConversations(current => current.map(item => item.id === conversationId
      ? { ...item, lastSeq: Math.max(item.lastSeq, high), lastMessageAt: incoming.at(-1)?.createdAt ?? item.lastMessageAt,
          lastMessage: incoming.at(-1) ? {
            seq: incoming.at(-1)!.seq, type: incoming.at(-1)!.type, status: incoming.at(-1)!.status,
            senderId: incoming.at(-1)!.senderId,
            summary: incoming.at(-1)!.status === 'RECALLED' ? '一条消息已撤回'
              : incoming.at(-1)!.content.text || (incoming.at(-1)!.type === 'IMAGE' ? '[图片]' : '[文件]'),
            createdAt: incoming.at(-1)!.createdAt
          } : item.lastMessage,
          unreadCount: selectedRef.current === conversationId ? 0 : item.unreadCount
            + incoming.filter(message => message.seq > item.lastSeq && message.senderId !== user.id).length }
      : item).sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? '')))
  }, [user.id])

  const applyRecall = useCallback((conversationId: string, sequence: number, recalledBy: string, recalledAt: string) => {
    setMessages(current => {
      const updated = (current[conversationId] ?? []).map(message => message.seq === sequence
        ? { ...message, status: 'RECALLED' as const, content: {}, attachments: [], recalledBy, recalledAt }
        : message)
      void saveMessages(user.id, conversationId, updated)
      return { ...current, [conversationId]: updated }
    })
    setConversations(current => current.map(item => item.id === conversationId && item.lastMessage?.seq === sequence
      ? { ...item, lastMessage: { ...item.lastMessage, status: 'RECALLED', summary: '一条消息已撤回' } }
      : item))
  }, [user.id])

  const synchronize = useCallback(async () => {
    const cursors = conversationsRef.current.map(conversation => ({
      conversationId: conversation.id,
      afterSeq: (messagesRef.current[conversation.id] ?? []).reduce((max, message) => Math.max(max, message.seq), 0)
    }))
    if (!cursors.length) return
    try {
      const deltas = await api.sync(cursors)
      await Promise.all(deltas.map(async delta => {
        let page = delta.page
        applyMessages(delta.conversationId, page.items)
        while (page.hasMore && page.nextAfterSeq) {
          page = await api.messages(delta.conversationId, { afterSeq: page.nextAfterSeq, limit: 100 })
          applyMessages(delta.conversationId, page.items)
        }
      }))
    } catch (failure) { setNotice((failure as Error).message) }
  }, [applyMessages])

  const handleEnvelope = useCallback((envelope: GatewayEnvelope) => {
    if (envelope.op === 'READY') void synchronize()
    if (envelope.op === 'MESSAGE') {
      const conversationId = String(envelope.data.conversationId)
      const message = messageFromGateway(envelope.data)
      applyMessages(conversationId, [message])
      if (!conversationsRef.current.some(item => item.id === conversationId) && !discoveringRef.current.has(conversationId)) {
        discoveringRef.current.add(conversationId)
        void api.conversation(conversationId).then(conversation => {
          const discovered = { ...conversation, lastSeq: Math.max(conversation.lastSeq, message.seq), lastMessageAt: message.createdAt }
          setDetails(current => ({ ...current, [conversationId]: discovered }))
          setConversations(current => current.some(item => item.id === conversationId)
            ? current : [discovered, ...current])
        }).catch(failure => setNotice((failure as Error).message))
          .finally(() => discoveringRef.current.delete(conversationId))
      }
      if (selectedRef.current === conversationId) {
        const sequence = Number(envelope.data.seq)
        realtimeRef.current?.send('READ', { conversationId, seq: String(sequence) })
      }
    }
    if (envelope.op === 'MESSAGE_RECALLED') {
      applyRecall(String(envelope.data.conversationId), Number(envelope.data.seq),
        String(envelope.data.recalledBy), String(envelope.data.recalledAt))
    }
    if (envelope.op === 'DIRECTORY_EVENT') {
      void reloadDirectory()
      if (String(envelope.data.eventType).startsWith('FRIEND_')) void loadContacts(true)
    }
    if (envelope.op === 'SEND_ACK') {
      const conversationId = String(envelope.data.conversationId)
      const clientMsgId = String(envelope.data.clientMsgId)
      setMessages(current => ({ ...current, [conversationId]: (current[conversationId] ?? []).map(message =>
        message.clientMsgId === clientMsgId ? {
          ...message, id: String(envelope.data.messageId), seq: Number(envelope.data.seq),
          createdAt: String(envelope.data.serverTime), delivery: 'sent'
        } : message) }))
      if (envelope.requestId) pendingRef.current.delete(envelope.requestId)
    }
    if (envelope.op === 'ERROR' && envelope.requestId) {
      const pending = pendingRef.current.get(envelope.requestId)
      if (pending) {
        setMessages(current => ({ ...current, [pending.conversationId]: (current[pending.conversationId] ?? []).map(message =>
          message.clientMsgId === pending.clientMsgId ? { ...message, delivery: 'failed' } : message) }))
        pendingRef.current.delete(envelope.requestId)
      }
      setNotice(String(envelope.data.message ?? '实时操作失败'))
    }
  }, [applyMessages, applyRecall, synchronize])

  useEffect(() => {
    let active = true
    api.conversationPage().then(page => {
      if (!active) return
      setConversations(page.items)
      setNextConversationCursor(page.hasMore ? page.nextCursor : undefined)
      setSelectedId(page.items[0]?.id ?? null)
    }).catch(failure => setNotice((failure as Error).message)).finally(() => setLoading(false))
    const realtime = new RealtimeClient(api, handleEnvelope, setConnection)
    realtimeRef.current = realtime
    realtime.start()
    return () => { active = false; realtime.stop(); realtimeRef.current = null }
  }, [handleEnvelope])

  async function reloadDirectory() {
    try {
      const page = await api.conversationPage()
      setConversations(page.items)
      setNextConversationCursor(page.hasMore ? page.nextCursor : undefined)
      const detailId = selectedRef.current
      if (detailId) {
        try { const value = await api.conversation(detailId); setDetails(current => ({ ...current, [detailId]: value })) }
        catch { setSelectedId(null) }
      }
    } catch (failure) { setNotice((failure as Error).message) }
  }

  async function loadMoreConversations() {
    if (!nextConversationCursor || loadingMoreConversations) return
    setLoadingMoreConversations(true)
    try {
      const page = await api.conversationPage(nextConversationCursor)
      setConversations(current => [...current, ...page.items.filter(item => !current.some(old => old.id === item.id))])
      setNextConversationCursor(page.hasMore ? page.nextCursor : undefined)
    } catch (failure) { setNotice((failure as Error).message) }
    finally { setLoadingMoreConversations(false) }
  }

  const loadContacts = useCallback(async (quiet = false) => {
    if (!quiet) setContactsLoading(true)
    try {
      const [friendValues, incomingValues, outgoingValues, blockedValues] = await Promise.all([
        api.friends(), api.friendRequests('incoming'), api.friendRequests('outgoing'), api.blockedUsers()
      ])
      setFriends(friendValues); setIncoming(incomingValues); setOutgoing(outgoingValues); setBlocked(blockedValues)
    } catch (failure) { if (!quiet) setNotice((failure as Error).message) }
    finally { if (!quiet) setContactsLoading(false) }
  }, [])

  useEffect(() => {
    void loadContacts()
    const timer = window.setInterval(() => void loadContacts(true), 30_000)
    return () => window.clearInterval(timer)
  }, [loadContacts])

  useEffect(() => {
    if (!selectedId) return
    setMobileList(false)
    let active = true
    Promise.all([loadMessages(user.id, selectedId), api.conversation(selectedId)]).then(([cached, detail]) => {
      if (!active) return
      setDetails(current => ({ ...current, [selectedId]: detail }))
      if (cached.length) applyMessages(selectedId, cached)
      const last = cached.reduce((max, message) => Math.max(max, message.seq), 0)
      return api.messages(selectedId, last ? { afterSeq: last, limit: 100 } : { limit: 50 })
    }).then(page => {
      if (!active || !page) return
      applyMessages(selectedId, page.items)
      if (page.highWatermark) markRead(selectedId, page.highWatermark)
    }).catch(failure => setNotice((failure as Error).message))
    return () => { active = false }
  }, [applyMessages, selectedId, user.id])

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages, selectedId])

  function markRead(conversationId: string, sequence: number) {
    if (!sequence) return
    if (!realtimeRef.current?.send('READ', { conversationId, seq: String(sequence) })) {
      void api.markRead(conversationId, sequence).catch(() => undefined)
    }
    setConversations(current => current.map(item => item.id === conversationId
      ? { ...item, unreadCount: 0, myLastReadSeq: Math.max(item.myLastReadSeq, sequence) } : item))
  }

  async function loadOlder() {
    if (!selectedId || loadingHistory) return
    const first = (messages[selectedId] ?? []).find(message => message.seq > 0)
    if (!first) return
    setLoadingHistory(true)
    try {
      const page = await api.messages(selectedId, { beforeSeq: first.seq, limit: 50 })
      applyMessages(selectedId, page.items)
    } catch (failure) { setNotice((failure as Error).message) }
    finally { setLoadingHistory(false) }
  }

  async function sendMessage(event: FormEvent) {
    event.preventDefault()
    const text = composer.trim()
    if (!selectedId || !text) return
    setComposer('')
    const clientMsgId = uuidV7()
    const optimistic: Message = {
      id: `pending:${clientMsgId}`, seq: 0, senderId: user.id, clientMsgId, type: 'TEXT',
      status: 'NORMAL', content: { text }, createdAt: new Date().toISOString(), attachments: [], delivery: 'sending'
    }
    applyMessages(selectedId, [optimistic])
    const payload = { conversationId: selectedId, clientMsgId, type: 'TEXT', content: { text }, attachmentIds: [] }
    const requestId = crypto.randomUUID()
    const sent = realtimeRef.current?.send('SEND', payload, requestId)
    if (sent) {
      pendingRef.current.set(requestId, { conversationId: selectedId, clientMsgId })
      return
    }
    try {
      const ack = await api.sendHttp(payload)
      applyMessages(selectedId, [{ ...optimistic, id: String(ack.messageId), seq: Number(ack.seq),
        createdAt: String(ack.serverTime), delivery: 'sent' }])
    } catch (failure) {
      applyMessages(selectedId, [{ ...optimistic, delivery: 'failed' }])
      setNotice((failure as Error).message)
    }
  }

  async function sendFile(file: File) {
    if (!selectedId || uploading || selected?.status === 'DISSOLVED') return
    setUploading(true)
    try {
      const attachment = await api.uploadAttachment(selectedId, file)
      const clientMsgId = uuidV7()
      const type: Message['type'] = file.type.startsWith('image/') ? 'IMAGE' : 'FILE'
      const optimistic: Message = {
        id: `pending:${clientMsgId}`, seq: 0, senderId: user.id, clientMsgId, type,
        status: 'NORMAL', content: {}, createdAt: new Date().toISOString(),
        attachments: [attachment], delivery: 'sending'
      }
      applyMessages(selectedId, [optimistic])
      const payload = { conversationId: selectedId, clientMsgId, type, content: {}, attachmentIds: [attachment.id] }
      const ack = await api.sendHttp(payload)
      applyMessages(selectedId, [{ ...optimistic, id: String(ack.messageId), seq: Number(ack.seq),
        createdAt: String(ack.serverTime), delivery: 'sent' }])
    } catch (failure) { setNotice((failure as Error).message) }
    finally { setUploading(false); if (fileRef.current) fileRef.current.value = '' }
  }

  async function recallMessage(message: Message) {
    if (!selectedId) return
    try {
      const result = await api.recallMessage(selectedId, message.seq)
      applyRecall(result.conversationId, result.seq, result.recalledBy, result.recalledAt)
    } catch (failure) {
      const error = failure as ApiError
      setNotice(error.code === 'MESSAGE_RECALL_EXPIRED' ? '已超过两分钟，无法撤回' : error.message)
    }
  }

  async function retryMessage(message: Message) {
    if (!selectedId || message.delivery !== 'failed') return
    applyMessages(selectedId, [{ ...message, delivery: 'sending' }])
    try {
      const ack = await api.sendHttp({
        conversationId: selectedId, clientMsgId: message.clientMsgId, type: message.type,
        content: message.content, attachmentIds: message.attachments.map(item => item.id)
      })
      applyMessages(selectedId, [{ ...message, id: String(ack.messageId), seq: Number(ack.seq),
        createdAt: String(ack.serverTime), delivery: 'sent' }])
    } catch (failure) {
      applyMessages(selectedId, [{ ...message, delivery: 'failed' }])
      setNotice((failure as Error).message)
    }
  }

  async function downloadAttachment(attachment: Message['attachments'][number]) {
    try {
      const blob = await api.downloadAttachment(attachment.downloadUrl)
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a')
      anchor.href = url; anchor.download = attachment.fileName; anchor.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (failure) { setNotice((failure as Error).message) }
  }

  async function logout() {
    realtimeRef.current?.stop()
    await api.logout()
    onLoggedOut()
  }

  async function created(conversation: Conversation) {
    setShowCreate(false)
    setSection('messages')
    setConversations(current => [conversation, ...current.filter(item => item.id !== conversation.id)])
    setDetails(current => ({ ...current, [conversation.id]: conversation }))
    setSelectedId(conversation.id)
    setMobileList(false)
  }

  async function openFriendChat(friendId: string) {
    try { await created(await api.createDirect(friendId)) }
    catch (failure) { setNotice((failure as Error).message) }
  }

  async function openGroupMembers() {
    if (!selected || selected.type !== 'GROUP') return
    setMembersLoading(true)
    try {
      const latest = await api.conversation(selected.id)
      setDetails(current => ({ ...current, [selected.id]: latest }))
      setShowMembers(true)
    } catch (failure) { setNotice((failure as Error).message) }
    finally { setMembersLoading(false) }
  }

  const selected = conversations.find(item => item.id === selectedId)
  const detail = selectedId ? details[selectedId] : undefined
  const visible = useMemo(() => conversations.filter(item => conversationName(item, user.id, details[item.id])
    .toLowerCase().includes(search.toLowerCase())), [conversations, details, search, user.id])
  const visibleFriends = useMemo(() => friends.filter(friend => `${friend.nickname} ${friend.account}`
    .toLowerCase().includes(contactFilter.toLowerCase())), [contactFilter, friends])
  const pendingCount = incoming.filter(request => request.status === 0).length
  const selectedFriend = friends.find(friend => friend.id === selectedFriendId)

  return <main className="app-shell">
    <aside className={`sidebar ${mobileList ? 'mobile-visible' : ''}`}>
      <header className="sidebar-header">
        <div className="brand-mark small"><MessageCircle size={19} /><span>LIN</span></div>
        <button className="icon-button" title="退出登录" onClick={logout}><LogOut size={18} /></button>
      </header>
      <button className="profile-strip profile-button" onClick={() => setShowProfile(true)}>
        <UserAvatar userId={profile.id} name={profile.nickname} avatarKey={profile.avatarKey} own />
        <div><strong>{profile.nickname}</strong><small>IM ID：{profile.account}</small></div>
        <ConnectionBadge state={connection} />
        <Settings size={15} />
      </button>
      <nav className="primary-nav">
        <button className={section === 'messages' ? 'active' : ''} onClick={() => setSection('messages')}><MessageCircle size={17} />消息</button>
        <button className={section === 'contacts' ? 'active' : ''} onClick={() => { setSection('contacts'); void loadContacts() }}><ContactRound size={17} />联系人{pendingCount > 0 && <span className="nav-badge">{pendingCount > 99 ? '99+' : pendingCount}</span>}</button>
      </nav>
      {section === 'messages' ? <>
        <div className="conversation-tools">
          <div className="search-box"><Search size={17} /><input value={search} onChange={e => setSearch(e.target.value)} placeholder="搜索会话" /></div>
          <button className="new-chat" onClick={() => { setShowCreate(true); void loadContacts(true) }}><Plus size={18} />建群</button>
        </div>
        <div className="section-label"><span>消息</span><span>{visible.length}</span></div>
        <div className="conversation-list">
          {loading && <div className="list-empty"><LoaderCircle className="spin" />正在载入会话</div>}
          {!loading && !visible.length && <div className="list-empty"><MessageCircle /><span>还没有会话</span><small>从联系人中选择好友开始交流</small></div>}
          {visible.map(item => {
            const name = conversationName(item, user.id, details[item.id])
            const last = messages[item.id]?.at(-1)
            return <button key={item.id} className={`conversation-item ${selectedId === item.id ? 'active' : ''}`} onClick={() => { setSelectedId(item.id); setMobileList(false) }}>
              <div className={`avatar ${item.type === 'GROUP' ? 'group' : ''}`}>{item.type === 'GROUP' ? <Users size={20} /> : initials(name)}</div>
              <div className="conversation-copy"><div><strong>{name}</strong><time>{formatListTime(item.lastMessageAt)}</time></div>
                <p>{last?.status === 'RECALLED' ? '一条消息已撤回' : last?.content.text || item.lastMessage?.summary || (item.lastSeq ? `已有 ${item.lastSeq} 条消息` : '开始一段新对话')}</p></div>
              {item.unreadCount > 0 && <span className="unread-badge">{item.unreadCount > 99 ? '99+' : item.unreadCount}</span>}
            </button>
          })}
          {nextConversationCursor && <button className="load-directory" onClick={() => void loadMoreConversations()} disabled={loadingMoreConversations}>{loadingMoreConversations ? '载入中…' : '加载更多会话'}</button>}
        </div>
      </> : <>
        <div className="contact-actions">
          <button className={contactView === 'requests' ? 'active' : ''} onClick={() => { setContactView('requests'); setSelectedFriendId(null); setMobileList(false) }}><Inbox size={18} /><span>新的朋友</span>{pendingCount > 0 && <b>{pendingCount}</b>}</button>
          <button className={contactView === 'add' ? 'active' : ''} onClick={() => { setContactView('add'); setSelectedFriendId(null); setMobileList(false) }}><UserPlus size={18} /><span>添加好友</span></button>
          <button className={contactView === 'blocks' ? 'active' : ''} onClick={() => { setContactView('blocks'); setSelectedFriendId(null); setMobileList(false) }}><Ban size={18} /><span>黑名单</span>{blocked.length > 0 && <b>{blocked.length}</b>}</button>
        </div>
        <div className="conversation-tools contact-search"><div className="search-box"><Search size={17} /><input value={contactFilter} onChange={e => setContactFilter(e.target.value)} placeholder="搜索联系人" /></div></div>
        <div className="section-label"><span>好友</span><span>{visibleFriends.length}</span></div>
        <div className="conversation-list">
          {contactsLoading && <div className="list-empty"><LoaderCircle className="spin" />正在载入联系人</div>}
          {!contactsLoading && !visibleFriends.length && <div className="list-empty"><ContactRound /><span>还没有好友</span><small>通过 IM ID 添加好友</small></div>}
          {visibleFriends.map(friend => <button key={friend.id} className={`conversation-item ${selectedFriendId === friend.id && contactView === 'friends' ? 'active' : ''}`} onClick={() => { setContactView('friends'); setSelectedFriendId(friend.id); setMobileList(false) }}>
            <div className="avatar">{initials(friend.nickname)}</div><div className="conversation-copy"><div><strong>{friend.nickname}</strong></div><p>IM ID：{friend.account}</p></div>
          </button>)}
        </div>
      </>}
    </aside>

    <section className={`chat-panel ${!mobileList ? 'mobile-visible' : ''}`}>
      {section === 'contacts' ? <ContactsPanel user={user} view={contactView} friend={selectedFriend}
        incoming={incoming} outgoing={outgoing} blocked={blocked} loading={contactsLoading} onBack={() => setMobileList(true)}
        onChanged={loadContacts} onOpenChat={openFriendChat} onNotice={setNotice}
        onSelectView={setContactView} onFriendRemoved={() => setSelectedFriendId(null)} /> :
      !selected ? <div className="welcome-empty"><div className="empty-orbit"><MessageCircle size={34} /></div><h2>选择一个会话</h2><p>你的消息会在这里实时出现。</p></div> : <>
        <header className="chat-header">
          <button className="icon-button mobile-back" onClick={() => setMobileList(true)}><ArrowLeft /></button>
          <div className={`avatar ${selected.type === 'GROUP' ? 'group' : ''}`}>{selected.type === 'GROUP' ? <Users size={20} /> : initials(conversationName(selected, user.id, detail))}</div>
          <div className="chat-title"><strong>{conversationName(selected, user.id, detail)}</strong><small>{selected.type === 'GROUP' ? `${detail?.memberCount ?? selected.memberCount} 位成员` : connection === 'online' ? '实时连接中' : '消息将可靠送达'}</small></div>
          {selected.type === 'GROUP' && <button className="members-button" disabled={membersLoading} onClick={() => void openGroupMembers()}>{membersLoading ? <LoaderCircle className="spin" size={16} /> : <Users size={16} />}成员</button>}
        </header>
        <div className="messages-pane">
          {(messages[selected.id]?.length ?? 0) > 0 && <button className="load-older" onClick={loadOlder} disabled={loadingHistory}>{loadingHistory ? <LoaderCircle className="spin" size={15} /> : <RefreshCw size={15} />}加载更早消息</button>}
          {(messages[selected.id] ?? []).map((message, index, all) => {
            const mine = message.senderId === user.id
            const sender = detail?.members?.find(member => member.userId === message.senderId)
            const showDay = index === 0 || new Date(message.createdAt).toDateString() !== new Date(all[index - 1].createdAt).toDateString()
            return <div key={message.id}>
              {showDay && <div className="day-divider"><span>{formatDay(message.createdAt)}</span></div>}
              <div className={`message-row ${mine ? 'mine' : ''}`}>
                {!mine && <div className="avatar tiny">{initials(sender?.nickname ?? '友')}</div>}
                <div className="message-stack">{!mine && selected.type === 'GROUP' && <small>{sender?.nickname ?? '成员'}</small>}
                  <div className={`bubble ${message.delivery === 'failed' ? 'failed' : ''} ${message.status === 'RECALLED' ? 'recalled' : ''}`}>
                    {message.status === 'RECALLED' ? <p className="recall-copy">{mine ? '你撤回了一条消息' : `${sender?.nickname ?? '对方'} 撤回了一条消息`}</p>
                      : message.type === 'TEXT' ? <p>{message.content.text}</p>
                      : <MessageAttachment type={message.type} attachment={message.attachments[0]} onDownload={downloadAttachment} />}
                    <span>{formatTime(message.createdAt)} {mine && (message.delivery === 'sending' ? <LoaderCircle className="spin" size={13} /> : message.delivery === 'failed' ? <CircleAlert size={13} /> : <CheckCheck size={13} />)}</span></div>
                  {mine && message.status === 'NORMAL' && message.delivery === 'sent' && Date.now() - new Date(message.createdAt).getTime() <= 120_000 &&
                    <button className="recall-button" onClick={() => void recallMessage(message)}><Undo2 size={12} />撤回</button>}
                  {mine && message.delivery === 'failed' && <button className="recall-button" onClick={() => void retryMessage(message)}><RefreshCw size={12} />重新发送</button>}
                </div>
              </div>
            </div>
          })}
          {!(messages[selected.id]?.length) && <div className="conversation-empty"><span>这是对话的开始</span><p>发送第一条消息，和对方打个招呼。</p></div>}
          <div ref={bottomRef} />
        </div>
        {selected.status === 'DISSOLVED' ? <div className="readonly-banner">群聊已解散，历史消息仅供查看</div> : <form className="composer" onSubmit={sendMessage}>
          <input ref={fileRef} hidden type="file" onChange={event => { const file=event.target.files?.[0]; if(file)void sendFile(file) }} />
          <button type="button" className="attach-button" disabled={uploading} title="发送图片或文件" onClick={() => fileRef.current?.click()}>{uploading ? <LoaderCircle className="spin" size={18} /> : <Paperclip size={18} />}</button>
          <textarea value={composer} onChange={e => setComposer(e.target.value)} placeholder="输入消息…" rows={1}
            onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit() } }} />
          <button disabled={!composer.trim()} title="发送"><Send size={19} /></button>
        </form>}
      </>}
    </section>
    {notice && <div className="toast"><CircleAlert size={17} /><span>{notice}</span><button onClick={() => setNotice('')}><X size={16} /></button></div>}
    {showCreate && <CreateGroup friends={friends} loadingFriends={contactsLoading} onClose={() => setShowCreate(false)} onCreated={created} />}
    {showMembers && detail?.type === 'GROUP' && <GroupMembers conversation={detail} currentUserId={user.id} friends={friends}
      onClose={() => setShowMembers(false)} onChanged={value => { setDetails(current => ({ ...current, [value.id]: value })); setConversations(current => current.map(item => item.id === value.id ? value : item)) }}
      onRemoved={() => { setShowMembers(false); setSelectedId(null); void reloadDirectory() }} onNotice={setNotice} />}
    {showProfile && <ProfileModal user={profile} onClose={() => setShowProfile(false)} onChanged={next => { setProfile(next); setShowProfile(false) }} onNotice={setNotice} />}
  </main>
}

type ContactsPanelProps = {
  user: User
  view: 'friends' | 'requests' | 'add' | 'blocks'
  friend?: Friend
  incoming: FriendRequest[]
  outgoing: FriendRequest[]
  blocked: BlockedUser[]
  loading: boolean
  onBack: () => void
  onChanged: (quiet?: boolean) => Promise<void>
  onOpenChat: (friendId: string) => Promise<void>
  onNotice: (message: string) => void
  onSelectView: (view: 'friends' | 'requests' | 'add' | 'blocks') => void
  onFriendRemoved: () => void
}

function ContactsPanel({ user, view, friend, incoming, outgoing, blocked, loading, onBack, onChanged,
  onOpenChat, onNotice, onSelectView, onFriendRemoved }: ContactsPanelProps) {
  const [query, setQuery] = useState('')
  const [note, setNote] = useState('')
  const [results, setResults] = useState<UserSearchResult[]>([])
  const [searching, setSearching] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)

  async function search(event?: FormEvent) {
    event?.preventDefault()
    const value = query.trim()
    if (value.length < 3) { onNotice('请输入至少 3 个字符的 IM ID'); return }
    setSearching(true)
    try { setResults(await api.searchUsers(value)) }
    catch (failure) { onNotice((failure as Error).message) }
    finally { setSearching(false) }
  }

  async function apply(id: string, work: () => Promise<unknown>, refreshSearch = false) {
    setBusyId(id)
    try {
      await work(); await onChanged(true)
      if (refreshSearch && query.trim().length >= 3) setResults(await api.searchUsers(query.trim()))
    } catch (failure) { onNotice((failure as Error).message) }
    finally { setBusyId(null) }
  }

  async function removeFriend(block: boolean) {
    if (!friend) return
    const confirmed = window.confirm(block
      ? `确定拉黑 ${friend.nickname}？拉黑后会同时解除好友关系。`
      : `确定删除好友 ${friend.nickname}？`)
    if (!confirmed) return
    await apply(friend.id, () => block ? api.blockUser(friend.id) : api.removeFriend(friend.id))
    onFriendRemoved()
  }

  if (view === 'add') return <div className="contacts-panel">
    <header className="contacts-header"><button className="icon-button mobile-back" onClick={onBack}><ArrowLeft /></button><div><strong>添加好友</strong><small>通过唯一 IM ID 查找用户</small></div></header>
    <div className="contacts-content narrow">
      <div className="contact-hero"><div className="contact-hero-icon"><UserPlus /></div><p className="eyebrow">FIND PEOPLE</p><h2>找到你认识的人</h2><p>输入对方的 IM ID，账号完全一致的结果会排在最前。</p></div>
      <form className="people-search" onSubmit={search}><div className="search-box"><Search size={18} /><input autoFocus minLength={3} maxLength={128} value={query} onChange={event => setQuery(event.target.value)} placeholder="输入 IM ID，例如 xiaoming_01" /></div><button className="primary-button" disabled={searching}>{searching ? <LoaderCircle className="spin" size={18} /> : '搜索'}</button></form>
      <label className="request-note">申请留言（可选）<input maxLength={256} value={note} onChange={event => setNote(event.target.value)} placeholder="介绍一下自己" /></label>
      <div className="search-results">
        {results.map(result => <div className="person-card" key={result.id}><div className="avatar">{initials(result.nickname)}</div><div className="person-copy"><strong>{result.nickname}</strong><small>IM ID：{result.account}</small></div><SearchResultAction result={result} busy={busyId === result.id}
          onAdd={() => apply(result.id, () => api.sendFriendRequest(result.id, note), true)}
          onAccept={() => result.pendingRequestId && apply(result.id, () => api.handleFriendRequest(result.pendingRequestId!, 'accept'), true)}
          onChat={() => onOpenChat(result.id)} /></div>)}
        {!searching && query && !results.length && <div className="inline-empty"><Search /><strong>没有找到用户</strong><span>请检查 IM ID 是否正确。</span></div>}
      </div>
    </div>
  </div>

  if (view === 'requests') return <div className="contacts-panel">
    <header className="contacts-header"><button className="icon-button mobile-back" onClick={onBack}><ArrowLeft /></button><div><strong>新的朋友</strong><small>{incoming.filter(item => item.status === 0).length} 个申请待处理</small></div><button className="icon-button header-refresh" onClick={() => void onChanged()} title="刷新"><RefreshCw size={17} /></button></header>
    <div className="contacts-content request-page">
      <section><div className="content-title"><div><p className="eyebrow">INCOMING</p><h2>收到的申请</h2></div><span>{incoming.filter(item => item.status === 0).length} 待处理</span></div>
        {loading ? <div className="inline-empty"><LoaderCircle className="spin" /></div> : incoming.length ? <div className="request-list">{incoming.map(request => <RequestCard key={request.id} request={request} incoming busy={busyId === request.id}
          onAccept={() => apply(request.id, () => api.handleFriendRequest(request.id, 'accept'))}
          onReject={() => apply(request.id, () => api.handleFriendRequest(request.id, 'reject'))} />)}</div> : <div className="inline-empty"><Inbox /><strong>暂无好友申请</strong><span>新的申请会显示在这里。</span></div>}
      </section>
      <section><div className="content-title"><div><p className="eyebrow">SENT</p><h2>我发出的申请</h2></div></div>
        {outgoing.length ? <div className="request-list">{outgoing.map(request => <RequestCard key={request.id} request={request} busy={busyId === request.id}
          onCancel={request.status === 0 ? () => apply(request.id, () => api.cancelFriendRequest(request.id)) : undefined} />)}</div> : <div className="inline-empty compact"><span>尚未发出申请</span></div>}
      </section>
    </div>
  </div>

  if (view === 'blocks') return <div className="contacts-panel">
    <header className="contacts-header"><button className="icon-button mobile-back" onClick={onBack}><ArrowLeft /></button><div><strong>黑名单</strong><small>黑名单中的用户不能向你发起好友申请</small></div></header>
    <div className="contacts-content narrow"><div className="content-title"><div><p className="eyebrow">BLOCKED USERS</p><h2>已拉黑的用户</h2></div><span>{blocked.length}</span></div>
      {blocked.length ? <div className="request-list">{blocked.map(item => <div className="person-card" key={item.id}><div className="avatar">{initials(item.nickname)}</div><div className="person-copy"><strong>{item.nickname}</strong><small>IM ID：{item.account} · {formatDay(item.blockedAt)}</small></div><button className="small-action" disabled={busyId === item.id} onClick={() => void apply(item.id, () => api.unblockUser(item.id))}>{busyId === item.id ? <LoaderCircle className="spin" size={14} /> : <ShieldOff size={14} />}取消拉黑</button></div>)}</div>
        : <div className="inline-empty"><Ban /><strong>黑名单为空</strong><span>被拉黑的用户会显示在这里。</span></div>}
    </div>
  </div>

  if (!friend) return <div className="contacts-panel"><header className="contacts-header"><button className="icon-button mobile-back" onClick={onBack}><ArrowLeft /></button><div><strong>联系人</strong><small>{user.nickname} · IM ID：{user.account}</small></div></header><div className="welcome-empty"><div className="empty-orbit"><ContactRound size={34} /></div><h2>选择一个联系人</h2><p>可以发消息、删除好友或拉黑对方。</p><button className="soft-button" onClick={() => onSelectView('add')}><UserPlus size={17} />添加好友</button></div></div>

  return <div className="contacts-panel"><header className="contacts-header"><button className="icon-button mobile-back" onClick={onBack}><ArrowLeft /></button><div><strong>联系人资料</strong><small>好友关系建立于 {formatDay(friend.friendsSince)}</small></div></header>
    <div className="profile-card"><div className="avatar profile-avatar">{initials(friend.nickname)}</div><h2>{friend.nickname}</h2><p>IM ID：{friend.account}</p><div className="profile-actions"><button className="primary-button" onClick={() => void onOpenChat(friend.id)}><MessageCircle size={18} />发消息</button><button className="danger-button" disabled={busyId === friend.id} onClick={() => void removeFriend(false)}><Trash2 size={17} />删除好友</button><button className="danger-button strong" disabled={busyId === friend.id} onClick={() => void removeFriend(true)}><Ban size={17} />拉黑</button></div><small className="profile-hint">拉黑会解除好友关系，并阻止双方继续发送好友申请。</small></div>
  </div>
}

function SearchResultAction({ result, busy, onAdd, onAccept, onChat }: { result: UserSearchResult; busy: boolean; onAdd: () => void; onAccept: () => void; onChat: () => void }) {
  if (busy) return <button className="small-action" disabled><LoaderCircle className="spin" size={15} /></button>
  if (result.relationship === 'NONE') return <button className="small-action" onClick={onAdd}><UserPlus size={15} />添加</button>
  if (result.relationship === 'INCOMING_PENDING') return <button className="small-action" onClick={onAccept}><Check size={15} />接受</button>
  if (result.relationship === 'FRIEND') return <button className="small-action" onClick={onChat}><MessageCircle size={15} />发消息</button>
  const label = result.relationship === 'SELF' ? '这是你' : result.relationship === 'BLOCKED' ? '已拉黑' : '已申请'
  return <span className="relationship-label">{label}</span>
}

function RequestCard({ request, incoming: isIncoming = false, busy, onAccept, onReject, onCancel }: { request: FriendRequest; incoming?: boolean; busy: boolean; onAccept?: () => void; onReject?: () => void; onCancel?: () => void }) {
  const nickname = isIncoming ? request.requesterNickname : request.recipientNickname
  const account = isIncoming ? request.requesterAccount : request.recipientAccount
  const labels = ['待处理', '已接受', '已拒绝', '已取消']
  return <div className="request-card"><div className="avatar">{initials(nickname)}</div><div className="person-copy"><strong>{nickname}</strong><small>IM ID：{account} · {formatDay(request.createdAt)}</small>{request.note && <p>“{request.note}”</p>}</div>{isIncoming && request.status === 0 ? <div className="request-buttons"><button disabled={busy} className="accept" onClick={onAccept}>{busy ? <LoaderCircle className="spin" size={14} /> : <Check size={14} />}接受</button><button disabled={busy} onClick={onReject}>拒绝</button></div> : onCancel ? <div className="request-buttons"><button disabled={busy} onClick={onCancel}>{busy ? <LoaderCircle className="spin" size={14} /> : <X size={14} />}取消申请</button></div> : <span className={`request-status status-${request.status}`}>{labels[request.status]}</span>}</div>
}

function GroupMembers({ conversation, currentUserId, friends, onClose, onChanged, onRemoved, onNotice }: {
  conversation: Conversation; currentUserId: string; friends: Friend[]; onClose: () => void
  onChanged: (value: Conversation) => void; onRemoved: () => void; onNotice: (message: string) => void
}) {
  const members = conversation.members ?? []
  const [title, setTitle] = useState(conversation.title ?? '')
  const [inviteId, setInviteId] = useState('')
  const [busy, setBusy] = useState('')
  const manager = conversation.myRole === 'OWNER' || conversation.myRole === 'ADMIN'
  const owner = conversation.myRole === 'OWNER'
  const available = friends.filter(friend => !members.some(member => member.userId === friend.id))
  const roleLabels: Record<Member['role'], string> = { OWNER: '群主', ADMIN: '管理员', MEMBER: '成员' }
  const ordered = [...members].sort((a, b) => {
    const rank = { OWNER: 0, ADMIN: 1, MEMBER: 2 }
    return rank[a.role] - rank[b.role] || a.nickname.localeCompare(b.nickname, 'zh-CN')
  })
  async function apply(key: string, work: () => Promise<Conversation | void>) {
    setBusy(key)
    try { const value = await work(); if (value) onChanged(value) }
    catch (failure) { onNotice((failure as Error).message) }
    finally { setBusy('') }
  }
  async function updateTitle(event: FormEvent) {
    event.preventDefault()
    if (title.trim() === conversation.title) return
    await apply('title', () => api.updateConversation(conversation.id, title.trim()))
  }
  async function addFriend() {
    if (!inviteId) return
    await apply('invite', () => api.addMembers(conversation.id, [inviteId]))
    setInviteId('')
  }
  async function leaveOrDissolve() {
    const dissolve = owner
    if (!window.confirm(dissolve ? '确定解散群聊？所有成员将只能查看历史消息。' : '确定退出群聊？')) return
    await apply('leave', async () => {
      if (dissolve) await api.dissolveConversation(conversation.id)
      else await api.leaveConversation(conversation.id)
      onRemoved()
    })
  }
  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
    <section className="modal members-modal">
      <header><div><p className="eyebrow">GROUP MEMBERS</p><h3>群聊成员</h3><small>{conversation.title || '未命名群聊'} · 共 {members.length} 人</small></div><button type="button" className="icon-button" onClick={onClose}><X /></button></header>
      {manager && <form className="group-title-form" onSubmit={updateTitle}><input maxLength={128} value={title} onChange={event => setTitle(event.target.value)} /><button disabled={busy === 'title' || !title.trim()}><Pencil size={14} />修改群名</button></form>}
      {manager && <div className="invite-row"><select value={inviteId} onChange={event => setInviteId(event.target.value)}><option value="">选择要邀请的好友</option>{available.map(friend => <option key={friend.id} value={friend.id}>{friend.nickname}（{friend.account}）</option>)}</select><button disabled={!inviteId || busy === 'invite'} onClick={() => void addFriend()}><UserPlus size={14} />邀请</button></div>}
      <div className="members-list">
        {ordered.map(member => {
          const canRemove = manager && member.role !== 'OWNER' && member.userId !== currentUserId
            && !(conversation.myRole === 'ADMIN' && member.role === 'ADMIN')
          return <div className="member-row" key={member.userId}><div className={`avatar ${member.role === 'OWNER' ? 'own' : ''}`}>{initials(member.nickname)}</div><div className="member-copy"><strong>{member.nickname}{member.userId === currentUserId && <span>我</span>}</strong><small>IM ID：{member.account}</small></div><span className={`role-badge role-${member.role.toLowerCase()}`}>{roleLabels[member.role]}</span>
            {owner && member.role !== 'OWNER' && <button className="member-action" disabled={busy === member.userId} title={member.role === 'ADMIN' ? '取消管理员' : '设为管理员'} onClick={() => void apply(member.userId, () => api.updateMemberRole(conversation.id, member.userId, member.role === 'ADMIN' ? 'MEMBER' : 'ADMIN'))}>{member.role === 'ADMIN' ? <ShieldOff size={14} /> : <Shield size={14} />}</button>}
            {canRemove && <button className="member-action danger" disabled={busy === member.userId} title="移出群聊" onClick={() => { if (window.confirm(`确定将 ${member.nickname} 移出群聊？`)) void apply(member.userId, async () => { await api.removeMember(conversation.id, member.userId); return api.conversation(conversation.id) }) }}><UserMinus size={14} /></button>}
          </div>
        })}
        {!ordered.length && <div className="inline-empty"><Users /><strong>暂无成员信息</strong></div>}
      </div>
      <div className="modal-footer"><button type="button" className="danger-button" disabled={busy === 'leave'} onClick={() => void leaveOrDissolve()}>{owner ? '解散群聊' : '退出群聊'}</button><button type="button" className="primary-button" onClick={onClose}>完成</button></div>
    </section>
  </div>
}

function ProfileModal({ user, onClose, onChanged, onNotice }: {
  user: User; onClose: () => void; onChanged: (value: User) => void; onNotice: (message: string) => void
}) {
  const [nickname, setNickname] = useState(user.nickname)
  const [busy, setBusy] = useState(false)
  const avatarRef = useRef<HTMLInputElement>(null)
  async function save(event: FormEvent) {
    event.preventDefault(); setBusy(true)
    try { onChanged(await api.updateProfile(nickname.trim())) }
    catch (failure) { onNotice((failure as Error).message) }
    finally { setBusy(false) }
  }
  async function avatar(file: File) {
    setBusy(true)
    try { onChanged(await api.uploadAvatar(file)) }
    catch (failure) { onNotice((failure as Error).message) }
    finally { setBusy(false) }
  }
  async function removeAvatar() {
    setBusy(true)
    try { await api.deleteAvatar(); onChanged({ ...user, avatarKey: null }) }
    catch (failure) { onNotice((failure as Error).message) }
    finally { setBusy(false) }
  }
  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
    <form className="modal profile-modal" onSubmit={save}>
      <header><div><p className="eyebrow">PROFILE</p><h3>个人资料</h3></div><button type="button" className="icon-button" onClick={onClose}><X /></button></header>
      <div className="profile-editor-avatar"><UserAvatar userId={user.id} name={nickname} avatarKey={user.avatarKey} own large /><div><strong>IM ID：{user.account}</strong><small>账号 ID 创建后不能修改</small></div></div>
      <label>昵称<input required maxLength={64} value={nickname} onChange={event => setNickname(event.target.value)} /></label>
      <input ref={avatarRef} hidden type="file" accept="image/jpeg,image/png,image/webp,image/gif" onChange={event => { const file=event.target.files?.[0]; if(file)void avatar(file) }} />
      <div className="profile-avatar-actions"><button type="button" className="soft-button" disabled={busy} onClick={() => avatarRef.current?.click()}>上传或替换头像</button>{user.avatarKey && <button type="button" className="danger-button" disabled={busy} onClick={() => void removeAvatar()}>删除头像</button>}</div>
      <button className="primary-button" disabled={busy || !nickname.trim()}>{busy && <LoaderCircle className="spin" size={16} />}保存资料</button>
    </form>
  </div>
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  const online = state === 'online'
  return <div className={`connection-badge ${online ? 'online' : ''}`}>{online ? <Wifi size={13} /> : <WifiOff size={13} />}<span>{online ? '在线' : state === 'connecting' ? '连接中' : state === 'reconnecting' ? '重连中' : '离线'}</span></div>
}

function UserAvatar({ userId, name, avatarKey, own = false, large = false }: {
  userId: string; name: string; avatarKey?: string | null; own?: boolean; large?: boolean
}) {
  const [source, setSource] = useState<string>()
  useEffect(() => {
    if (!avatarKey) { setSource(undefined); return }
    let active = true; let url: string | undefined
    void api.downloadAvatar(userId).then(blob => {
      if (!active) return
      url = URL.createObjectURL(blob); setSource(url)
    }).catch(() => setSource(undefined))
    return () => { active = false; if (url) URL.revokeObjectURL(url) }
  }, [avatarKey, userId])
  return <div className={`avatar ${own ? 'own' : ''} ${large ? 'profile-avatar' : ''}`}>
    {source ? <img src={source} alt="" /> : initials(name)}
  </div>
}

function MessageAttachment({ type, attachment, onDownload }: {
  type: Message['type']; attachment?: Message['attachments'][number]
  onDownload: (value: Message['attachments'][number]) => Promise<void>
}) {
  const [preview, setPreview] = useState<string>()
  useEffect(() => {
    if (type !== 'IMAGE' || !attachment) return
    let active = true; let url: string | undefined
    void api.downloadAttachment(attachment.downloadUrl).then(blob => {
      if (!active) return
      url = URL.createObjectURL(blob); setPreview(url)
    }).catch(() => undefined)
    return () => { active = false; if (url) URL.revokeObjectURL(url) }
  }, [attachment, type])
  if (!attachment) return <div className="attachment-message"><FileText size={20} /><strong>{type === 'IMAGE' ? '图片' : '文件'}</strong></div>
  return <div className={`attachment-message ${type === 'IMAGE' ? 'image' : ''}`}>
    {preview ? <img src={preview} alt={attachment.fileName} /> : <FileText size={20} />}
    <div><strong>{attachment.fileName}</strong><small>{formatBytes(attachment.sizeBytes)}</small></div>
    <button onClick={() => void onDownload(attachment)} title="下载"><Download size={16} /></button>
  </div>
}

function CreateGroup({ friends, loadingFriends, onClose, onCreated }: { friends: Friend[]; loadingFriends: boolean; onClose: () => void; onCreated: (value: Conversation) => void }) {
  const [title, setTitle] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [selectorOpen, setSelectorOpen] = useState(false)
  const [friendQuery, setFriendQuery] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const selectable = friends.filter(friend => `${friend.nickname} ${friend.account}`.toLowerCase().includes(friendQuery.toLowerCase()))
  function toggle(friendId: string) {
    setSelected(current => current.includes(friendId) ? current.filter(id => id !== friendId) : [...current, friendId])
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      if (!selected.length) throw new Error('请至少选择一位好友')
      onCreated(await api.createGroup(title.trim(), selected))
    } catch (failure) { setError((failure as ApiError).message) }
    finally { setBusy(false) }
  }
  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
    <form className="modal group-modal" onSubmit={submit}>
      <header><div><p className="eyebrow">NEW GROUP</p><h3>创建群聊</h3></div><button type="button" className="icon-button" onClick={onClose}><X /></button></header>
      <label>群名称<input autoFocus required maxLength={128} value={title} onChange={e => setTitle(e.target.value)} placeholder="例如：项目讨论组" /></label>
      <label>群成员<div className={`friend-picker ${selectorOpen ? 'open' : ''}`}>
        <button type="button" className="friend-picker-trigger" onClick={() => setSelectorOpen(open => !open)} disabled={loadingFriends}>
          <span>{loadingFriends ? '正在载入好友…' : selected.length ? `已选择 ${selected.length} 位好友` : '从好友列表中选择'}</span><ChevronDown size={17} />
        </button>
        {selectorOpen && <div className="friend-picker-menu">
          <div className="friend-picker-search"><Search size={15} /><input value={friendQuery} onChange={event => setFriendQuery(event.target.value)} placeholder="搜索昵称或 IM ID" /></div>
          <div className="friend-picker-options">
            {selectable.map(friend => <button type="button" key={friend.id} className={selected.includes(friend.id) ? 'selected' : ''} onClick={() => toggle(friend.id)}>
              <span className="picker-check">{selected.includes(friend.id) && <Check size={13} />}</span><span className="avatar tiny">{initials(friend.nickname)}</span><span className="picker-person"><strong>{friend.nickname}</strong><small>IM ID：{friend.account}</small></span>
            </button>)}
            {!selectable.length && <div className="picker-empty">{friends.length ? '没有匹配的好友' : '好友列表为空，请先添加好友'}</div>}
          </div>
        </div>}
      </div><small>当前账号会自动成为群主，只能选择已添加的好友。</small></label>
      {selected.length > 0 && <div className="selected-friends">{selected.map(id => { const friend = friends.find(item => item.id === id); return friend && <button type="button" key={id} onClick={() => toggle(id)}>{friend.nickname}<X size={12} /></button> })}</div>}
      {error && <div className="form-error"><CircleAlert size={16} />{error}</div>}
      <button className="primary-button" disabled={busy || loadingFriends || !friends.length}>{busy ? <LoaderCircle className="spin" size={18} /> : <Users size={18} />}创建群聊</button>
    </form>
  </div>
}

function formatListTime(value?: string | null): string {
  if (!value) return ''
  const date = new Date(value), now = new Date()
  if (date.toDateString() === now.toDateString()) return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(date)
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
}
function formatTime(value: string): string { return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
function formatDay(value: string): string { return new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(value)) }
function formatBytes(value?: number): string {
  if (!value) return ''
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}
