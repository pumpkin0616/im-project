import type { ConnectionState, GatewayEnvelope } from './types'
import type { ApiClient } from './api'

type EnvelopeHandler = (envelope: GatewayEnvelope) => void
type StateHandler = (state: ConnectionState) => void

export class RealtimeClient {
  private socket: WebSocket | null = null
  private stopped = true
  private attempt = 0
  private generation = 0
  private heartbeat?: number
  private reconnectTimer?: number
  private state: ConnectionState = 'offline'

  constructor(private api: ApiClient, private onEnvelope: EnvelopeHandler, private onState: StateHandler) {}

  start(): void {
    if (!this.stopped) return
    this.stopped = false
    this.attempt = 0
    this.connect()
  }

  stop(): void {
    this.stopped = true
    this.generation += 1
    window.clearTimeout(this.reconnectTimer)
    window.clearInterval(this.heartbeat)
    const socket = this.socket
    this.socket = null
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, 'CLIENT_LOGOUT')
    this.changeState('offline')
  }

  send(operation: string, data: Record<string, unknown>, requestId = crypto.randomUUID()): string | null {
    if (this.state !== 'online' || this.socket?.readyState !== WebSocket.OPEN) return null
    this.socket.send(JSON.stringify({ v: 1, op: operation, requestId, data }))
    return requestId
  }

  private async connect(): Promise<void> {
    const run = ++this.generation
    this.changeState(this.attempt === 0 ? 'connecting' : 'reconnecting')
    try {
      const ticket = await this.api.ticket()
      if (this.stopped || run !== this.generation) return
      const socket = new WebSocket(ticket.websocketUrl)
      this.socket = socket
      const readyDeadline = window.setTimeout(() => socket.close(4000, 'READY_TIMEOUT'), 10_000)
      socket.onmessage = event => {
        let envelope: GatewayEnvelope
        try { envelope = JSON.parse(String(event.data)) as GatewayEnvelope } catch { return }
        if (envelope.op === 'READY') {
          window.clearTimeout(readyDeadline)
          this.attempt = 0
          this.changeState('online')
          this.startHeartbeat()
        } else if (envelope.op === 'RECONNECT') {
          socket.close(4001, 'SERVER_RECONNECT')
        }
        this.onEnvelope(envelope)
      }
      socket.onerror = () => socket.close()
      socket.onclose = () => {
        window.clearTimeout(readyDeadline)
        window.clearInterval(this.heartbeat)
        if (this.socket === socket) this.socket = null
        if (!this.stopped && run === this.generation) this.scheduleReconnect()
      }
    } catch {
      if (!this.stopped && run === this.generation) this.scheduleReconnect()
    }
  }

  private scheduleReconnect(): void {
    this.attempt += 1
    this.changeState('reconnecting')
    const base = Math.min(15_000, 500 * 2 ** Math.min(this.attempt, 5))
    const delay = Math.round(base * (0.8 + Math.random() * 0.4))
    window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = window.setTimeout(() => this.connect(), delay)
  }

  private startHeartbeat(): void {
    window.clearInterval(this.heartbeat)
    this.heartbeat = window.setInterval(() => {
      this.send('PING', { clientTime: new Date().toISOString() })
    }, 20_000)
  }

  private changeState(next: ConnectionState): void {
    if (this.state === next) return
    this.state = next
    this.onState(next)
  }
}
