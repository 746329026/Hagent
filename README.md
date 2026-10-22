# Hagent · 企业办公 Agent

> 为大模型应用岗位实习准备的简历项目：以 **Spring AI** 为底座、**自研编排**（`AgentPipeline`），
> 把 RAG、会话/长期记忆、工具调用、工作流、可观测性做成**可替换、可解释**的模块。
>
> **状态：已完结（阶段 0–17）** · 主应用 **369 测试通过 / 2 skipped** · `MCP/time-server` **3 条测试** ·
> 真实 Key 端到端验证通过 · 详细过程见 [`docs/PROCEDURES.md`](docs/PROCEDURES.md)

---

## 这个项目是什么

题目要求的能力：**会话 / 工作流模式、统一异常处理、Spring AI、RAG 企业知识库、
基于 MySQL / Redis / `.md` 的存储与记忆、tool call** —— 全部落地，并额外做了：

- 回合账（每轮一行：耗时 / 首字 / 模型次数 / token / 结局）与工具审计；
- MCP 工具来源可插拔（独立进程的时间 server，模型真实调用过）；
- 包归属与包级依赖的**架构守卫**（`PackagePlacementTest` + `ArchitectureRulesTest`，包级环 8 → 0）。

它**不是**"接一个 ChatClient 就完"的 Demo：主链路是自己写的编排器，
每个关键设计都能回答"解决什么问题、谁消费、不做会怎样"。

## 能力一览

| 能力 | 实现要点 | 入口 |
|---|---|---|
| 静 / 流式对话 | SSE；思考过程按步落库（`@@step:` 标记），刷新可回看 | `POST /api/chat`、`POST/GET /api/chat/stream-sse` |
| RAG 企业知识库 | **模型自主决定何时检索**（工具化，不是每轮必跑）；密级过滤下推到 SQL | 工具 `retrieveKnowledge` |
| 会话记忆 | MySQL **记录层**（权威·完整）+ Redis **上下文层**（有损·TTL·可重建） | `AgentPipeline` 读写 |
| 长期记忆 | `{dir}/{userId}/MEMORY.md` 索引常驻 + 三个工具 | `saveMemory` / `readMemory` / `forgetMemory` |
| 工作流模式 | 先拆解 → 逐步执行 → 同步重试 1 次 → 重规划 1 次 → 放弃并说明第 k 步 | `mode: "workflow"` |
| 回合账 / 审计 | `chat_turn` 一轮一行（SUCCESS/FAILED/CANCELLED）+ 工具审计带 `turn_id`/`user_id` | `stats` SSE 事件、SQL 直查 |
| MCP | 独立项目 `MCP/time-server`（stdio）；工具名 `mcp_hagent_time_*` | `local,mcp` profile |
| 会话管理 | 列表 / 改名 / 删除 / 历史回放（含思考与回合统计） | `/api/chat/sessions*` |
| 前端 | Vue3 + Vite：会话侧栏 / 对话 / 思考 / 执行轨迹 / 回合账一行字 | `frontend/` |

## 架构

```mermaid
flowchart LR
    subgraph web["入口层"]
        c1["ChatController"]
        c2["ConversationController"]
        c3["HealthController"]
    end

    subgraph app["编排层（自研）"]
        p["AgentPipeline<br/>唯一编排者"]
        pb["PromptBuilder<br/>唯一拼装者"]
        svc["ChatAppService / ConversationService"]
    end

    subgraph cap["能力层"]
        tool["tool<br/>检索 / 记忆 / MCP 工具"]
        ret["retrieval<br/>密级过滤 + 向量检索"]
        mem["memory<br/>双层会话记忆 + 长期记忆"]
        wf["workflow<br/>计划 / 重试 / 重规划"]
    end

    subgraph store["存储层（各司其职）"]
        mysql[("MySQL<br/>记录 / 会话 / 审计 / 回合")]
        redis[("Redis<br/>模型上下文")]
        pg[("pgvector<br/>向量索引")]
        md["`.md`<br/>长期记忆"]
    end

    web --> app --> cap
    cap --> store
```

- **模型接入**：DeepSeek `deepseek-flash`（chat，云端）+ Ollama `mxbai-embed-large`（embedding，本地 1024 维）。
- **直连 `ChatModel`**：不用 `ChatClient` / advisor 链，工具循环与事件流由 `AgentPipeline` 掌控，顺序即代码行序。
- **四个存储各司其职**：pgvector（向量）/ MySQL（业务权威）/ Redis（可丢上下文）/ `.md`（人可读长期记忆）。

## 快速开始

### 0. 前置

| 需要 | 说明 |
|---|---|
| JDK 21 | 仓库自带 Maven Wrapper（无需全局 Maven） |
| Docker | 三个容器：pgvector / MySQL / Redis（valkey） |
| Ollama | `ollama pull mxbai-embed-large:latest`（embedding，本地零边际成本） |
| DeepSeek API Key | 只需非空；真实调用才需要有效值 |
| Node 18+（可选） | 仅前端需要 |

### 1. 起三个基础设施容器

```powershell
docker run -d --name pgvector -p 5432:5432 `
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=hagent `
  pgvector/pgvector:pg16

docker run -d --name hagent-mysql -p 3307:3306 `
  -e MYSQL_ROOT_PASSWORD=hagent123 -e MYSQL_DATABASE=hagent -e TZ=Asia/Shanghai `
  mysql:8.0.39 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

docker run -d --name hagent-redis -p 6379:6379 `
  valkey/valkey:8 valkey-server --save --appendonly no
```

> 端口被占用时改宿主端口，并同步改 `application-local.yml`。
> 本机 Docker Hub 不可达时可复用本地已有镜像（`pgvector/pgvector:pg16`、`mysql:8.0.39`、`valkey/valkey:8`）。

向量库扩展（框架建表需要）：

```powershell
docker exec pgvector psql -U postgres -d hagent -c 'CREATE EXTENSION IF NOT EXISTS vector; CREATE EXTENSION IF NOT EXISTS "uuid-ossp";'
```

### 2. 建业务表（显式 DDL，唯一出处）

应用**不自动建表**；启动时会校验表与字符集，不符即启动失败并提示重跑。

```powershell
foreach ($s in 'chat_message','chat_conversation','chat_tool_invocation','chat_turn') {
  docker cp "src/main/resources/db/$s.sql" "hagent-mysql:/tmp/$s.sql"
  docker exec hagent-mysql sh -c "MYSQL_PWD=`$MYSQL_ROOT_PASSWORD mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/$s.sql"
}
```

> 已有库升级时才需要 `src/main/resources/db/migrations/*.sql`（本项目第一次表迁移是
> `001-chat_tool_invocation-add-who.sql`，历史行一律 `unknown`，不伪造）。

### 3. 本地私有配置

```powershell
Copy-Item src\main\resources\application-local.yml.example src\main\resources\application-local.yml
$env:DEEPSEEK_API_KEY = "sk-..."     # 也可以只设环境变量，不落盘
```

`application-local.yml` 已被 `.gitignore` 排除，绝不进仓库。

### 4. 知识入库（示例数据）

`knowledge/` 下 4 份虚构文档用于验证入库链路；**入库目前没有 HTTP 入口**
（刻意保持"测试里调用 + 显式 DDL"的运维方式）。先清空再跑入库测试：

```powershell
docker exec pgvector psql -U postgres -d hagent -c "TRUNCATE TABLE hagent_knowledge;"
$env:DEEPSEEK_API_KEY = "test-placeholder-never-used"   # 此步只调 Ollama embedding，不调 DeepSeek
.\mvnw.cmd -B -ntp test -Dtest=DocumentIngestStableStateTest
```

> 为什么必须先 TRUNCATE：该测试"库里已有数据就不再入库"，直接跑**不会**把改过的文档写进去
> （且不报错）。细节见 [`docs/KNOWLEDGE-SAMPLE-DATA.md`](docs/KNOWLEDGE-SAMPLE-DATA.md)。

### 5. 启动后端

```powershell
.\mvnw.cmd spring-boot:run
# 端口 8123，context-path=/api
# 健康检查：http://localhost:8123/api/health（存活） / http://localhost:8123/api/health/detail（依赖）
```

启用 MCP（可选；**先构建 server，再起主应用**）：

```powershell
.\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml package
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,mcp"
# 日志应出现：已注册 5 个工具（本地 4 + MCP 1）… mcp_hagent_time_getCurrentTime
```

### 6. 启动前端

```powershell
cd frontend
npm install
npm run dev          # http://localhost:5173
```

### 7. 说第一句话

```powershell
. .\tools\hagent-test.ps1
问 -Message "公司的总部在哪里"        # 匿名（未带身份头）：只能看到 PUBLIC 文档
```

> 想看 INTERNAL / CONFIDENTIAL 的内容，可在前端**侧栏切换身份**（前端会带
> `X-Hagent-Token: admin:ADMIN`），或用任意 HTTP 客户端自行加请求头。
> 头格式：`X-Hagent-Token: <userId>:<ROLE>`，`ROLE` ∈ `ANONYMOUS` / `EMPLOYEE` / `ADMIN`；
> **缺失 = 匿名（合法）**，写了但格式错 = 400（不静默降级）。

> `tools/hagent-test.ps1` 做了中文 UTF-8 安全处理 —— PowerShell 5.1 裸 `Invoke-WebRequest`
> 会把中文 body 变成 `????`。不要绕过它手写请求。

### 8. 跑测试

```powershell
$env:DEEPSEEK_API_KEY = "test-placeholder-never-used"   # 测试不调真模型，只需非空
.\mvnw.cmd -B -ntp clean test                            # 369 通过 / 2 skipped
.\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml test       # 3 条
node tools\mcp-probe.mjs                                 # MCP stdio 握手探针
```

> 需要三个容器 + Ollama 在跑；2 条 skipped 是需要环境开关的真实探针，不是失败。

## 目录结构（简）

```text
Hagent/
├── docs/              协作协议 / 阶段记录 / 交接快照 / 架构审计台账
├── knowledge/         4 份示例知识文档（虚构数据）
├── frontend/          Vue3 + Vite
├── tools/             sql.ps1 / hagent-test.ps1 / change-list.ps1 / mcp-probe.mjs …
├── MCP/time-server/   独立 MCP server（stdio，独立 pom / 独立进程）
└── src/
    ├── main/java/com/hagent/
    │   ├── app/        AgentPipeline（唯一编排者）/ PromptBuilder / ChatAppService …
    │   ├── agent/      PipelineEvent（sealed 事件契约）
    │   ├── config/     全部配置与装配（*Properties / *Config / 启动期校验器）
    │   ├── controller/ 3 个控制器
    │   ├── memory/     会话记忆（record / context / catalog）+ longterm/
    │   ├── document/   入库领域模型 + chunking/（切分策略）
    │   ├── retrieval/  检索与密级过滤
    │   ├── tool/       本地工具 + audit/
    │   ├── workflow/   计划 / 解析 / 校验
    │   ├── observability/ 启动探针 + 回合账
    │   └── stream/ security/ exception/ common/ dto/ prompt/
    ├── main/resources/ application*.yml / db/*.sql / prompts/*.md
    └── test/java/com/hagent/   50 个测试类（369 用例）
```

## 几个关键设计（为什么这样选）

1. **RAG 是工具，不是每轮必跑的流程**
   由模型决定何时调 `retrieveKnowledge` ⇒ 无关问题不白烧 embedding；`retrieval.enabled=false`
   表示"把检索能力整体拔掉"（模型看不到该工具）。
2. **会话记忆是两个角色，不是同一份数据存两份**
   MySQL 记录层（权威·完整·永久）+ Redis 上下文层（有损·TTL·可从记录层重建）。
   写路径固定"先记录层、后上下文层" ⇒ 上下文永不比记录新。
3. **回合账一轮一行，三种结局都落库**
   失败 / 取消的轮次在 `chat_message` 里根本不存在 ⇒ 统计必须落在 `doFinally`（唯一能看到
   onComplete / onError / cancel 三出口的位置）。成功率的分母 = 所有开始的轮次。
4. **工作流与 chat 共用同一个工具循环**
   唯一分叉点在 `run()`：多一次规划调用 + 逐步执行 + 重试/重规划；不是两套执行器。
5. **失败与没命中必须可区分**
   检索故障抛 `RetrievalUnavailableException`，工具翻译成"检索不可用（系统故障，不是没有资料）"；
   "降级要发生在能说出原因的那一层"。
6. **守卫是结构，不是纪律**
   `PackagePlacementTest`（包归属 5 条规则、零例外）+ `ArchitectureRulesTest`（包级环 0）；
   关键回归断言都用"临时植入缺陷确认会红"验证过。

## 已知限制（诚实清单）

- **身份是自报的**：`X-Hagent-Token` 明文可伪造，检索越权防护只证明"链路正确"，不是安全边界；
  真正的认证授权不在本项目范围（只预留身份传递链路）。
- **删会话不删痕迹**：审计表的 `arguments`/`result` 含用户原话（已拍板接受）。
- **进程被 kill 的轮次不留账**：回合账在收尾时一次性 INSERT（换零 UPDATE / 零僵尸行）。
- **入库没有运行时入口**：改知识文档需 TRUNCATE + 重跑入库测试（见上文）。
- **PDF / DOCX 不支持**：`SourceLoader` 只认 `.md` / `.txt`（依赖已预留，属扩展铺垫）。
- **embedding 中文区分度弱**：相关与无关提问的余弦距离分布重叠，阈值只能粗筛、兜底靠提示词；
  换 embedding 模型必须重新校准。
- **单实例、无监控端点**：无 Micrometer / Actuator；限流、多实例扩展不在范围。

## 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/HANDOFF.md`](docs/HANDOFF.md) | **交接快照**：最终状态、不可违反的约定、技术事实、踩坑索引、面试讲解顺序 |
| [`docs/PROCEDURES.md`](docs/PROCEDURES.md) | **按阶段的完整记录**：目标 / 文件 / 踩坑 / 结果 / 数据流向图；文末是《最终范围与移出项》 |
| [`docs/WORKING-AGREEMENT.md`](docs/WORKING-AGREEMENT.md) | 13 条协作协议（"未放行不写代码""结论必须有证据"等） |
| [`docs/架构问题.md`](docs/架构问题.md) | 第三方架构审计 + 复核结论（阶段 12 的债务台账，已完成结项） |
| [`docs/KNOWLEDGE-SAMPLE-DATA.md`](docs/KNOWLEDGE-SAMPLE-DATA.md) | 示例知识数据的 frontmatter 规则与入库/幂等说明 |
| [`MCP/README.md`](MCP/README.md) | MCP server 的构建与 IDEA 配置说明 |

## 版本与技术事实

| 组件 | 版本 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring AI BOM | 2.0.1 |
| DeepSeek 模型 | `deepseek-flash`（服务端只有 `deepseek-flash` / `deepseek-v4-pro`） |
| Ollama embedding | `mxbai-embed-large:latest`（1024 维） |
| MySQL / Redis / pgvector | `mysql:8.0.39` / `valkey:8` / `pgvector:pg16`（vector 0.8.6） |
| 前端 | Vue 3 + Vite |
| 测试 | JUnit 6 + AssertJ + ArchUnit 1.5.1 |
