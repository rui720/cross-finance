// AI 合规顾问接口：会话管理 + 消息管理 + SSE 流式发送
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

/* ============ 会话管理 ============ */
export function createSession(title) {
  return request({ url: '/ai/session/create', method: 'post', data: { title } })
}

export function listSessions() {
  return request({ url: '/ai/session/list', method: 'get' })
}

export function renameSession(id, title) {
  return request({ url: '/ai/session/rename', method: 'put', data: { id, title } })
}

export function deleteSession(id) {
  return request({ url: `/ai/session/${id}`, method: 'delete' })
}

/* ============ 消息管理 ============ */
export function listMessages(sessionId) {
  return request({ url: `/ai/message/list/${sessionId}`, method: 'get' })
}

export function editMessage(messageId, content) {
  return request({ url: '/ai/message/edit', method: 'put', data: { messageId, content } })
}

export function deleteMessage(id) {
  return request({ url: `/ai/message/${id}`, method: 'delete' })
}

/* ============ SSE 流式发送 ============ */
// 使用 fetch + ReadableStream 消费 SSE，回调式推送 token
// 返回 AbortController 用于中断生成
export function sendMessageStream(sessionId, content, callbacks) {
  return doStream('/api/ai/chat/send', { sessionId, content }, callbacks)
}

/* ============ SSE 重新生成（编辑消息后用，不保存用户消息） ============ */
export function regenerateStream(sessionId, callbacks) {
  return doStream('/api/ai/chat/regenerate', { sessionId }, callbacks)
}

/**
 * SSE 流式请求通用方法
 * 事件格式：
 *   {"type":"token","content":"xxx"}      逐 token 推送
 *   {"type":"tool","name":"xxx"}          工具调用通知（前端可显示"正在查询..."）
 *   {"type":"done","messageId":xxx}       生成完成
 *   {"type":"error","message":"xxx"}      错误
 */
function doStream(url, body, callbacks) {
  const controller = new AbortController()
  const { onToken, onTool, onDone, onError } = callbacks

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + getToken()
    },
    body: JSON.stringify(body),
    signal: controller.signal
  })
    .then(async (response) => {
      if (!response.ok) {
        onError?.('请求失败：' + response.status)
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        // SSE 事件以 \n\n 分隔
        const parts = buffer.split('\n\n')
        buffer = parts.pop() // 保留最后不完整的片段
        for (const part of parts) {
          const line = part.trim()
          if (!line.startsWith('data:')) continue
          const jsonStr = line.slice(5).trim()
          try {
            const event = JSON.parse(jsonStr)
            if (event.type === 'token') onToken?.(event.content)
            else if (event.type === 'tool') onTool?.(event.name)
            else if (event.type === 'done') onDone?.(event.messageId)
            else if (event.type === 'error') onError?.(event.message)
          } catch (e) {
            // 忽略解析失败的片段
          }
        }
      }
    })
    .catch((e) => {
      if (e.name !== 'AbortError') {
        onError?.(e.message || '网络异常')
      }
    })

  return controller
}
