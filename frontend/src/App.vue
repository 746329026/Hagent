<script setup>
/**
 * 应用根组件 —— 状态都在这里，子组件只负责渲染与发事件。
 *
 * <h2>★ 一条事件流，三个视图</h2>
 *
 * <p>SSE 只有<b>一条</b>连接。收到事件后分派到三个互不相干的数据结构：
 * <pre>
 *   step        → steps[]      执行轨迹面板（悬浮窄框）
 *   reasoning   → reasoning[]  思考区（按步分段）
 *   message     → content      回答正文（Markdown）
 *   tool_call   → steps[].tools[]
 *   tool_result → steps[].tools[].state
 * </pre>
 * 关键是<b>不为面板单开一条连接</b>：那会让"一次对话"变成两次订阅，
 * 历史写回、计费、并发全部翻倍。
 *
 * <h2>★ 三个已知限制（UI 里如实标注，不假装没有）</h2>
 * <ol>
 *   <li><b>工作流模式当前行为与对话一致</b>，只有历史预算不同（真正的编排在阶段 12）；</li>
 *   <li><b>历史回放的执行轨迹只有步号与工具名</b> —— 参数与结果在审计表里，不进历史接口；</li>
 *   <li><b>工具状态只到"已返回"</b>，没有成功/失败 —— 后端拿不到那个信号。</li>
 * </ol>
 */
import { computed, nextTick, reactive, ref } from 'vue'
import AppSidebar from './components/AppSidebar.vue'
import TaskPanel from './components/TaskPanel.vue'
import MessageBubble from './components/MessageBubble.vue'
import Composer from './components/Composer.vue'
import { ROLES, listSessions, loadSession, renameSession, streamChat } from './api'

const sessions = ref([])
const currentId = ref(null)
const currentSession = ref(null)
const messages = ref([])
const steps = ref([])
const sources = ref([])
const mode = ref('chat')
const currentStep = ref(1)
const streaming = ref(false)
const error = ref('')
const role = ref(localStorage.getItem('hagent.role') || 'employee')

const messagesEl = ref(null)
const composer = ref(null)
let controller = null

const modeLocked = computed(() => Boolean(currentId.value))
const title = computed(() => currentSession.value?.title || (currentId.value ? '新会话' : '新对话'))
const effectiveMode = computed(() => currentSession.value?.mode || mode.value)

const hints = [
  '公司年假政策怎么规定的？',
  '报销流程要走哪些步骤？',
  '入职需要准备哪些材料？',
]

// ---------------------------------------------------------------------------
// 会话列表
// ---------------------------------------------------------------------------

async function refreshSessions() {
  try {
    sessions.value = (await listSessions(role.value)) || []
  } catch (e) {
    error.value = e.message
  }
}

function newChat() {
  if (streaming.value) {
    return
  }
  currentId.value = null
  currentSession.value = null
  messages.value = []
  steps.value = []
  sources.value = []
  error.value = ''
  mode.value = 'chat'
}

async function openSession(id) {
  if (streaming.value || id === currentId.value) {
    return
  }
  try {
    error.value = ''
    const detail = await loadSession(id, role.value)
    currentId.value = id
    currentSession.value = detail.session
    mode.value = detail.session.mode
    messages.value = (detail.messages || []).map((message, index) => ({
      id: index,
      role: message.role,
      content: message.content,
      reasoning: message.reasoning || [],
    }))
    steps.value = stepsFromHistory(messages.value)
    sources.value = []
    scrollToBottom()
  } catch (e) {
    error.value = e.message
  }
}

/**
 * 从历史里重建执行轨迹。
 *
 * <p>★ 只能重建到"第 N 步 · 工具名"：{@code chat_message.reasoning} 里存了步号与工具名，
 * 但<b>参数与结果没有落库</b>（它们在审计表 chat_tool_invocation 里，不属于会话历史）。
 * 所以历史回放的动作面板是"骨架版"—— 这是刻意的取舍，不是没做完。
 */
function stepsFromHistory(history) {
  const lastAssistant = [...history].reverse().find((m) => m.role === 'ASSISTANT')
  if (!lastAssistant || !lastAssistant.reasoning.length) {
    return []
  }
  return lastAssistant.reasoning.map((section) => ({
    step: section.step,
    state: 'done',
    tools: (section.tools || []).map((name, index) => ({
      id: `${section.step}-${index}-${name}`,
      name,
      state: 'returned',
      resultPreview: '',
    })),
  }))
}

async function onRename(id, titleText) {
  try {
    const updated = await renameSession(id, titleText, role.value)
    const index = sessions.value.findIndex((s) => s.conversationId === id)
    if (index >= 0 && updated) {
      sessions.value[index] = updated
    }
    if (currentSession.value?.conversationId === id && updated) {
      currentSession.value = updated
    }
  } catch (e) {
    error.value = e.message
  }
}

function onRoleChange(key) {
  role.value = key
  localStorage.setItem('hagent.role', key)
  sources.value = []
  refreshSessions()
}

// ---------------------------------------------------------------------------
// 发送
// ---------------------------------------------------------------------------

async function send(text) {
  if (streaming.value) {
    return
  }
  error.value = ''

  messages.value.push({ id: Date.now(), role: 'USER', content: text, reasoning: [] })
  /**
   * ★ 必须是 reactive 对象，不能是裸对象。
   *   裸对象塞进 reactive 数组后，数组里存的是【原始对象】，
   *   而我手上这个引用也是原始对象 —— 直接改它<b>不会触发视图更新</b>
   *   （Vue 的响应式靠 Proxy 拦截，改原始对象等于绕过 Proxy）。
   *   症状是"流式过程中界面完全不动，刷新一次才出现完整回答"，极难归因。
   */
  const assistant = reactive({ id: Date.now() + 1, role: 'ASSISTANT', content: '', reasoning: [] })
  messages.value.push(assistant)

  steps.value = []
  currentStep.value = 1
  sources.value = []
  streaming.value = true
  controller = new AbortController()
  scrollToBottom()

  try {
    const newId = await streamChat({
      message: text,
      conversationId: currentId.value,
      mode: mode.value,
      roleKey: role.value,
      signal: controller.signal,
      onEvent: (name, data) => handleEvent(name, data, assistant),
    })
    if (!currentId.value && newId) {
      currentId.value = newId
    }
  } catch (e) {
    // 用户点"停止"会让 fetch 抛 AbortError —— 那是正常操作，不是故障
    if (e.name !== 'AbortError') {
      error.value = e.message
    }
  } finally {
    streaming.value = false
    controller = null
    steps.value.forEach((step) => {
      step.state = 'done'
      step.tools.forEach((tool) => {
        if (tool.state === 'running') {
          tool.state = 'returned'
        }
      })
    })
    // 流结束后刷新侧栏：标题（第一句话）与活跃时间都变了
    refreshSessions()
    if (currentId.value && !currentSession.value) {
      currentSession.value = {
        conversationId: currentId.value,
        title: text,
        mode: mode.value,
      }
    }
  }
}

function stop() {
  controller?.abort()
}

/**
 * 从工具参数里取出"这次查的是什么"。
 *
 * <p><b>为什么需要它</b>：模型可能在同一歩里发<b>多个</b> retrieveKnowledge
 * （实测：一次提问里它会用两种表述各查一次，以提高召回）。
 * 而执行轨迹里原来只显示工具名，于是两行长得一模一样 ——
 * 看起来像"重复渲染"，其实两次的 query 完全不同。
 *
 * <p>只取 {@code query} 字段：它是目前唯一对用户有意义的参数；
 * 其它参数硬编码成文本只会变成噪声。解析失败返回空串（不显示），
 * 而不是把原始 JSON 糊到界面上。
 */
function queryOf(argumentsJson) {
  if (!argumentsJson) {
    return ''
  }
  try {
    const parsed = JSON.parse(argumentsJson)
    return typeof parsed?.query === 'string' ? parsed.query : ''
  } catch {
    return ''
  }
}

/** 把一条 SSE 事件分派到对应的数据结构。 */
function handleEvent(name, data, assistant) {
  switch (name) {
    case 'step': {
      const step = Number(data?.step ?? steps.value.length + 1)
      currentStep.value = step
      // 每进入新的一步，把前面那些步标记为完成
      steps.value.forEach((item) => {
        if (item.step < step) {
          item.state = 'done'
        }
      })
      if (!steps.value.some((item) => item.step === step)) {
        steps.value.push({ step, tools: [], state: 'thinking' })
      }
      break
    }
    case 'reasoning': {
      const text = data?.text ?? ''
      if (!text) {
        break
      }
      let section = assistant.reasoning.find((s) => s.step === currentStep.value)
      if (!section) {
        section = { step: currentStep.value, tools: [], text: '' }
        assistant.reasoning.push(section)
      }
      section.text += text
      scrollToBottomSoon()
      break
    }
    case 'message': {
      const text = data?.text ?? ''
      if (!text) {
        break
      }
      assistant.content += text
      const step = steps.value.find((item) => item.step === currentStep.value)
      if (step && step.state !== 'answering') {
        step.state = 'answering'
        // 回答开始 = 思考结束：把这一步的工具名记进对应的思考分段（与历史回放的形状对齐）
        const section = assistant.reasoning.find((s) => s.step === currentStep.value)
        if (section) {
          section.tools = step.tools.map((tool) => tool.name)
        }
      }
      scrollToBottomSoon()
      break
    }
    case 'tool_call': {
      const step = steps.value.find((item) => item.step === currentStep.value)
      if (step) {
        step.tools.push({
          id: data?.id ?? `tool-${step.tools.length}`,
          name: data?.name ?? '工具',
          query: queryOf(data?.arguments),
          state: 'running',
          resultPreview: '',
        })
      }
      break
    }
    case 'tool_result': {
      for (const step of steps.value) {
        const tool = step.tools.find((item) => item.id === data?.id)
        if (tool) {
          tool.state = 'returned'
          tool.resultPreview = (data?.result || '').slice(0, 120)
          break
        }
      }
      break
    }
    case 'retrieved': {
      sources.value = data?.sources || []
      break
    }
    case 'error': {
      error.value = data?.message || String(data)
      break
    }
    default:
      break
  }
}

// ---------------------------------------------------------------------------
// 滚动
// ---------------------------------------------------------------------------

let scrollTimer = null

function scrollToBottom() {
  nextTick(() => {
    const el = messagesEl.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

/** 流式时每 60ms 最多滚动一次 —— 每个 token 都滚会明显拖慢渲染。 */
function scrollToBottomSoon() {
  if (scrollTimer) {
    return
  }
  scrollTimer = setTimeout(() => {
    scrollTimer = null
    scrollToBottom()
  }, 60)
}

function useHint(text) {
  composer.value?.fill(text)
}

refreshSessions()
</script>

<template>
  <div class="app">
    <AppSidebar
      :sessions="sessions"
      :current-id="currentId"
      :role="role"
      :roles="ROLES"
      @new="newChat"
      @select="openSession"
      @rename="onRename"
      @update:role="onRoleChange"
    />

    <main class="main">
      <div class="main-header">
        <span class="main-title">{{ title }}</span>
        <span class="tag" :class="effectiveMode === 'workflow' ? 'tag-workflow' : 'tag-chat'">
          {{ effectiveMode === 'workflow' ? '工作流' : '对话' }}
        </span>
        <span v-if="sources.length" style="font-size: 11.5px; color: var(--text-faint)">
          参考了 {{ sources.length }} 段知识库内容
        </span>
        <span class="spacer" />
      </div>

      <!-- 执行轨迹：悬浮在对话区右上角的窄框（不是对话流的一部分） -->
      <TaskPanel :steps="steps" :streaming="streaming" />

      <div ref="messagesEl" class="messages">
        <div v-if="error" class="error-line">{{ error }}</div>

        <div
          v-if="effectiveMode === 'workflow' && currentId"
          class="banner"
        >
          工作流编排开发中 —— 当前它的行为与"对话"一致，只有历史预算不同（5 万 vs 5 千 token）。
        </div>

        <div v-if="!messages.length" class="empty-state">
          <h1>有什么可以帮你？</h1>
          <p>问公司制度、流程、规定，模型会自己决定要不要查知识库。</p>
          <div class="empty-hints">
            <button
              v-for="hint in hints"
              :key="hint"
              class="hint-chip"
              type="button"
              @click="useHint(hint)"
            >
              {{ hint }}
            </button>
          </div>
        </div>

        <div v-else class="messages-inner">
          <MessageBubble
            v-for="message in messages"
            :key="message.id"
            :message="message"
            :streaming="streaming && message === messages[messages.length - 1]"
          />
        </div>
      </div>

      <Composer
        ref="composer"
        :streaming="streaming"
        :mode="mode"
        :mode-locked="modeLocked"
        @send="send"
        @stop="stop"
        @update:mode="mode = $event"
      />
    </main>
  </div>
</template>
