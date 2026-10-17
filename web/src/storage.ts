import type { Message } from './types'

const DATABASE = 'im-web-cache'
const STORE = 'conversations'
const VERSION = 1

type CachedConversation = { key: string; messages: Message[]; updatedAt: number }

function openDatabase(): Promise<IDBDatabase> {
  return new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open(DATABASE, VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE)) {
        request.result.createObjectStore(STORE, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function loadMessages(userId: string, conversationId: string): Promise<Message[]> {
  const database = await openDatabase()
  return new Promise<Message[]>((resolve, reject) => {
    const request = database.transaction(STORE).objectStore(STORE).get(`${userId}:${conversationId}`)
    request.onsuccess = () => resolve((request.result as CachedConversation | undefined)?.messages ?? [])
    request.onerror = () => reject(request.error)
  }).finally(() => database.close())
}

export async function saveMessages(userId: string, conversationId: string, messages: Message[]): Promise<void> {
  const database = await openDatabase()
  const durable = messages.filter(message => message.delivery !== 'sending').slice(-500)
  return new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(STORE, 'readwrite')
    transaction.objectStore(STORE).put({ key: `${userId}:${conversationId}`, messages: durable, updatedAt: Date.now() })
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error)
  }).finally(() => database.close())
}

export async function clearUserCache(userId: string): Promise<void> {
  const database = await openDatabase()
  return new Promise<void>((resolve, reject) => {
    const transaction = database.transaction(STORE, 'readwrite')
    const store = transaction.objectStore(STORE)
    const cursor = store.openCursor()
    cursor.onsuccess = () => {
      const value = cursor.result
      if (!value) return
      if (String(value.key).startsWith(`${userId}:`)) value.delete()
      value.continue()
    }
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error)
  }).finally(() => database.close())
}
