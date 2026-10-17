/**
 * 后端 API 的唯一出口。
 *
 * <h2>★ 为什么流式用 POST + fetch，而不是 EventSource</h2>
 *
 * <p>{@code EventSource} 有两个硬限制，正好都卡在我们要做的事上：
 * <ol>
 *   <li><b>只能是 GET</b>，且<b>不能带请求体</b> —— 而用户消息、会话 ID、模式都要发过去；</li>
 *   <li><b>不能自定义请求头</b> —— 而身份走 {@code X-Hagent-Token} 头。
 *       把它塞进 query string 会让 token 出现在浏览器历史、访问日志与 Referer 里。</li>
 * </ol>
 *
 * <p>所以用 {@code fetch} + 手动读 {@code ReadableStream}：换来的能力是
 * "带请求体 + 带请求头 + 可中断（AbortController）"。
 * 代价是 SSE 帧要自己解析 —— 解析器就在下面，只有二十来行。
 */

/** 后端基址。可用 VITE_API_BASE 覆盖（例如换成局域网地址）。 */
const BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8123/api'

/**
 * 可切换的身份。
 *
 * <p>token 的格式是 {@code <userId>:<ROLE>}，后端把它解析成
 * {@code UserContext(userId, role)}，再由角色决定检索能看见哪些密级。
 * 所以切换它不只是"换个名字"，而是<b>真的换了一组可见的知识</b> ——
 * 这也是为什么前端要把它做成可切换的：用来直观验证权限过滤有没有生效。
 */
export const ROLES = [
  { key: 'anonymous', label: '匿名', hint: '未携带凭证，仅可见 PUBLIC', token: null },
  { key: 'employee', label: '普通员工', hint: 'zhangsan:EMPLOYEE，可见 PUBLIC + INTERNAL', token: 'zhangsan:EMPLOYEE' },
  { key: 'admin', label: '管理员', hint: 'admin:ADMIN，可见全部密级', token: 'admin:ADMIN' },
]

function roleToken(roleKey) {
  return ROLES.find((r) => r.key === roleKey)?.token ?? null
}

function authHeaders(roleKey) {
  const headers = {}
  const token = roleToken(roleKey)
  if (token) {
    headers['X-Hagent-Token'] = token
  }
  return headers
}

/**
 * 普通 JSON 接口。
 *
 * <p>后端统一用 {@code {code,message,data}} 包装，{@code code !== 0} 即失败 ——
 * 但 HTTP 状态码也可能是非 2xx（例如会话不存在是 404）。两条都要看：
 * 只看 HTTP 状态会漏掉"业务失败但 HTTP 200"的情况。
 */
async function request(path, { method = 'GET', body, roleKey } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...authHeaders(roleKey) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let payload = null
  try {
    payload = await res.json()
  } catch {
    // 非 JSON（例如网关错误页）—— 下面按状态码报错
  }
  if (!res.ok) {
    throw new Error(payload?.message || `请求失败（HTTP ${res.status}）`)
  }
  if (payload && typeof payload.code === 'number' && payload.code !== 0) {
    throw new Error(payload.message || '请求失败')
  }
  return payload?.data
}

/** 侧栏：我有哪些会话（最近的在前）。 */
export function listSessions(roleKey, limit = 50) {
  return request(`/chat/sessions?limit=${limit}`, { roleKey })
}

/** 点开一个会话：元信息 + 全部历史（思考已由后端解析成分段）。 */
export function loadSession(conversationId, roleKey) {
  return request(`/chat/sessions/${encodeURIComponent(conversationId)}/messages`, { roleKey })
}

/** 改名。返回更新后的摘要。 */
export function renameSession(conversationId, title, roleKey) {
  return request(`/chat/sessions/${encodeURIComponent(conversationId)}`, {
    method: 'PATCH',
    body: { title },
    roleKey,
  })
}

/**
 * 解析一个 SSE 帧。
 *
 * <p>帧长这样（字段顺序不固定，空行分隔）：
 * <pre>
 *   id:12
 *   event:tool_call
 *   data:{"id":"call-1","name":"retrieveKnowledge"}
 * </pre>
 *
 * <p>后端所有事件的 data 都是 <b>JSON</b>（阶段 10 起连 {@code message}/{@code reasoning}
 * 也包成了 {@code {"text":"…"}}，因为 SSE 的 data 行放不下裸换行）。
 * 只有 {@code done} 发的是字面量 {@code [DONE]} —— 解析失败时原样返回即可。
 */
function parseFrame(raw) {
  let name = 'message'
  const dataLines = []
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      name = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
    // id: / retry: / 注释行（以 : 开头）刻意忽略 —— 前端不做断线续传
  }
  if (dataLines.length === 0) {
    return null
  }
  const raw_ = dataLines.join('\n')
  try {
    return { name, data: JSON.parse(raw_) }
  } catch {
    return { name, data: raw_ }
  }
}

/**
 * 发一句话并读完整条事件流。
 *
 * @param {object}   options
 * @param {string}   options.message        用户输入
 * @param {string?}  options.conversationId 会话 ID（新建时不传）
 * @param {string}   options.mode           chat / workflow（只在新建会话时生效）
 * @param {string}   options.roleKey        身份
 * @param {Function} options.onEvent        (事件名, data) => void
 * @param {AbortSignal} options.signal      用于"停止生成"
 * @returns {Promise<string?>} 会话 ID（后端通过 X-Conversation-Id 响应头返回）
 */
export async function streamChat({ message, conversationId, mode, roleKey, onEvent, signal }) {
  const res = await fetch(`${BASE}/chat/stream-sse`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...authHeaders(roleKey),
    },
    body: JSON.stringify({ message, conversationId: conversationId || undefined, mode }),
    signal,
  })

  if (!res.ok) {
    let detail = ''
    try {
      const payload = await res.json()
      detail = payload?.message || ''
    } catch {
      // 忽略：下面用状态码兜底
    }
    throw new Error(detail || `流式请求失败（HTTP ${res.status}）`)
  }

  // ★ 会话 ID 走响应头（不是响应体）：它是"调用元数据"，放进事件流会污染业务事件。
  //   响应头必须已在后端 CORS 的 exposedHeaders 里，否则浏览器读不到 —— 且不报错。
  const newConversationId = res.headers.get('X-Conversation-Id')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })
    // \r\n 也要认（网关/代理可能改写行尾）
    buffer = buffer.replace(/\r\n/g, '\n')
    let index
    while ((index = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      if (!raw.trim()) {
        continue
      }
      const frame = parseFrame(raw)
      if (frame) {
        onEvent(frame.name, frame.data)
      }
    }
  }

  return newConversationId
}
