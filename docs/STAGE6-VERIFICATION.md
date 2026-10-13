# 阶段 6 验收清单（RAG 入库 + 检索链路）

> **这份清单要本人用真实 `DEEPSEEK_API_KEY` 跑。** AI 不能代跑 —— 它没有 Key。
>
> **为什么必须人工跑**：自动化测试（189 个用例）能证明"数据装配正确"，
> **不能证明"模型真的用上了"**。这两者之间隔着一次真实模型调用，而这正是本项目
> 反复强调的"**单测全绿 ≠ 功能可用**"。
>
> **预计耗时**：15–25 分钟（其中启动约 30 秒）。
>
> **⚠️ 绝对不要把 API Key 贴给任何人（包括 AI）。** 下面的步骤用环境变量注入，不落盘。

---

## ★ 如果只有十几分钟：核心 4 项

| 顺序 | 位置 | 测什么 | 判定标准（必须全中） | 状态 |
|---|---|---|---|---|
| 1 | [一](#一环境前提) | 环境前提 | 启动日志出现 `DeepSeek API Key : [OK] 已配置(长度=xx)`，`xx` 不是 0 | ❌ 待跑 |
| 2 | [三](#三核心项-2记忆真的被模型用上了阶段-5-欠的那一项) | **记忆注入（阶段 5 欠的）** | 同会话第二轮，模型**说出了「张三」** | ❌ 待跑 |
| 3 | [四](#四核心项-3rag-片段真的被模型用上了) | **RAG 依据片段作答** | 回答**引用了来源**（如"员工手册 第二章"），且数字与片段一致 | ❌ 待跑 |
| 4 | [五](#五核心项-4权限过滤在真实链路上生效) | **权限过滤** | 匿名问不出 INTERNAL 内容；员工问得出 | ❌ 待跑 |

**前 3 项在阶段 5 就欠着（`STAGE5-VERIFICATION.md` 第 3 项至今未跑）** —— 这一次一起跑掉。

---

## 一、环境前提

### 1.1 基础设施必须在跑

```powershell
docker ps --format '{{.Names}}\t{{.Status}}'
```

必须看到这四个：`pgvector`、`hagent-mysql`、`hagent-redis`，以及 Ollama（`http://localhost:11434`）。

```powershell
# 向量库里有知识（示例数据已入库时应当有 10 个块）
docker exec pgvector psql -U postgres -d hagent -c "SELECT metadata->>'level' AS level, COUNT(*) FROM hagent_knowledge GROUP BY 1;"

# Ollama 在跑、模型在
(Invoke-WebRequest -Uri 'http://localhost:11434/api/tags' -UseBasicParsing -TimeoutSec 5).StatusCode
```

> 若向量库是空的：先跑一次 `.\mvnw.cmd -B -ntp test -Dtest=DocumentIngestStableStateTest`
> 让它把 4 份示例文档入库，或者用下面的"手工入库"一节。

### 1.2 注入 API Key 并启动

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:DEEPSEEK_API_KEY = "<你自己的 key>"      # ★ 不要贴给别人，不要写进任何文件
Set-Location "C:\Users\Rong\Desktop\实习准备\Hagent"

# 端口被占时先清理（spring-boot:run 会 fork 子进程，杀 wrapper 不够）
Get-NetTCPConnection -LocalPort 8123 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

.\mvnw.cmd spring-boot:run
```

### 1.3 启动日志要检查这 6 行

搜 `Hagent 启动探针` 附近，并确认：

| 要看到的 | 说明 |
|---|---|
| `DeepSeek API Key : [OK] 已配置(长度=xx)` | `xx` 不是 0 → 环境变量生效（**这一步踩过 5 次**） |
| `[向量库] 表 'hagent_knowledge' 已存在` | 表建出来了（不是"配了却没建"） |
| `[RAG] 回答约束已就位：classpath:prompts/rag-answer-prompt.md（238 字符）` | 提示词加载成功（缺失会启动失败） |
| `[检索] 参数：topK=3 粗筛=距离<= 0.5（相似度>= 0.5000） 分档=距离(<= 0.3, <= 0.45)` | 预算与阈值都生效 |
| `[Web] 身份解析拦截器已注册：/chat/**` | 鉴权链路挂上了 |
| `[记忆] 适配器已就绪（框架边界的预算闸门）：… {CONVERSATION=…5000…, WORKFLOW=…50000…}` | 预算闸门在（这个是核心项，**键序稳定：CONVERSATION 在前**） |

### 1.4 手工入库（仅当向量库为空时）

```powershell
# 用应用的批量入库接口或测试把 4 份知识文档入库；最快的方式是跑一次集成测试
.\mvnw.cmd -B -ntp test -Dtest=DocumentIngestStableStateTest
# 结束后再启动应用（测试会 TRUNCATE 表，所以顺序不能反）
```

---

## 二、怎么发请求（中文不要用裸 `Invoke-WebRequest`）

**★ 已知坑（两个方向，都会让中文出错）**：

| 方向 | 现象 | 原因 |
|---|---|---|
| **发送** | 服务端收到 `????` | `Invoke-WebRequest -Body '<字符串>'` **按 ISO-8859-1 编码**，中文逐字符变成 `?` |
| **接收** | 你看到的回复是 `æ ¹æ®...` | 响应头 `application/json` **不带 charset**，`$resp.Content` **按 ISO-8859-1 解码响应体** |

**两个坑的症状一样有误导性：服务端全绿、只有内容是错的。** 详细的实测证据与正确读法见
`WORKING-AGREEMENT.md` §9.2.1。

**用项目自带的工具**（发送与接收都已处理）：

```powershell
. "C:\Users\Rong\Desktop\实习准备\Hagent\tools\hagent-test.ps1"

# 同步对话（不带身份 = 匿名，只能看到 PUBLIC）
问 -Message "年假要提前几天申请"
```

**要带身份头**（看 INTERNAL 内容必须带）—— 工具脚本的 `问` 不支持自定义头，
用下面这段（它复用了脚本里的 `读响应体`，所以**必须先加载上面的脚本**）：

```powershell
function 问身份([string]$Message, [string]$Token, [string]$ConversationId) {
  $payload = @{ message = $Message }
  if ($ConversationId) { $payload.conversationId = $ConversationId }
  $bytes = [System.Text.Encoding]::UTF8.GetBytes(($payload | ConvertTo-Json -Compress))
  $h = @{}
  if ($Token) { $h['X-Hagent-Token'] = $Token }
  try {
    $resp = Invoke-WebRequest -Uri "$Global:HagentBaseUrl/chat" -Method POST -Body $bytes `
      -ContentType 'application/json; charset=utf-8' -Headers $h -UseBasicParsing -TimeoutSec 120
    # ★ 关键：不用 $resp.Content（会乱码），走脚本里的按字节读
    (读响应体 -Response $resp | ConvertFrom-Json).data.reply
  } catch {
    Write-Host "HTTP $([int]$_.Exception.Response.StatusCode)  $(读错误响应体 -Response $_.Exception.Response)" -ForegroundColor Yellow
  }
}
```

**三种身份**（自定义 token 的格式是 `<userId>:<role>`）：

| 身份 | 请求头 | 可见密级 |
|---|---|---|
| 匿名 | （不带 `X-Hagent-Token`） | 仅 `PUBLIC` |
| 普通员工 | `X-Hagent-Token: zhangsan:EMPLOYEE` | `PUBLIC` + `INTERNAL` |
| 管理员 | `X-Hagent-Token: root:ADMIN` | 全部 |

> ⚠️ **这是明文 token，没有签名** —— 任何人写个 `ADMIN` 就是管理员。
> 本项目只做"身份传递链路"，**不做**签发/校验/过期/吊销（见 `HANDOFF.md` 决策 20）。
> 演示可以，**别当安全边界用**。

---

## 三、核心项 2：记忆真的被模型用上了（阶段 5 欠的那一项）

**为什么要单独验**：能证明"记忆能装配、能落盘"，**不能证明"注入后模型真的用上了"**。
写入成功但注入失败时，文件看起来完全正常 —— 只有这一项能发现。

### 2.1 两轮对话（一次跑完，★ 关键）

**★ 全程不手工复制 UUID** —— 手工复制过一次，结果前 8 位丢了，
服务端读到的是另一个会话，表现成"记忆没生效"：

```powershell
$r1 = 问 -Message "我叫张三，在一家做企业服务的公司上班"
$cid = [string]$r1.会话ID                 # ★ 强制转字符串：PowerShell 7 的 .Headers[...] 返回的是集合
"会话ID = [$cid]  长度=$($cid.Length)"    # 正常应当是 36

$r2 = 问 -Message "我叫什么？我公司在哪个行业？" -ConversationId $cid
$r2.回复
```

- **判定标准**：`$r2.回复` 里出现 **「张三」** 和 **「企业服务」**（或同义表达）
- 实际结果：____________________

**要同时在启动日志里确认这两行**（证明链路确实注入/写回了）：

```text
[记忆-读] 会话 <UUID> 模式=CONVERSATION 注入历史 0 条   ← 第一轮（新会话）
[记忆-读] 会话 <UUID> 模式=CONVERSATION 注入历史 2 条   ← ★ 第二轮应当是 2 条
[PromptAssemblyAdvisor] … 消息数=4（system=1 历史=2 未知=0 用户=1）  ← ★ 结构证据
[记忆-写] 会话 <UUID> 已写回 2 条（问 + 答）
```

> ★ **判断"历史注入了没有"只看 `[PromptAssemblyAdvisor]` 那行** ——
> `[PromptTraceAdvisor]` 永远显示 `消息数=1`，它在最外层、看不到内层的注入（切面链单向转发）。

> 若 `注入历史 0 条` 但文件存在 → **写入端与读取端的 UUID 不是同一个**。
> 对比日志里的 UUID 与响应头里的 UUID。

### 2.3 新会话不串话（会话隔离）

```powershell
问带身份 "我叫什么？" "zhangsan:EMPLOYEE"     # 不传 conversationId = 新会话
```

- **判定标准**：**答不出**「张三」
- 实际结果：____________________

---

## 四、核心项 3：RAG 片段真的被模型用上了

### 3.1 问一个只有知识库能答的问题（带员工身份，才看得到 INTERNAL）

```powershell
问带身份 "年假要提前几天申请？" "zhangsan:EMPLOYEE"
```

- **判定标准**（三条都要中）：
  1. 回答给出**具体天数**「三个工作日」，且与库中内容一致；
  2. 回答**说明来源**（如"根据员工手册 第二章 假期管理"）；
  3. **不是**"我不知道" / 泛泛而谈的通识回答
- 实际结果：____________________

**对照的日志**（证明片段确实进了 prompt）：

```text
[检索] 命中 3 条（topK=3 粗筛=距离<= 0.5）| 视角 userId=zhangsan role=EMPLOYEE 可见密级=[PUBLIC, INTERNAL]
[检索]   · 相似度=0.7495 档位=高 出处=员工手册 第二章 假期管理
[PromptAssemblyAdvisor] ← 模型收到的 prompt | 消息数=2（system=1 历史=0 未知=0 用户=1）| 片段=3
```

> ★ **`[PromptTraceAdvisor]` 那行会是 `消息数=1`，而 `[PromptAssemblyAdvisor]` 是 `消息数=2`** ——
> 这不是 bug：最外层的切面**看不到内层的注入**（切面链单向转发，内层修改不回流）。
> 想看"模型真正收到什么"，看 `PromptAssemblyAdvisor` 那条。

### 3.2 问一个知识库里没有的问题（★ 验"不编造"）

```powershell
问带身份 "公司明年打算在哪些城市开分公司？" "zhangsan:EMPLOYEE"
```

- **判定标准**：明确说"知识库中未找到相关内容"，**不编造**具体城市
- 实际结果：____________________

> 这一项比 3.1 更能说明问题：**3.1 证明"会用资料"，3.2 证明"没资料时不瞎编"。**
> 只做 3.1 的话，一个"完全忽略片段、凭训练知识作答"的模型也可能答对天数。

### 3.3 问一个无关问题（已知局限，只需观察）

```powershell
问带身份 "今天杭州天气怎么样？" "zhangsan:EMPLOYEE"
```

- **预期**：检索**仍会命中**（实测相似度 0.7402 通过了 0.5 的粗筛），
  但**回答约束第 5 条**应当让模型不用这些片段作答
- **这一项是在观察"提示词约束到底起不起作用"** —— 如果模型拿年假片段硬答天气，
  说明约束不够强，需要改 `prompts/rag-answer-prompt.md`
- 实际结果：____________________

---

## 五、核心项 4：权限过滤在真实链路上生效

**同一个问题、同一个库、只换身份**，看回答的差异：

```powershell
# ① 匿名（只能看到 PUBLIC —— 公司简介/公司介绍）
问带身份 "年假要提前几天申请？" $null

# ② 员工（能看到 INTERNAL —— 员工手册）
问带身份 "年假要提前几天申请？" "zhangsan:EMPLOYEE"
```

- **判定标准**：
  1. ① 的回答**答不出具体天数**（或说"知识库中未找到"）—— 因为匿名的片段里没有员工手册；
  2. ② 的回答给出「三个工作日」；
  3. 日志里两者的 `可见密级` 分别是 `[PUBLIC]` 与 `[PUBLIC, INTERNAL]`
- 实际结果：____________________

**日志对照**（这个差异是权限过滤生效的直接证据）：

```text
① [检索] 命中 3 条 | 视角 userId=anonymous role=ANONYMOUS 可见密级=[PUBLIC]
② [检索] 命中 3 条 | 视角 userId=zhangsan  role=EMPLOYEE  可见密级=[PUBLIC, INTERNAL]
```

> ★ **这一项是"越权防线"的唯一验收方式。** 自动化测试已经证明过滤条件正确
> （`RetrievalPermissionIntegrationTest`），但那条路径不经过真实的 HTTP + 拦截器 + 切面链。
> 这里补的是"整条链在真实请求下是否仍然正确"。

### 5.1 顺手验一下非法 token（不需要 Key）

```powershell
问带身份 "你好" "badtoken"          # 期望 HTTP 400 + {"code":40001}
问带身份 "你好" "zhangsan:ADMN"     # 期望 HTTP 400 + {"code":40001}
```

- **判定标准**：都是 **400 + 40001**（**不是 500**）。
  ★ 第一版实现这里返回 500，是端到端实测才发现的（自动化测试 177 个用例全绿却漏掉了）。
- 实际结果：____________________

---

## 六、收尾

1. **停掉应用**（`Ctrl+C`，或清理端口）：
   ```powershell
   Get-NetTCPConnection -LocalPort 8123 -State Listen -ErrorAction SilentlyContinue |
     ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
   ```
2. **清掉 API Key 的环境变量**（可选，关掉这个终端即可）：
   ```powershell
   Remove-Item Env:\DEEPSEEK_API_KEY
   ```
3. **检查没有把 Key 写进任何文件**：
   ```powershell
   git status --porcelain          # 不应出现 application-local.yml 或任何配置文件
   Select-String -Path src\main\resources\*.yml -Pattern 'sk-'   # 不应有命中
   ```

---

## 七、把结果告诉我（按这个格式，**不要贴 API Key**）

```text
【核心 4 项】
1  启动探针里 API Key 显示长度是多少（不能是 0）：
2  第二轮回复提到「张三」和「企业服务」了吗：         ← 阶段 5 欠的那一项
3  RAG 回答给出「三个工作日」并说明来源了吗：
3b 知识库里没有的问题，它拒绝编造了吗：
4  匿名答不出、员工答得出吗：
   日志里两次的「可见密级」分别是什么：
5  非法 token 返回的是 400+40001 还是 500：

【顺带观察】
3c 问天气时它有没有拿年假片段硬答：
   ─ 若硬答了，说明 prompts/rag-answer-prompt.md 的约束第 5 条需要加强
   日志里 PromptAssemblyAdvisor 那行的「片段=N」分别是多少：
```

**任何一项不符合预期，把那一项的日志原文（`[检索]` / `[PromptAssemblyAdvisor]` /
`[记忆-读]` / `[记忆-写]` 四类行）贴给我，我来定位。**
