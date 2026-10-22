#!/usr/bin/env node
/**
 * MCP stdio 握手探针 —— 用来验证"本地 MCP server 真的能通、工具真的能看到、真的能调"。
 *
 * <h2>★ 为什么需要这个脚本（而不是"看日志说它启动了"）</h2>
 *
 * stdio 形态有一条极具欺骗性的失败模式：
 * <pre>
 *   server 进程起来了、日志一切正常，但它把日志写进了 stdout
 *   → 协议帧被 "2026-10-21 ... INFO ..." 这类行搅乱
 *   → 客户端解析失败、一个工具都看不到
 * </pre>
 * 于是"进程活着"和"协议通了"是两件事。本脚本<b>直接说 JSON-RPC</b>，
 * 按真实客户端的顺序握手，并把"stdout 里有没有非 JSON 行"单独报出来。
 *
 * <h2>用法</h2>
 * <pre>
 *   node tools/mcp-probe.mjs                                   # 默认探 MCP/time-server
 *   node tools/mcp-probe.mjs "MCP/excel-server/target/xxx.jar" # 探别的 server
 *   node tools/mcp-probe.mjs --call getCurrentTime '{"zone":"UTC"}'   # 换工具/参数
 * </pre>
 *
 * 退出码：0 = 全部通过（工具列表非空 + 调用返回内容 + stdout 纯净）；1 = 有失败项。
 *
 * <h2>它<b>不</b>验证什么</h2>
 * 不验证"模型会不会选择调这个工具"—— 那要真模型，见 HANDOFF 的验收清单。
 */

import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { resolve } from 'node:path'

const args = process.argv.slice(2)
let callName = 'getCurrentTime'
let callArgs = { zone: 'Asia/Shanghai' }
const positional = []
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--call') {
    callName = args[++i]
    callArgs = args[i + 1] && args[i + 1].startsWith('{') ? JSON.parse(args[++i]) : {}
  } else {
    positional.push(args[i])
  }
}

const jar = resolve(positional[0] || 'MCP/time-server/target/time-server-0.0.1-SNAPSHOT.jar')
if (!existsSync(jar)) {
  console.error(`[探针] 找不到 server jar：${jar}`)
  console.error('[探针] 先构建：.\\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml package')
  process.exit(1)
}

/** 协议版本：与 SDK 2.0.0 对齐的现代版本；server 若不支持会在响应里回它自己的版本。 */
const PROTOCOL_VERSION = '2025-06-18'

console.log(`[探针] 拉起：java -jar ${jar}`)
const child = spawn('java', ['-jar', jar], { stdio: ['pipe', 'pipe', 'pipe'] })

let stdoutBuffer = ''
const stdoutLines = []
const pending = new Map()
let dirtyStdoutLines = []

child.stdout.on('data', (chunk) => {
  stdoutBuffer += chunk.toString('utf8')
  let idx
  while ((idx = stdoutBuffer.indexOf('\n')) !== -1) {
    const line = stdoutBuffer.slice(0, idx).replace(/\r$/, '')
    stdoutBuffer = stdoutBuffer.slice(idx + 1)
    if (!line.trim()) continue
    stdoutLines.push(line)
    try {
      const msg = JSON.parse(line)
      if (msg.id !== undefined && pending.has(msg.id)) {
        const { resolve: done } = pending.get(msg.id)
        pending.delete(msg.id)
        done(msg)
      }
    } catch {
      dirtyStdoutLines.push(line)
    }
  }
})

child.stderr.on('data', (chunk) => {
  // 日志应该都在这边 —— 只打印前几行，避免刷屏
  const text = chunk.toString('utf8').trim()
  if (text) console.log(`[server stderr] ${text.split('\n')[0]}`)
})

let nextId = 1
function request(method, params) {
  const id = nextId++
  return new Promise((done, fail) => {
    const timer = setTimeout(() => {
      pending.delete(id)
      fail(new Error(`等待 ${method} 响应超时（15s）`))
    }, 15000)
    pending.set(id, {
      resolve: (msg) => {
        clearTimeout(timer)
        done(msg)
      },
    })
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n')
  })
}
function notify(method, params) {
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n')
}

const failures = []
function check(ok, label, detail) {
  console.log(`${ok ? '✅' : '❌'} ${label}${detail ? ' —— ' + detail : ''}`)
  if (!ok) failures.push(label)
}

try {
  const init = await request('initialize', {
    protocolVersion: PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: 'hagent-mcp-probe', version: '1.0.0' },
  })
  const serverInfo = init.result?.serverInfo
  check(!init.error, 'initialize 成功',
    init.error ? JSON.stringify(init.error) : `${serverInfo?.name} ${serverInfo?.version}（protocol ${init.result?.protocolVersion}）`)

  notify('notifications/initialized')

  const tools = await request('tools/list', {})
  const list = tools.result?.tools ?? []
  check(list.length > 0, 'tools/list 返回了工具', list.map((t) => t.name).join(', ') || '（空）')

  const target = list.find((t) => t.name === callName)
  check(!!target, `工具 ${callName} 在列表里`, target ? target.description?.split('\n')[0] : '（没有）')

  const call = await request('tools/call', { name: callName, arguments: callArgs })
  const content = call.result?.content?.[0]?.text
  check(!call.error && !!content, `调用 ${callName} 返回内容`, call.error ? JSON.stringify(call.error) : content)

  check(dirtyStdoutLines.length === 0,
    'stdout 里没有非 JSON 行（日志没污染协议通道）',
    dirtyStdoutLines.length ? `前 3 条：${dirtyStdoutLines.slice(0, 3).join(' | ')}` : `${stdoutLines.length} 行全是 JSON`)
} catch (e) {
  check(false, '握手过程异常', e.message)
} finally {
  child.stdin.end()
  child.kill()
}

console.log(failures.length === 0 ? '\n[探针] 全部通过 ✅' : `\n[探针] 失败 ${failures.length} 项 ❌`)
process.exit(failures.length === 0 ? 0 : 1)
