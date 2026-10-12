/**
 * SSE 客户端（fetch + ReadableStream，2026-08-20）。
 * 支持：自动重连（指数退避）、暂停/恢复（AbortController）、事件回调。
 * 与主前端 SSE 消费模式对齐；服务端为 SseEmitter（event: xxx\ndata: {...}\n\n）。
 */

/**
 * 打开一个 SSE 事件流。
 *
 * @param url     事件流 URL（相对 /api）
 * @param handlers 回调：{ onOpen, onEvent(name, data), onError(err), onClose }
 * @returns 控制对象 { pause(), resume(), close(), setPaused }
 */
export function openEventStream(url, handlers = {}) {
  let controller = null
  let paused = false
  let closed = false
  let retry = 0

  const parse = (name, dataText) => {
    try {
      return { name, data: JSON.parse(dataText) }
    } catch (_) {
      return { name, data: dataText }
    }
  }

  async function connect() {
    if (closed) return
    controller = new AbortController()
    try {
      const resp = await fetch(url, {
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal
      })
      if (!resp.ok || !resp.body) throw new Error(`SSE HTTP ${resp.status}`)
      retry = 0
      handlers.onOpen?.()

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''
      let eventName = 'message'
      while (!closed) {
        const { done, value } = await reader.read()
        if (done) {
          // 服务端正常关闭流（连接被服务端/代理断开）：抛错走下方 catch → 自动重连
          throw new Error('SSE 流已关闭')
        }
        buf += decoder.decode(value, { stream: true })
        // 按空行切分事件块
        let idx
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, idx)
          buf = buf.slice(idx + 2)
          for (const line of block.split('\n')) {
            const trimmed = line.trim()
            if (!trimmed) continue
            if (trimmed.startsWith('event:')) eventName = trimmed.slice(6).trim()
            else if (trimmed.startsWith('data:')) {
              const ev = parse(eventName, trimmed.slice(5).trim())
              if (!paused && !closed) handlers.onEvent?.(ev.name, ev.data)
            } else if (trimmed.startsWith(':')) {
              // 注释（心跳）
            }
          }
          eventName = 'message'
        }
      }
    } catch (err) {
      if (closed) return
      handlers.onError?.(err)
      // 指数退避重连：0.5s → 1s → 2s → 4s（上限 8s）
      const delay = Math.min(8000, 500 * 2 ** retry)
      retry++
      if (!closed && !paused) {
        setTimeout(connect, delay)
      }
    }
  }

  connect()

  return {
    /** 暂停（停止处理事件，连接保持） */
    setPaused(v) { paused = !!v },
    pause() { paused = true },
    resume() { paused = false; if (!controller && !closed) connect() },
    /** 完全关闭连接并停止重连 */
    close() {
      closed = true
      try { controller?.abort() } catch (_) { /* 忽略 */ }
      handlers.onClose?.()
    }
  }
}
