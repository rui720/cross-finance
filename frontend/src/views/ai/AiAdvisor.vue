<script setup>
// AI 合规顾问对话页（DeepSeek 式交互）
// 三栏布局：左侧会话列表 + 中间对话区 + 右侧消息目录
// 支持 SSE 真实流式输出、消息复制/编辑/删除、多轮上下文记忆、中断生成
import { ref, reactive, nextTick, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createSession, listSessions, renameSession, deleteSession,
  listMessages, editMessage, deleteMessage, sendMessageStream, regenerateStream
} from '@/api/ai'

// ===== 会话列表 =====
const sessions = ref([])
const activeSessionId = ref(null)

// ===== 消息列表 =====
// 每条消息：{ id, role('USER'/'AI'), content, seqNo, createTime, streaming, editing, editContent, toolTip }
const messages = ref([])
const inputText = ref('')
const sending = ref(false)
const messageListRef = ref(null)
let abortController = null

// 工具名称中文映射（用于显示"正在查询汇率..."）
const toolNameMap = {
  getLatestExchangeRate: '查询实时汇率',
  getExchangeRateHistory: '查询汇率历史',
  queryOrders: '查询订单数据',
  queryProfitReport: '查询利润报表',
  queryBudgetWarnings: '查询预算预警',
  queryPaymentApplies: '查询付款申请',
  queryAllocationRules: '查询分摊规则',
  queryPaymentDetail: '查询付款详情',
  queryOrderDetail: '查询订单详情',
  queryAuditLogs: '查询审计日志',
  queryReconcileStatus: '查询对账状态',
  analyzeProfit: '分析利润归因'
}

// ===== 预设快捷问题 =====
const quickQuestions = [
  '现在美元兑人民币汇率多少？',
  '7月利润情况如何？',
  '哪些预算快超了？',
  '1000美元等于多少人民币？',
  '亚马逊平台最近30天订单情况',
  '待审批的付款有哪些？'
]

// ===== 右侧目录：提取所有用户消息摘要 =====
const directory = computed(() => {
  return messages.value
    .filter((m) => m.role === 'USER' && m.id)
    .map((m) => ({
      id: m.id,
      preview: m.content.length > 22 ? m.content.slice(0, 22) + '...' : m.content
    }))
})

// 当前会话标题
const currentSessionTitle = computed(() => {
  const s = sessions.value.find((s) => s.id === activeSessionId.value)
  return s ? s.title || '新对话' : 'AI 合规顾问'
})

// ==================== 会话管理 ====================

// 加载会话列表
async function loadSessions() {
  try {
    const res = await listSessions()
    sessions.value = res.data || []
    if (sessions.value.length > 0) {
      await handleSelectSession(sessions.value[0])
    }
  } catch (e) {
    // 拦截器已处理
  }
}

// 新建会话
async function handleNewSession() {
  if (sending.value) return
  try {
    const res = await createSession('')
    const newSession = res.data
    sessions.value.unshift(newSession)
    await handleSelectSession(newSession)
  } catch (e) {
    // 拦截器已处理
  }
}

// 选择会话
async function handleSelectSession(session) {
  if (sending.value) return
  activeSessionId.value = session.id
  messages.value = []
  try {
    const res = await listMessages(session.id)
    messages.value = (res.data || []).map((m) => ({
      ...m,
      role: m.role === 'ASSISTANT' ? 'AI' : 'USER',
      streaming: false,
      editing: false,
      editContent: '',
      toolTip: ''
    }))
    scrollToBottom()
  } catch (e) {
    // 拦截器已处理
  }
}

// 重命名会话
async function handleRenameSession(session) {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话名称', '重命名会话', {
      inputValue: session.title,
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    if (value && value !== session.title) {
      await renameSession(session.id, value)
      session.title = value
      ElMessage.success('已重命名')
    }
  } catch (e) {
    // 用户取消
  }
}

// 删除会话
async function handleDeleteSession(session) {
  try {
    await ElMessageBox.confirm(`确定删除会话"${session.title || '新对话'}"吗？`, '提示', {
      type: 'warning'
    })
    await deleteSession(session.id)
    sessions.value = sessions.value.filter((s) => s.id !== session.id)
    if (activeSessionId.value === session.id) {
      activeSessionId.value = null
      messages.value = []
      if (sessions.value.length > 0) {
        await handleSelectSession(sessions.value[0])
      }
    }
    ElMessage.success('已删除')
  } catch (e) {
    // 用户取消
  }
}

// 将当前会话移到列表顶部（发送消息后调用）
function refreshSessionOrder() {
  const idx = sessions.value.findIndex((s) => s.id === activeSessionId.value)
  if (idx > 0) {
    const [session] = sessions.value.splice(idx, 1)
    sessions.value.unshift(session)
  }
}

// ==================== 消息发送（SSE 流式） ====================

async function handleSend() {
  const content = inputText.value.trim()
  if (!content || sending.value) return

  // 没有会话时自动创建
  if (!activeSessionId.value) {
    await handleNewSession()
    if (!activeSessionId.value) return
  }

  inputText.value = ''
  sending.value = true

  // 前端立即显示用户消息 + AI 占位
  messages.value.push({ role: 'USER', content, streaming: false, editing: false })
  const aiMsg = reactive({ role: 'AI', content: '', streaming: true, editing: false })
  messages.value.push(aiMsg)
  scrollToBottom()

  abortController = sendMessageStream(activeSessionId.value, content, {
    onTool: (toolName) => {
      // 显示"正在查询汇率..."提示
      aiMsg.toolTip = toolNameMap[toolName] || toolName
      scrollToBottom()
    },
    onToken: (token) => {
      // 收到第一个 token 时清除工具提示
      if (aiMsg.toolTip) aiMsg.toolTip = ''
      aiMsg.content += token
      scrollToBottom()
    },
    onDone: () => {
      aiMsg.streaming = false
      aiMsg.toolTip = ''
      sending.value = false
      reloadMessages()
      refreshSessionOrder()
    },
    onError: (msg) => {
      aiMsg.content = aiMsg.content || '生成失败：' + msg
      aiMsg.streaming = false
      aiMsg.toolTip = ''
      sending.value = false
      ElMessage.error(msg)
    }
  })
}

// 中断生成
function handleStop() {
  if (abortController) {
    abortController.abort()
    abortController = null
  }
  sending.value = false
  const last = messages.value[messages.value.length - 1]
  if (last && last.streaming) {
    last.streaming = false
    if (!last.content) last.content = '（已中断）'
  }
}

// 重新加载消息列表（获取正确的 id）
async function reloadMessages() {
  if (!activeSessionId.value) return
  try {
    const res = await listMessages(activeSessionId.value)
    messages.value = (res.data || []).map((m) => ({
      ...m,
      role: m.role === 'ASSISTANT' ? 'AI' : 'USER',
      streaming: false,
      editing: false,
      editContent: '',
      toolTip: ''
    }))
  } catch (e) {
    // 忽略
  }
}

// ==================== 消息操作 ====================

// 复制消息
async function handleCopy(msg) {
  try {
    await navigator.clipboard.writeText(msg.content)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.error('复制失败')
  }
}

// 删除消息
async function handleDelete(msg, index) {
  try {
    await ElMessageBox.confirm('确定删除这条消息吗？', '提示', { type: 'warning' })
    if (msg.id) {
      await deleteMessage(msg.id)
    }
    messages.value.splice(index, 1)
    ElMessage.success('已删除')
  } catch (e) {
    // 用户取消
  }
}

// 进入编辑模式
function startEdit(msg) {
  msg.editing = true
  msg.editContent = msg.content
}

// 取消编辑
function cancelEdit(msg) {
  msg.editing = false
  msg.editContent = ''
}

// 保存编辑：更新消息 + 截断后续 + 重新生成 AI 回复
async function saveEdit(msg, index) {
  const newContent = msg.editContent.trim()
  if (!newContent) {
    ElMessage.warning('内容不能为空')
    return
  }
  if (newContent === msg.content) {
    msg.editing = false
    return
  }

  try {
    // 1. 调 editMessage 更新内容 + 删除后续消息
    const res = await editMessage(msg.id, newContent)
    const sessionId = res.data.sessionId

    // 2. 前端截断后续消息，更新当前消息
    messages.value = messages.value.slice(0, index + 1)
    msg.content = newContent
    msg.editing = false

    // 3. 添加 AI 占位，调 regenerate 重新生成
    sending.value = true
    const aiMsg = reactive({ role: 'AI', content: '', streaming: true, editing: false })
    messages.value.push(aiMsg)
    scrollToBottom()

    abortController = regenerateStream(sessionId, {
      onTool: (toolName) => {
        aiMsg.toolTip = toolNameMap[toolName] || toolName
        scrollToBottom()
      },
      onToken: (token) => {
        if (aiMsg.toolTip) aiMsg.toolTip = ''
        aiMsg.content += token
        scrollToBottom()
      },
      onDone: () => {
        aiMsg.streaming = false
        aiMsg.toolTip = ''
        sending.value = false
        reloadMessages()
        refreshSessionOrder()
      },
      onError: (err) => {
        aiMsg.content = aiMsg.content || '生成失败：' + err
        aiMsg.streaming = false
        aiMsg.toolTip = ''
        sending.value = false
        ElMessage.error(err)
      }
    })
  } catch (e) {
    // 拦截器已处理
  }
}

// ==================== 目录定位 ====================

function scrollToMessage(msgId) {
  const el = document.getElementById('msg-' + msgId)
  if (el) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.add('highlight')
    setTimeout(() => el.classList.remove('highlight'), 1500)
  }
}

// ==================== 工具方法 ====================

function scrollToBottom() {
  nextTick(() => {
    if (messageListRef.value) {
      messageListRef.value.scrollTop = messageListRef.value.scrollHeight
    }
  })
}

function handleEnter(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function formatTime(time) {
  if (!time) return ''
  return String(time).replace('T', ' ').slice(0, 16)
}

onMounted(() => {
  loadSessions()
})
</script>

<template>
  <div class="ai-container">
    <!-- 左侧：会话列表 -->
    <div class="sidebar">
      <div class="sidebar-header">
        <span class="panel-title">会话列表</span>
        <el-button type="primary" size="small" plain @click="handleNewSession">
          <el-icon><Plus /></el-icon> 新建
        </el-button>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: activeSessionId === s.id }"
          @click="handleSelectSession(s)"
        >
          <div class="session-info">
            <div class="session-title">{{ s.title || '新对话' }}</div>
            <div class="session-time">{{ formatTime(s.updateTime) }}</div>
          </div>
          <div class="session-actions" @click.stop>
            <el-icon @click="handleRenameSession(s)" title="重命名"><EditPen /></el-icon>
            <el-icon @click="handleDeleteSession(s)" title="删除"><Delete /></el-icon>
          </div>
        </div>
        <div v-if="sessions.length === 0" class="empty-tip">
          暂无会话，点击"新建"开始对话
        </div>
      </div>
    </div>

    <!-- 中间：对话区 -->
    <div class="chat-area">
      <div class="chat-header">
        <span class="panel-title">{{ currentSessionTitle }}</span>
      </div>

      <!-- 快捷问题（仅无消息时显示） -->
      <div v-if="messages.length === 0" class="quick-questions">
        <div class="welcome-text">
          <el-icon class="welcome-icon"><ChatDotRound /></el-icon>
          您好，我是 AI 合规顾问，可以为您解答跨境业务中的税务、外汇、财会合规问题。
        </div>
        <div class="quick-tags">
          <el-tag
            v-for="(q, i) in quickQuestions"
            :key="i"
            class="quick-tag"
            effect="plain"
            @click="inputText = q"
          >
            {{ q }}
          </el-tag>
        </div>
      </div>

      <!-- 消息列表 -->
      <div ref="messageListRef" class="message-list">
        <div
          v-for="(msg, index) in messages"
          :key="index"
          :id="'msg-' + (msg.id || '')"
          class="message-item"
          :class="msg.role === 'USER' ? 'msg-user' : 'msg-ai'"
        >
          <div class="avatar" :class="msg.role === 'USER' ? 'avatar-user' : 'avatar-ai'">
            <el-icon v-if="msg.role === 'AI'"><ChatDotRound /></el-icon>
            <el-icon v-else><User /></el-icon>
          </div>
          <div class="msg-body">
            <!-- 编辑模式 -->
            <template v-if="msg.editing">
              <el-input
                v-model="msg.editContent"
                type="textarea"
                :rows="3"
                resize="none"
                placeholder="修改消息内容，保存后将重新生成 AI 回复"
              />
              <div class="edit-actions">
                <el-button size="small" @click="cancelEdit(msg)">取消</el-button>
                <el-button size="small" type="primary" @click="saveEdit(msg, index)">
                  保存并重新生成
                </el-button>
              </div>
            </template>
            <!-- 正常显示 -->
            <template v-else>
              <div class="bubble" :class="msg.role === 'USER' ? 'bubble-user' : 'bubble-ai'">
                <!-- 工具调用提示（AI 正在查询数据时显示） -->
                <span v-if="msg.toolTip" class="tool-tip">
                  <el-icon class="tool-spin"><Loading /></el-icon>
                  正在{{ msg.toolTip }}...
                </span>
                <template v-if="msg.content">{{ msg.content }}</template>
                <span v-if="msg.streaming" class="cursor-blink">▋</span>
              </div>
              <!-- 操作按钮 -->
              <div v-if="!msg.streaming" class="msg-actions">
                <el-icon @click="handleCopy(msg)" title="复制"><CopyDocument /></el-icon>
                <el-icon v-if="msg.role === 'USER'" @click="startEdit(msg)" title="编辑"><EditPen /></el-icon>
                <el-icon @click="handleDelete(msg, index)" title="删除"><Delete /></el-icon>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- 输入区 -->
      <div class="input-area">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="请输入您的问题，Enter 发送，Shift+Enter 换行"
          @keydown="handleEnter"
        />
        <el-button v-if="!sending" type="primary" class="send-btn" @click="handleSend">
          <el-icon><Promotion /></el-icon> 发送
        </el-button>
        <el-button v-else type="danger" class="send-btn" @click="handleStop">
          <el-icon><VideoPause /></el-icon> 停止
        </el-button>
      </div>
    </div>

    <!-- 右侧：消息目录 -->
    <div class="directory">
      <div class="directory-header">
        <span class="panel-title">对话目录</span>
      </div>
      <div class="directory-list">
        <div
          v-for="item in directory"
          :key="item.id"
          class="directory-item"
          @click="scrollToMessage(item.id)"
        >
          {{ item.preview }}
        </div>
        <div v-if="directory.length === 0" class="empty-tip">暂无对话记录</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-container {
  display: flex;
  gap: 12px;
  height: calc(100vh - 100px);
  min-height: 500px;
}
.panel-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
}

/* ===== 左侧会话列表 ===== */
.sidebar {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  overflow: hidden;
}
.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}
.session-item:hover {
  background-color: #f5f7fa;
}
.session-item.active {
  background-color: #ecf5ff;
}
.session-info {
  flex: 1;
  overflow: hidden;
}
.session-title {
  font-size: 13px;
  color: #1f2937;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-time {
  font-size: 11px;
  color: #909399;
  margin-top: 3px;
}
.session-actions {
  display: none;
  gap: 8px;
  color: #909399;
  margin-left: 8px;
}
.session-item:hover .session-actions {
  display: flex;
}
.session-actions .el-icon {
  cursor: pointer;
  font-size: 14px;
}
.session-actions .el-icon:hover {
  color: #409eff;
}

/* ===== 中间对话区 ===== */
.chat-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  overflow: hidden;
}
.chat-header {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

/* 欢迎语 + 快捷问题 */
.quick-questions {
  padding: 24px 16px;
}
.welcome-text {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
  margin-bottom: 16px;
  line-height: 1.6;
}
.welcome-icon {
  font-size: 22px;
  color: #409eff;
  flex-shrink: 0;
}
.quick-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.quick-tag {
  cursor: pointer;
  transition: all 0.2s;
}
.quick-tag:hover {
  color: #409eff;
  border-color: #409eff;
}

/* 消息列表 */
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
  background-color: #fafafa;
}
.message-item {
  display: flex;
  align-items: flex-start;
  margin-bottom: 16px;
  transition: background-color 0.3s;
}
.message-item.highlight {
  background-color: #fff3cd;
  border-radius: 6px;
}
.msg-user {
  flex-direction: row-reverse;
}
.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 17px;
  flex-shrink: 0;
}
.avatar-ai {
  background-color: #409eff;
}
.avatar-user {
  background-color: #67c23a;
}
.msg-body {
  max-width: 72%;
  position: relative;
}
.msg-user .msg-body {
  margin-right: 10px;
}
.msg-ai .msg-body {
  margin-left: 10px;
}
.bubble {
  padding: 10px 14px;
  border-radius: 8px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}
.bubble-ai {
  background-color: #fff;
  color: #1f2937;
  border: 1px solid #ebeef5;
}
.bubble-user {
  background-color: #409eff;
  color: #fff;
}
.cursor-blink {
  animation: blink 1s steps(1) infinite;
  color: #409eff;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* 工具调用提示 */
.tool-tip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #409eff;
  font-size: 13px;
  margin-right: 4px;
}
.tool-spin {
  animation: rotating 1.5s linear infinite;
}
@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 消息操作按钮 */
.msg-actions {
  display: flex;
  gap: 10px;
  margin-top: 6px;
  color: #909399;
  font-size: 14px;
  opacity: 0;
  transition: opacity 0.2s;
}
.message-item:hover .msg-actions {
  opacity: 1;
}
.msg-actions .el-icon {
  cursor: pointer;
}
.msg-actions .el-icon:hover {
  color: #409eff;
}
.msg-user .msg-actions {
  justify-content: flex-end;
}

/* 编辑模式 */
.edit-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  justify-content: flex-end;
}

/* 输入区 */
.input-area {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid #f0f0f0;
  align-items: flex-end;
}
.input-area .el-input {
  flex: 1;
}
.send-btn {
  height: 56px;
}

/* ===== 右侧目录 ===== */
.directory {
  width: 180px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #ebeef5;
  overflow: hidden;
}
.directory-header {
  padding: 12px;
  border-bottom: 1px solid #f0f0f0;
}
.directory-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.directory-item {
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  cursor: pointer;
  transition: all 0.2s;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.directory-item:hover {
  background-color: #f5f7fa;
  color: #409eff;
}

/* 空状态 */
.empty-tip {
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
  padding: 24px 12px;
}
</style>
