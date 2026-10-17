import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowLeft, Check, CheckCheck, CircleAlert, LoaderCircle, LogOut, MessageCircle,
  MoreHorizontal, Plus, RefreshCw, Search, Send, Users, Wifi, WifiOff, X
} from 'lucide-react'
import { ApiClient } from './api'
import { RealtimeClient } from './realtime'
import { loadMessages, saveMessages } from './storage'
import type { ApiError, ConnectionState, Conversation, GatewayEnvelope, Message, User } from './types'
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
    content: (data.content ?? {}) as Message['content'], createdAt: String(data.createdAt),
    attachments: (data.attachments ?? []) as Message['attachments'], delivery: 'sent'
  }
}

function conversationName(conversation: Conversation, currentUser: string, detail?: Conversation): string {
  if (conversation.type === 'GROUP') return conversation.title || '未命名群聊'
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
        <label>账号<input autoFocus required minLength={3} value={account} onChange={e => setAccount(e.target.value)} placeholder="请输入账号" /></label>
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
  const [mobileList, setMobileList] = useState(true)
  const realtimeRef = useRef<RealtimeClient | null>(null)
  const conversationsRef = useRef<Conversation[]>([])
  const messagesRef = useRef<Record<string, Message[]>>({})
  const selectedRef = useRef<string | null>(null)
  const pendingRef = useRef(new Map<string, { conversationId: string; clientMsgId: string }>())
  const bottomRef = useRef<HTMLDivElement>(null)

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
      ? { ...item, lastSeq: Math.max(item.lastSeq, high), lastMessageAt: incoming.at(-1)?.createdAt ?? item.lastMessageAt }
      : item).sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? '')))
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
      applyMessages(conversationId, [messageFromGateway(envelope.data)])
      if (selectedRef.current === conversationId) {
        const sequence = Number(envelope.data.seq)
        realtimeRef.current?.send('READ', { conversationId, seq: String(sequence) })
      }
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
  }, [applyMessages, synchronize])

  useEffect(() => {
    let active = true
    api.conversations().then(items => {
      if (!active) return
      setConversations(items)
      setSelectedId(items[0]?.id ?? null)
    }).catch(failure => setNotice((failure as Error).message)).finally(() => setLoading(false))
    const realtime = new RealtimeClient(api, handleEnvelope, setConnection)
    realtimeRef.current = realtime
    realtime.start()
    return () => { active = false; realtime.stop(); realtimeRef.current = null }
  }, [handleEnvelope])

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
      content: { text }, createdAt: new Date().toISOString(), attachments: [], delivery: 'sending'
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

  async function logout() {
    realtimeRef.current?.stop()
    await api.logout()
    onLoggedOut()
  }

  async function created(conversation: Conversation) {
    setShowCreate(false)
    setConversations(current => [conversation, ...current.filter(item => item.id !== conversation.id)])
    setDetails(current => ({ ...current, [conversation.id]: conversation }))
    setSelectedId(conversation.id)
  }

  const selected = conversations.find(item => item.id === selectedId)
  const detail = selectedId ? details[selectedId] : undefined
  const visible = useMemo(() => conversations.filter(item => conversationName(item, user.id, details[item.id])
    .toLowerCase().includes(search.toLowerCase())), [conversations, details, search, user.id])

  return <main className="app-shell">
    <aside className={`sidebar ${mobileList ? 'mobile-visible' : ''}`}>
      <header className="sidebar-header">
        <div className="brand-mark small"><MessageCircle size={19} /><span>LIN</span></div>
        <button className="icon-button" title="退出登录" onClick={logout}><LogOut size={18} /></button>
      </header>
      <div className="profile-strip">
        <div className="avatar own">{initials(user.nickname)}</div>
        <div><strong>{user.nickname}</strong><small>@{user.account}</small></div>
        <ConnectionBadge state={connection} />
      </div>
      <div className="conversation-tools">
        <div className="search-box"><Search size={17} /><input value={search} onChange={e => setSearch(e.target.value)} placeholder="搜索会话" /></div>
        <button className="new-chat" onClick={() => setShowCreate(true)}><Plus size={18} />新建</button>
      </div>
      <div className="section-label"><span>消息</span><span>{visible.length}</span></div>
      <div className="conversation-list">
        {loading && <div className="list-empty"><LoaderCircle className="spin" />正在载入会话</div>}
        {!loading && !visible.length && <div className="list-empty"><MessageCircle /><span>还没有会话</span><small>新建单聊或群聊开始交流</small></div>}
        {visible.map(item => {
          const name = conversationName(item, user.id, details[item.id])
          const last = messages[item.id]?.at(-1)
          return <button key={item.id} className={`conversation-item ${selectedId === item.id ? 'active' : ''}`} onClick={() => setSelectedId(item.id)}>
            <div className={`avatar ${item.type === 'GROUP' ? 'group' : ''}`}>{item.type === 'GROUP' ? <Users size={20} /> : initials(name)}</div>
            <div className="conversation-copy"><div><strong>{name}</strong><time>{formatListTime(item.lastMessageAt)}</time></div>
              <p>{last?.content.text || (item.lastSeq ? `已有 ${item.lastSeq} 条消息` : '开始一段新对话')}</p></div>
          </button>
        })}
      </div>
    </aside>

    <section className={`chat-panel ${!mobileList ? 'mobile-visible' : ''}`}>
      {!selected ? <div className="welcome-empty"><div className="empty-orbit"><MessageCircle size={34} /></div><h2>选择一个会话</h2><p>你的消息会在这里实时出现。</p></div> : <>
        <header className="chat-header">
          <button className="icon-button mobile-back" onClick={() => setMobileList(true)}><ArrowLeft /></button>
          <div className={`avatar ${selected.type === 'GROUP' ? 'group' : ''}`}>{selected.type === 'GROUP' ? <Users size={20} /> : initials(conversationName(selected, user.id, detail))}</div>
          <div className="chat-title"><strong>{conversationName(selected, user.id, detail)}</strong><small>{selected.type === 'GROUP' ? `${detail?.memberCount ?? selected.memberCount} 位成员` : connection === 'online' ? '实时连接中' : '消息将可靠送达'}</small></div>
          <button className="icon-button"><MoreHorizontal /></button>
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
                  <div className={`bubble ${message.delivery === 'failed' ? 'failed' : ''}`}><p>{message.content.text || `[${message.type}]`}</p>
                    <span>{formatTime(message.createdAt)} {mine && (message.delivery === 'sending' ? <LoaderCircle className="spin" size={13} /> : message.delivery === 'failed' ? <CircleAlert size={13} /> : <CheckCheck size={13} />)}</span></div>
                </div>
              </div>
            </div>
          })}
          {!(messages[selected.id]?.length) && <div className="conversation-empty"><span>这是对话的开始</span><p>发送第一条消息，和对方打个招呼。</p></div>}
          <div ref={bottomRef} />
        </div>
        <form className="composer" onSubmit={sendMessage}>
          <textarea value={composer} onChange={e => setComposer(e.target.value)} placeholder="输入消息…" rows={1}
            onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit() } }} />
          <button disabled={!composer.trim()} title="发送"><Send size={19} /></button>
        </form>
      </>}
    </section>
    {notice && <div className="toast"><CircleAlert size={17} /><span>{notice}</span><button onClick={() => setNotice('')}><X size={16} /></button></div>}
    {showCreate && <CreateConversation onClose={() => setShowCreate(false)} onCreated={created} />}
  </main>
}

function ConnectionBadge({ state }: { state: ConnectionState }) {
  const online = state === 'online'
  return <div className={`connection-badge ${online ? 'online' : ''}`}>{online ? <Wifi size={13} /> : <WifiOff size={13} />}<span>{online ? '在线' : state === 'connecting' ? '连接中' : state === 'reconnecting' ? '重连中' : '离线'}</span></div>
}

function CreateConversation({ onClose, onCreated }: { onClose: () => void; onCreated: (value: Conversation) => void }) {
  const [type, setType] = useState<'DIRECT' | 'GROUP'>('DIRECT')
  const [peer, setPeer] = useState('')
  const [title, setTitle] = useState('')
  const [members, setMembers] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const value = type === 'DIRECT' ? await api.createDirect(peer.trim())
        : await api.createGroup(title.trim(), members.split(/[\s,，]+/).map(value => value.trim()).filter(Boolean))
      onCreated(value)
    } catch (failure) { setError((failure as ApiError).message) }
    finally { setBusy(false) }
  }
  return <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget) onClose() }}>
    <form className="modal" onSubmit={submit}>
      <header><div><p className="eyebrow">NEW CONVERSATION</p><h3>新建会话</h3></div><button type="button" className="icon-button" onClick={onClose}><X /></button></header>
      <div className="segmented"><button type="button" className={type === 'DIRECT' ? 'active' : ''} onClick={() => setType('DIRECT')}>单聊</button><button type="button" className={type === 'GROUP' ? 'active' : ''} onClick={() => setType('GROUP')}>群聊</button></div>
      {type === 'DIRECT' ? <label>好友用户 ID<input required value={peer} onChange={e => setPeer(e.target.value)} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" /><small>只有互为好友的用户可以创建单聊。</small></label> : <>
        <label>群名称<input required value={title} onChange={e => setTitle(e.target.value)} placeholder="例如：项目讨论组" /></label>
        <label>成员用户 ID<input required value={members} onChange={e => setMembers(e.target.value)} placeholder="多个 ID 用逗号分隔" /><small>当前账号会自动成为群主。</small></label>
      </>}
      {error && <div className="form-error"><CircleAlert size={16} />{error}</div>}
      <button className="primary-button" disabled={busy}>{busy ? <LoaderCircle className="spin" size={18} /> : <Check size={18} />}创建会话</button>
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
