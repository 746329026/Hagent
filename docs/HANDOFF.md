# Hagent 项目交接文档（最终版）

> **用途**：新开对话 / 新接手者先读本文件。**项目已完结（阶段 0–17）**；
> 本文件是"最终状态 + 不可违反的约定 + 技术事实 + 踩坑索引"的快照。
> 每个阶段的目标 / 文件 / 踩坑 / 结果的完整历史在 `docs/PROCEDURES.md`。
>
> **最后更新（2026-10-22）**：阶段 17（工程收尾）完成 —— 全仓注释精简、
> 文档全量核对（目录 / 类名 / 计数 / 移除已移出范围的叙述）、根 README、失效静态页删除。
> 最终验证：主应用 **369 通过 / 2 skipped** + `MCP/time-server` **3 条**；
> 真实 Key 端到端由本人逐阶段验证通过。
> 仓库：https://github.com/746329026/Hagent

---

## 一、这个项目是什么

为大模型应用岗位实习准备的简历项目：**企业办公 Agent**，主张"高扩展性 + 高度工程化、模块化"。

**项目定位**：为大模型应用岗位实习准备的简历项目：**企业办公 Agent**，主张"高扩展性 + 高度工程化、模块化"。

**题目要求的能力**：会话/工作流模式、统一异常处理、Spring AI 框架、RAG 企业知识库、
基于 MySQL/Redis/`.md` 的存储与记忆系统、tool call 工具调用 —— 全部已落地，另加了回合账与 MCP。

**最终进度（阶段 0–17，全部收尾）**：

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0–7 | 工程骨架 / 模型接入 / SSE / 结构化输出（意图层已撤回）/ 会话记忆 / RAG / 记忆双层重构 | ✅ 已完成 |
| 8 | advisor 链 → `AgentPipeline`（直连 `ChatModel`） | ✅ 已完成 |
| 9 | 工具调用与多步 Agent（工具循环 + 事件 + 审计表） | ✅ 已完成 |
| 10 | 前端页面 + 会话目录 + 思考落库 | ✅ 已完成 |
| 11 | 长期记忆（`.md` 索引 + 三个工具） | ✅ 已完成 |
| 12 | 结构治理（包级环 8 → 0；唯一出处收口） | ✅ 已完成 |
| 13 | 工作流模式（拆解 → 逐步执行 → 两次重试后放弃） | ✅ 已完成 |
| 14 | 可观测性与审计（回合账 + 会话删除接口） | ✅ 已完成 |
| 15 | 新工具追加（工具分组 / 最小权限） | ❌ 移出范围（`getCurrentTime` 后由 MCP 交付） |
| 16 | MCP（工具来源可插拔，独立进程时间 server） | ✅ 已完成 |
| 17 | 工程收尾（注释精简 / 文档全量更新 / README） | ✅ 已完成 |

**最终测试状态**：主应用 **369 通过 / 2 skipped**（50 个测试类；`mvnw.cmd -B -ntp clean test`）
+ `MCP/time-server` **3 条**。真实 Key 的端到端验证由本人逐阶段确认通过。

**最终能力清单**：

| 能力 | 状态 |
|---|---|
| 静/流式对话（SSE，带思考过程） | ✅ |
| RAG 检索 —— **由模型自主决定何时调工具**（不是每轮必跑） | ✅ |
| 密级过滤（PUBLIC / INTERNAL / CONFIDENTIAL，SQL 层拦截） | ✅ |
| 会话记忆（MySQL 记录层 + Redis 上下文层，两个角色） | ✅ |
| 思考过程落库（`@@step:` 标记）+ 历史回放 | ✅ |
| 会话列表 / 改名 / 删除 / 全部历史 | ✅ |
| 长期记忆（跨会话的事实与偏好，模型自主读写） | ✅ |
| 工作流编排（先拆解 → 逐步执行 → 汇总） | ✅ |
| 回合账（**每轮一行**：耗时 / 首字 / 次数 / token / 结局） | ✅ |
| MCP 工具来源可插拔（独立进程，stdio） | ✅ |
| 鉴权（只有明文 token 的身份传递，**没有真正的认证**） | ❌ 移出范围（决策 71：只预留链路） |

**明确不做 / 已知限制**：完整清单与理由见 `docs/PROCEDURES.md` 的《最终范围与移出项》。

> ⚠️ **跑测试前必须设 `DEEPSEEK_API_KEY`**（任意非空值即可；测试不调真模型，启动期只做 `Assert.hasText` 校验）：
> ```powershell
> $env:DEEPSEEK_API_KEY = "test-placeholder-never-used"
> ```
> 真实 Key 只在端到端验证时需要，测试不需要。

---

## 二、工作协议（`docs/WORKING-AGREEMENT.md`，**13 条**）—— 最重要的部分

**违反这些协议比写错代码更严重。** 逐条摘要：

### 第 1 条：每阶段五步走，**未获明确放行不写代码**

```
① 讲清"解决什么问题、涉及哪些概念"  →  ② 提出需要本人判断的设计问题
→ ③ 纠正错误、确认理解  →  ④ 本人明确放行（OK）→  ⑤ 才写代码
```

### 第 2 条：每次代码改动后，**固定六段汇报**（★ 第 2 段是"建议阅读顺序"）

```
【本次改动文件】新增/修改/删除，每个都要说明"动了哪里、为什么动"
【建议阅读顺序】★ 不只要清单，还要"从哪读起"：先契约 → 再结构 → 再难点 → 再装配 → 最后测试；
                 每项写清【读的时候看什么】（哪个注释块/哪个方法），最后标"只有一小时读哪三处"
【编译与测试】  实际命令与输出，不写"应该通过"
【本阶段数据流向】一张 Mermaid 流程图 + 一张思维导图，
                  并同步写入 docs/PROCEDURES.md【该阶段小节的开头】
【为后续阶段准备了什么】
【需要本人决策的事】
```

### 第 3 条：验证原则 —— **结论必须有证据**

- 证据形式：日志、SQL 直查、**原始字节**、**字节码（javap）**、真实 HTTP 往返
- 加验证项时要同时确认：**① 数据确实发出去了；② 观察者能看到它**
- **教练（AI）的断言同样需要验证** —— 曾断言"删掉注解处理器配置会编译失败"，实测只是警告
- 对第三方 API，**先读真实签名（javap / 配置元数据），不凭记忆写**

### 第 4 条：代码归属

机器杂活（Docker、版本核查、环境排障）由 AI 做；
**业务设计、技术选型、核心抽象、异常策略必须由用户拍板**。

### 第 5 条：文档即作品

`docs/PROCEDURES.md` 按阶段记录；踩坑只保留**有可迁移认知的大坑**。

### 第 6 条：同一操作性错误重复 ≥2 次 → **必须机制化**，不许继续口头提醒

### 第 7 条：动手前必须能回答三个问题，答不出来就不做

| 问题 | 不合格的答案 |
|---|---|
| ① 它解决什么具体问题？ | "业界都这么做"、"框架支持" |
| ② **它的产出谁消费？** | "会存下来"、"给下游用" |
| ③ 不做它会怎样？ | "不够完整"、"不够工程化" |

**警戒信号**（出现即停下重新论证）：
产出"以后会被用到"／让模型做两遍同一个判断／靠"两个组件约定不互相干涉"维持隔离／
抽象还没有第二个使用者／**需要在注释里反复解释"它其实不参与主流程"**。
这条是**"前置意图提取层"被整体删除**换来的。

### 第 8 条：文档小节编号**禁止手工改**，必须脚本化重排 + 双校验（编号连续 + 锚点有效）

GitHub 锚点规则：转小写、空格→`-`、**标点与反引号会被剥掉**。

### 第 9 条：编码约定（中文全链路 UTF-8）

- **`.ps1` 必须带 BOM**（PowerShell 5.1 无 BOM 时按 GBK 读，中文语法崩）；
  **`.java`/`.yml` 必须无 BOM** —— 规则相反
- 后续**必须显式做**的四处：MySQL 建库建表用 `utf8mb4`、JDBC URL 加 `characterEncoding=utf8`、
  `.md` 文件读写显式 `UTF_8`、CSV 导出加 BOM
- **不需要做**的（避免画蛇添足）：`CharacterEncodingFilter`、`produces` 加 charset、
  改 `StringHttpMessageConverter` —— Spring Boot 已经默认 UTF-8

### 第 10 条：切面必须考虑"内层被重复调用"，可重复的用幂等

**机制**：框架的 `ToolCallingAdvisor` order = `Integer.MIN_VALUE + 300`，在**最外层**，
自带工具循环 —— **挂在它内层的切面，其 `adviseCall` 会被跑 N 次**（N = 工具调用轮数）。

**判断方法（写代码前问三句）**：
1. 这个切面在 `ToolCallingAdvisor` 内层吗？→ **是就假设它会被跑 N 次**
2. 它每次跑会产生新副作用吗？→ 产生就**必须幂等**
3. 幂等靠什么保证？→ **靠结构**（缓存命中/唯一键/单一拼装者），**不靠"记得先检查"**

---

### 第 11 条：讨论问题时说重点，不堆料

**规则**：讨论设计时**只讲清"是什么、为什么、要你定什么"**，三条约束：

1. **不长篇罗列文件清单** —— 只在"进入新阶段"与"最终放行前"给；
2. **已拍板的事不再反复提** —— 最终敲定时一句话带过；
3. **一次只摆当前真正卡住的问题**，并给出建议 + 理由。

**不受限制的**：证据（日志/字节码/真实 HTTP）、"三问"、踩坑的完整记录（写进 `PROCEDURES.md`）。

**为什么**：堆料会①**掩盖待决问题**（该拍板的那条被埋掉）②**浪费注意力**（读者要不断分辨"哪些还要我定"）。

### 第 12 条：命名必须描述本质，不得携带历史概念

**规则**：名字要描述它**现在是什么**，不能描述它**曾经是什么**、也不能指向**别处的东西**。
配一张**术语表**（概念 → 代码名，唯一出处）。发现不一致时**记录**进待办，而不是顺手改（改名单独立项，理由见协议）。

**为什么**：名字错 → 后来者搜不到东西、或照着名字理解错语义；而这类错误**没有编译器告警**。

### 第 13 条：尽可能复用已有的概念与变量

**规则**：① 引入新字段/新枚举/新常量之前**先 grep，不靠回忆**；② 有就复用，且必须调**同一个定义点**
（不是照着写一份等价逻辑）；③ 真要新增，得说清它比已有的**多出了什么**。

**为什么**：同一语义两处定义**迟早漂移**，而漂移不报错。正例：`AccessLevel.visibleLevels` 由
`visibleTo` 派生，所以"判定"与"检索过滤"永不会给出不同答案。反例：`seq` 与 `id` 并存（坑 30）。

**阶段 11 被当场纠正的例子**：我提出"记忆条目带一个 `clearance`"，而它早已是
`Role.clearance()` → `AccessLevel` —— 我造了个同义词，会让"密级"看起来有两套。

---

## 三、技术事实（**全部实测过，不要凭记忆覆盖**）

### 3.1 版本（GA 稳定版，禁用里程碑版）

| 组件 | 版本 |
|---|---|
| Spring Boot | `4.1.1` |
| Spring AI BOM | `2.0.1` |
| Spring Framework | `7.0.9` |
| Java | 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`） |
| Tomcat | `11.0.24`（内嵌） |
| MySQL | `9.7.0` 驱动 / 容器 `mysql:8.0.39` |
| Maven | 3.9.12（**只有 wrapper，无全局 `mvn`**） |

### 3.2 三个必须知道的"反直觉"事实

**① Jackson 是 3，不是 2**：`tools.jackson.databind.ObjectMapper` / `tools.jackson.core.JacksonException`。
只有注解包仍是 `com.fasterxml.jackson.annotation`。

**② Spring Framework 的 `StringHttpMessageConverter.DEFAULT_CHARSET = ISO_8859_1`，
但 Spring Boot 把它换成了 UTF-8**：

```
Spring Boot 4.1.1 的 StringHttpMessageConvertersCustomizer：
    new StringHttpMessageConverter(properties.getStringEncodingCharset())
配置项：spring.http.converters.string-encoding-charset   默认值 = UTF-8
```
→ **"框架的默认值"和"你在用的发行版的默认值"是两件事。**

**③ 框架异常之间没有共同父类**：Spring AI 2.0.1 里**不存在 `AiException`**。
`NonTransientAiException`、`ToolExecutionException` 都直接继承 `RuntimeException`，彼此无关。

### 3.3 模型（DeepSeek 云 + 本地 Ollama）

| 用途 | 实现 | 关键实测 |
|---|---|---|
| chat | DeepSeek `deepseek-flash` | 账户上**只有** `deepseek-flash` 与 `deepseek-v4-pro`；`deepseek-chat`/`deepseek-reasoner` **不存在且会静默降级** |
| embedding | Ollama `mxbai-embed-large:latest` | **1024 维**（实测） |

**reasoning-effort**：`HIGH` → HTTP 422；必须用小写 `high`
（Spring AI 枚举 `HIGH` 上有 `@JsonProperty("high")`）。

**temperature 刻意不设置** —— 思考模式会静默忽略它。

### 3.4 ★ embedding 输入上限（一个静默失败的坑）

**实测边界**：

```
1000 汉字 → 未截断
1010 汉字 → ★ 后半部分【完全被忽略】
2004 汉字 → 不报错，返回正常 1024 维向量（静默截断）
```

**判定方法（可复现）**：把块的前半保留、后半换成完全不同的内容，
算两个向量的余弦相似度。**相似度 = 1.0000 就说明后半根本没进向量。**

**所以 `chunkSize` 必须显著小于 1000** —— 当前配 `400`（用 40%，留 2.5 倍余量）。
**这个坑的危险之处：不报错、维度正常、检索悄悄变差。**

### 3.5 ★ 检索相关性实测（阈值校准的依据）

用 `mxbai-embed-large` 测的**余弦距离**（`0` = 完全相同，`1` = 正交，`2` = 相反）：

**相关提问**：

| 提问 | 到最近块的距离 |
|---|---|
| 年假要提前几天申请 | 0.2168 |
| 出差住宿费能报多少 | 0.2237 |
| 我们的年假怎么算？ | 0.2514 |
| 公司总部在哪里 | 0.2570 |
| 请假流程是什么 | **0.4827** ← 相关却很"远" |

**无关提问**：

| 提问 | 到最近块的距离 |
|---|---|
| **今天杭州天气怎么样** | **0.2883** ← ★ **比"公司总部在哪里"还近** |
| 帮我翻译这段英文 | 0.3396 |
| 帮我写一封催款邮件 | 0.3563 |
| 推荐几本好看的小说 | 0.3819 |
| 红烧肉怎么做好吃 | 0.5055 |

**结论：相关与无关的距离分布完全重叠，阈值挡不住无关问题。**
要让"请假流程"通过，阈值必须 > 0.49；要挡住"今天天气"，阈值必须 < 0.29 —— **没有这样的数存在**。

**所以阈值 0.5 的定位是"粗筛"，真正的兜底必须靠提示词约束。**

**根因**：`mxbai-embed-large` 是英文优化模型，对中文短文本区分度不足，距离被压在 0.2~0.5 的窄带里。
**⚠️ 换 embedding 模型（如 BGE-m3）后距离分布会完全不同，阈值必须重新校准。**

### 3.6 ★ 权限过滤的 SQL 形态与性能（20 万行实测）

**框架生成的过滤条件走 jsonpath，不是 `->>`**：

```sql
eq  : metadata::jsonb @@ '$."level" == "INTERNAL"'::jsonpath
in  : metadata::jsonb @@ '($."level" == "PUBLIC" || $."level" == "INTERNAL")'::jsonpath
ne  : metadata::jsonb @@ '$."dept" != "HR"'::jsonpath
and : metadata::jsonb @@ '($."level" == "PUBLIC") && $."dept" == "HR"'::jsonpath
```

**性能实测**（20 万行，检索形态 = 过滤 + 向量排序 + topK）：

| 方案 | 耗时 |
|---|---|
| 过滤（jsonpath）+ 排序，**无 GIN** | **66.4 ms** |
| 过滤（jsonpath）+ 排序，**有 GIN** | **41.4 ms**（快 3.4 倍） |
| **不做过滤**，直接排序 | **317.0 ms**（比过滤无索引还慢 4.8 倍） |
| 高权限视角（80% 可见）+ GIN | 303.7 ms（选择性差时 GIN 帮助很小） |

**建 GIN 索引的确切 DDL**（本阶段**决定不加**，理由见第七节）：

```sql
CREATE INDEX hagent_knowledge_meta_gin
    ON hagent_knowledge USING gin ((metadata::jsonb) jsonb_path_ops);
```

**⚠️ 三个风险**：
1. 表达式必须**逐字符匹配**框架生成的 `metadata::jsonb`，否则索引**白建且不报错**
2. `jsonb_path_ops` **不支持 `!=`** —— 将来若权限规则用排除条件，这个索引直接失效
3. 需要**我们自己的 DDL** → 与框架的建表职责重叠（阶段 6 已因 DDL/配置漂移踩过坑）

**⚠️ 另一个静默语义陷阱**：`metadata::jsonb @@ '$."dept" != "HR"'::jsonpath`
对**字段不存在的行返回 unknown（不匹配）** → 用 `dept` 做排除会漏掉 `dept` 缺失的记录。

### 3.7 向量表结构（框架建的，实测）

```sql
CREATE TABLE hagent_knowledge (
    id        uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   text,
    metadata  json,          -- ★ 不是 jsonb
    embedding vector(1024)
);
-- 另有 HNSW 向量索引（vector_cosine_ops）
```

**框架只在 `initialize-schema: true` 时建表**；为 false 时它**既不建表也不校验**
（`schema-validation` 默认也是 false，校验是空操作）→ 表永远不存在，直到第一次写入才报错。
**这是阶段 6 踩过的坑，已修（改为 true + 启动自检失败即中止）。**

### 3.8 切面机制（⚠️ **历史记录**：阶段 1–7 用过，阶段 8 已整体删除）

| 事实 | 证据 |
|---|---|
| **数字小 = 外层 = 先执行** | 实验：push(10) 后 push(-5) → 执行顺序是 -5, 10 |
| **order 相同的切面是"并列兄弟"，不是嵌套** | 实验：两个 order=1 的切面互不嵌套 |
| `ChatClientRequest.Builder.context(Map)` 是 **`putAll` 累加**，不是覆盖 | 字节码 |
| 框架**不做** `${...}` 占位符解析 | 无 `PlaceholderHelper` 类；`MessageChatMemoryAdvisor` 直接按字符串取 key |
| `ChatMemory.CONVERSATION_ID` = **`"chat_memory_conversation_id"`** | javap -constants |
| 框架的 `MessageChatMemoryAdvisor.before()` 用 `addAll(instructions)` **保留原有消息**，且有 `isMemoryAlreadyInPrompt()` 防重复注入 | 反编译 |

> ⚠️ **本节是历史记录，别照着它改现在的代码。**
> 阶段 8 已把整条 advisor 链（7 个自定义切面）删除，现在只有一个编排者
> `AgentPipeline`，**顺序就是代码行序**。
> 保留本节的两个理由：① 这些**框架机制本身仍是事实**（将来若改用 `ChatClient` 会再遇到）；
> ② 踩坑 #17 / #19 / #22 都是从这条链上换来的。

**当时的自定义切面（阶段 6 后半 – 阶段 7；阶段 8 已全部删除）**：

| order | 切面 | 职责 | before / after |
|---|---|---|---|
| `MIN+100` | `PromptTraceAdvisor` | 记"请求进链时的样子" + 回复 + **全链耗时** | 都有 |
| `MIN+150` | `MemoryWritebackAdvisor` | 聚合出最终答 → `append` **一次** | **只 after** |
| `MIN+200` | `MemoryReadAdvisor` | `load` 历史 → 写 context | **只 before** |
| `MIN+220` | `RagRetrievalAdvisor` | 检索（含权限过滤）→ 片段写 context | **只 before** |
| `MIN+250` | `PromptAssemblyAdvisor` | ★ **唯一重建消息列表者** + 记"模型收到的 prompt" | **只 before** |
| `MIN+300` | `ToolCallingAdvisor`（框架） | 工具循环（N 轮，自理内部历史） | — |
| `MAX` | `ChatModelCallAdvisor`（框架） | 真正调模型 | — |

**order 常量集中在 `com.hagent.advisor.AdvisorOrders`**（具名常量，不写裸数字 ——
因为 `order` 会进框架的 observation 标签，裸数字会污染监控）。

### 3.8.1 ★★ 三条"必须先知道"的切面机制（阶段 7 字节码实证；**本项目阶段 8 起不再有切面链**）

**① 外层切面看不到内层注入的内容（方向上曾被写反）**

`DefaultAroundAdvisorChain.nextCall` 从 `Deque` 里 `pop()` 出下一个切面、把 request 的
**新版本**继续往下交 —— **内层的修改不回流给外层**。所以最外层 in `before` 侧看到的
永远是"还没被加工过"的那一版。

```
before 顺序：order 升序        1 → 2 → 3 → … → 模型
after  顺序：order 降序        模型 → … → 3 → 2 → 1   ← ★ 镜像，不是正序
```

→ **要记"模型真正收到的 prompt"，切面必须放最内层（order 最大）**，
不是最外层。当时由 `PromptAssemblyAdvisor`（`MIN+250`，最后一个改 prompt 的人）负责；
**现在没有切面了，这件事由 `PromptBuilder` 做**（它仍是"唯一重建消息列表者"）。
→ 当时的断言固化在 `AdvisorOrderTest#outerAdvisorCannotSeeInnerInjection`（该测试已随切面链一起删除）。

**② 框架探测"记忆切面是否在工具循环内层"，据此关掉它自己的内部历史**

```java
// DefaultChatClient$DefaultChatClientRequestSpec#autoRegisterToolCallingAdvisor
boolean memInside = advisors.stream()
    .anyMatch(a -> a instanceof MemoryAdvisor && a.getOrder() > toolCallingAdvisorOrder);
... .conversationHistoryEnabled(!memInside)
```

`MemoryAdvisor` 是**空标记接口**。开关为真 → 循环累积全部历史；
为假 → 只留 `system + 最新一条`，把历史维护交给内层记忆切面。

→ **本项目刻意不实现 `MemoryAdvisor`**：我们只想要"单轮问答的记忆"，
不想接管"多步循环的记忆"（我们的记忆只存"问 + 最终答"，**不存中间工具轮次**，
接管会让阶段 8 的工具循环缺 `tool_result`）。

**③ `ChatClientRequest.mutate()` 会复制 prompt 与 context**

```java
// 字节码：mutate() → prompt(this.prompt.copy()) + context(new HashMap<>(this.context))
```
→ 切面之间传请求级数据（`Context.Builder.context(Map)` 内部是 `putAll` 累加）是可行的。
→ **不要直接 `request.context().put(...)`**：context 可能是 `Map.of(...)`（不可变），
也可能被共享；走 `mutate().context(k, v).build()` 才稳定。

---

## 四、已定的设计决策（不要再重新论证）

> ⚠️ **本节是"决策流水账"**：记录的是**每次为什么那样选**，不是"现在代码长什么样"。
> 早期条目描述的结构有些已被后续阶段推翻 —— 凡是这样的，条目里都标了 **⚠️ 已被阶段 N 推翻**。
> **当前真实结构以 §三（技术事实）与 §八（目录结构）为准**；本节与它们冲突时，以 §三/§八 为准。

| # | 决策 | 理由摘要 |
|---|---|---|
| 1 | 存储分层：pgvector（向量）/ MySQL（业务权威）/ Redis（缓存）/ `.md`（长期记忆） | — |
| 2 | ★ **阶段 7 修订**：会话记忆是**两个角色**，不是"一份数据的两种存法"：<br>**MySQL = 对话记录**（权威·完整·永久 —— 用户刷新页面要看）<br>**Redis = 模型上下文**（有损·TTL·可重建 —— 模型这次要发的） | **原措辞"Redis 是 MySQL 的缓存"会被读成"同一份数据存两份"**，从而导出"写库后删缓存"这种**命中率≈0**的策略（会话历史是 1 读 : 1 写，且写的正是读的那份）。改成"两个角色"后，写路径自然是"两层各自写入"，命中率问题消失 |
| 3 | ★ **阶段 7 修订**：`ConversationMemory` 是**门面**，背后是两层**各自可换**（record: `mysql`\|`file`；context: `redis`\|`none`），**不再是"一个 type 三选一"** | 两层"同时存在、各司其职"，条件装配表达不了那种关系。旧键 `hagent.memory.chat.type` **已废弃**（配了会启动失败，刻意不留兼容层） |
| 4 | **预算只覆盖历史**，不含 system 与本次问题 | 结构上杜绝"截断把当前问题丢掉" |
| 5 | **先调模型、后写入**记忆 | 失败调用不留半条记录；预算天然不含当前问题 |
| 6 | 会话 ID：**服务端生成随机 UUID**，走 `request.context()`，**不进 prompt** | 防 IDOR；prompt 会发给模型提供方，context 不会 |
| 7 | 记忆预算：会话 `5000 token / 30 条`；工作流 `50000 token / 200 条` | 工作流多一个数量级（每轮工具返回都可能是一整篇文档） |
| 8 | 配置未实现的实现 → **启动期失败，不静默降级** | 静默降级会让你以为配置生效了 |
| 9 | 默认密级 **INTERNAL** | 默认偏保守：忘了打标签时"该看到的人看不到"好过"不该看到的人看到" |
| 10 | 密级三级 `PUBLIC/INTERNAL/CONFIDENTIAL`，过滤规则 `doc.rank <= user.rank` | 有序等级 → 一行比较 → 能直接翻译成 SQL |
| 11 | `dept` **只存不用** | "多部门用户取并集还是交集"是组织架构问题，需要业务定义 |
| 12 | `chunkSize = 400` / `overlap = 60` | embedding 上限 ~1000 的安全余量 + 向量集中度 + prompt 预算 |
| 13 | **不加** `maxSize` 的启动自检 | 400 远低于 1000，不存在现实风险；加自检是为假想误用付真实复杂度 |
| 14 | 幂等身份：`source`（更新定位）+ `docHash`（文档级跳过）+ `contentHash`（块级）+ `chunkIndex` | `docHash` **含 level/kind** —— 只改密级也算变更，否则权限调整静默失效 |
| 15 | `topK = 3`，阈值 `0.5`（定位为**粗筛**） | 3 × 400 字 ≈ 1200 token，占会话预算 24% |
| 16 | `kind` 枚举**承载切分策略与默认密级** | 新增种类时编译器强制填齐，防"双字典"陷阱 |
| 17 | RAG 片段**走 context**，不由各切面各自追加 —— 由**单一聚合器**统一拼装 | "谁重建消息列表，只有一个人" |
| 18 | **聚合器方案**：前置切面只产片段，order 最大的切面统一拼装 | 用户拍板；框架无现成 aggregator |
| 19 | RAG 检索切面**必须幂等**（context 有片段则跳过） | 防工具循环里重复检索（N 倍成本） |
| 20 | authenticator 走**拦截器 + 自定义 token**（不用 Spring Security） | 只需"身份传递链路"，发凭证是另一件事 |
| 21 | **匿名请求 = 最小权限（只看 PUBLIC）**，不拒绝 | 符合"默认偏保守"，且不打断已有验证流程 |
| 22 | 出处走**甲方案**（给模型元数据）+ 提示词约束"禁伪造来源" | 没有元数据时"说明出处"只能编造 |
| 23 | 24 字核心价值观**不放进代码库**（用企业价值观代替） | 企业知识库示例应场景自洽；主动规避不必要的敏感性 |

### 阶段 6 后半新增（本轮拍板）

| # | 决策 | 理由摘要 |
|---|---|---|
| 24 | **保留全部切面形态**（不在 app 层写顺序 pipeline） | 与框架的扩展模型对齐（框架自带 `SafeGuard`/`SimpleLogger`/`StructuredOutputValidation` 等**都带位置要求**，不在链里就无法表达相对位置）；切面**自带 observation**（`order` 会进标签）；顺序是可被一个测试整体断言的**声明式数据**。代价是 `context` 字符串 key + order 常量 |
| 25 | **全部自定义切面放工具循环之外**（`MIN+100…250`） | 一次请求只被调用一次 → 写回一次、检索一次、拼装唯一。检索的"幂等"从**必须**降级为**防御**（协议第 10 条：靠结构而非靠检查） |
| 26 | **`ConversationMemoryAdvisor` 拆成 `MemoryReadAdvisor` + `MemoryWritebackAdvisor`**<br>⚠️ 两个切面**已随阶段 8 的切面链一起删除**（读/写变成 `AgentPipeline` 里的两行） | 两者位置要求相反（读要在拼装前、写要在链收尾），一个 order 装不下 |
| 27 | **只有"拼装切面"改 prompt**，其余只写 context | "谁重建消息列表，只有一个人" —— 把决策 17/18 的**实质**保留，形式改成"唯一且最后执行"（不再是"order 最大的切面"） |
| 28 | **鉴权走拦截器，不做成切面**；`UserContext` 走请求属性 + 方法参数 | 三条判据：粒度（每请求 vs 每模型调用）、能否拿到 `HttpServletRequest`、失败该表达成 401/403 而非模型错误。切面在 Controller 之后 → 放它会"先花掉检索成本再发现身份有问题" |
| 29 | **token 缺失 = 匿名（只看 PUBLIC）；token 非法 = 抛异常** | 缺失是合法状态（决策 21 不拒绝）；**写错不是** —— 静默降级成匿名会让"我是管理员却查不到东西"毫无线索（决策 8） |
| 30 | **阈值单位用"距离"**（配置 `max-distance` / `high-max-distance` / `medium-max-distance`） | 与校准证据（HANDOFF 3.5 的距离表）同单位；配置键名里带 `distance` 杜绝歧义；换算只在 `RetrievalProperties` 两处，且启动日志同时打两个单位 |
| 31 | **不建 `PromptFragment` 类** | 它是 `RetrievedChunk` 的纯透传，没有第二个使用者（协议第 7 条 → 不建）。片段格式化当时放在 `PromptAssemblyAdvisor#renderFragments`（现为 `PromptBuilder`） |
| 32 | **不实现 `MemoryAdvisor` 标记接口** | 实现它会让框架关掉自己的循环历史、把多步历史交给我们 —— 而我们的记忆只存"问 + 最终答"，**不存中间工具轮次**（阶段 8 会缺 `tool_result`） |
| 33 | **`AccessLevel.visibleLevels()` 放在枚举内部**（而非检索代码里） | 让"比较式判定"与"枚举式过滤"是**同一条规则的两个形态**，由前者派生后者 —— 规则只有一个出处 |

### 阶段 6 后半复核新增（本轮拍板）

| # | 决策 | 理由摘要 |
|---|---|---|
| 34 | **保留 `ChatMemoryAdapter` 与 `chatMemory` Bean（★ 不可删）**<br>⚠️ **已被阶段 8 推翻：`ChatMemoryAdapter` 已删除**（启动自检反向断言取代它，见 `PROCEDURES.md` 第 8 节） | 它是框架边界的 **闸门 + 占位**：`ChatMemoryAutoConfiguration` 的两个 `@Bean` 都是 `@ConditionalOnMissingBean`，我们的 Bean 一删，框架会装 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` —— **纯内存、重启即丢、无 token 预算**。这正是 `MemoryProperties` 自己警告过的失败模式。详见 `PROCEDURES.md` 阶段 5"坑 4 的复核补充" |
| 35 | **"模式→预算"抽成 `MemoryBudgets` 共用值对象**（而非两处各建一份）<br>⚠️ **阶段 8 已删除这个类**（`switch` 搬进 `AgentPipeline#budgetOf`），本条只保留"为什么曾经抽它"的记录 | 原先是 `MemoryReadAdvisor` 与 `ChatMemoryAdapter` 各抄一份 `Map.of(...)`：① 手写列举**没有任何机制**保证覆盖全部模式 → 新增模式漏改会在 `budgets.get(mode).maxTokens()` 上 **NPE**；② 两处各自演化，会让同一会话在两条路径下拿到**不同预算**且不报错。现在用**穷尽 `switch` + 遍历 `MemoryMode.values()` 建表**，把 ① 变成**编译期错误** |
| 36 | **回归防线要「植入缺陷验证过」** | `MemoryBudgetsTest#advisorAndAdapterAgreeOnTheSameBudget`（**已随阶段 8 删除**，但这条纪律留下了）不只是"跑了就过"—— 已**临时把主链路的预算改歪、确认它真的失败**，再恢复。`test passes` 不能证明防线有效，**只有"bug 在时会失败"才能** |

---

### 阶段 7 新增（本轮拍板）

| # | 决策 | 理由摘要 |
|---|---|---|
| 37 | **写路径固定"先记录层、后上下文层"** | 守住不变量「**上下文永不比记录新**」。破坏它的后果是"模型知道、用户刷新却看不到"—— 最难排查的一类不一致 |
| 38 | **记录层不做预算裁剪**（只按条数取最近 N 条） | 记录就是记录，裁剪属于"给模型看的那一份"。将来"给用户的完整历史读 API"也要用它的完整数据 |
| 39 | **上下文层存"原始消息"，不存裁剪结果** | 预算随模式变（30 / 200 条）；存裁剪结果会让缓存键必须带 budget。裁剪由门面统一做（`MemoryTrimmer` 仍是唯一出处） |
| 40 | **写失败分级**：记录层失败=ERROR（**但不影响本次响应**）且**不再写上下文层**；上下文层失败=WARN | 模型回复已产生、token 已花，为写库失败丢掉它是双重损失。而"记录层失败仍写缓存"会造成"模型知道、用户看不到" |
| 41 | `load` 两层都失败 → **降级为"无历史"** + ERROR | 与"检索失败降级"一致：读不到记忆不该让对话失败 |
| 42 | **每层一个短熔断窗口**（默认 30s，0=关闭），**不引入 Resilience4j** | 不熔断则每个请求白等一次连接超时（3s/5s）—— 故障期体验反而比正常时差得多。而这个需求只是"一个 AtomicLong"，引框架属于过度设计 |
| 43 | **依赖状态放 `/health/detail`，不放 `/health`** | `/health` 是**存活**检查，定位是"不碰任何外部依赖"。把会真连库的探测放进去，会让"依赖抖动"被判成"进程死了"→ 被编排系统反复重启 → 小故障放大成雪崩 |
| 44 | **不做"应用自动重启容器"** | ①越权（多实例惊群、无权限控制）②治不了根因（磁盘满/OOM 重启后依旧，只掩盖原因）③技术上不成立（容器内没有 docker CLI，挂 `docker.sock` 等于交出宿主机权限）。**做法是"如实报告 + 交给外部"** |
| 45 | ★ **表结构：删掉 `seq`，用自增 `id` 排序**（`PRIMARY KEY(id)` + `KEY idx_conv_id`） | "无洞会话内序号"必须加锁才能生成：普通读+重试会**耗尽重试**，锁定读+INSERT 会**间隙锁死锁**。而 `seq` 提供的属性**没有消费者**（排序由 `id` 天然提供，分页 keyset 用 `id` 更稳）。为一个没人要的属性付锁与死锁的代价 —— 不值 |
| 46 | **DDL 显式手写 + 手动执行一次 + 启动自检**（表存在 / `content` 是 utf8mb4 / `idx_conv_id` 存在） | 不让应用自动建表：`CREATE TABLE IF NOT EXISTS` 查不出"表在但结构不对"，会静默沿用旧表（阶段 6 同类坑） |
| 47 | **契约测试**：一个抽象类 + 三种层组合各继承它 | 把"换实现只改配置"从"靠人记得测"变成**机制保证**：新实现天然被同一组断言检验 |
| 48 | ★ **业务主库数据源显式声明**（`BusinessDataSourceConfig` + `@Primary`）；**所有 pgvector 注入点按 `@Qualifier` 取** | 见踩坑 28、29 —— 自动装配被顶掉导致业务主库根本不存在；`@Primary` 又压过参数名匹配劫持了向量库的 `JdbcTemplate` |
| 49 | **删除 `InMemoryConversationMemory`** | 死代码：无 Bean、无配置入口、无任何测试引用。新设计下它的位置由 `context-cache: none` 覆盖 |
| 50 | **保留 `file` 作为记录层实现** | 零基础设施兜底 + 人可读的成长记录；生产记录层是 MySQL |

### 阶段 10 / 11 新增（本轮拍板）

| # | 决策 | 理由摘要 |
|---|---|---|
| 51 | **思考落库到 `chat_message.reasoning`，用 `@@step:N` 文本标记** | 第一消费者是人（SQL 直查 / 前端折叠）。JSON 在 `SELECT` 里是一坨转义；而每步只有三件事实（步号、这步之后调了什么工具、想了什么），一行文本足够 |
| 52 | **思考存 `AssistantMessage.properties`，绝不存 `reasoningContent`** | 后者会被 `createRequest` **回填进请求**（`reasoning_content` 是输出专用字段）→ 轻则白烧 token，重则 400。症状会是"下一轮莫名失败"，几乎不可能被联想到"历史里带了思考" |
| 53 | **`mode` 落 `chat_conversation.mode`，首建后不再由请求改变** | 否则客户端能每轮换模式，让历史预算在 5k/50k 间来回跳，且事后查不到原因 |
| 54 | **SSE 文本载荷用 JSON（`{"text":"…"}`）** | `SseEmitter` **不转义换行**（字节码可证），token 里的 `\n` 会切断帧 —— 而段落空行被当成帧分隔符吃掉，症状是"排版偶发错乱"（坑 38） |
| 55 | **长期记忆：索引常驻注入 + 明细按需**；索引 ≤1000 字，超限**拒绝写** | 记忆不读=没存；全量注入会吃光会话预算；静默淘汰=悄悄丢用户数据 |
| 56 | **`saveMemory` 是 upsert，不加 `updateMemory`** | 对 LLM 来说"步数"和"歧义"都是成本：`forget+save` 是两步非原子（中间失败=丢记忆），`update` 与 `save` 语义重叠只会让它犹豫 |
| 57 | **记忆文件名 = `sha256(title)` 前 8 位** | 标题来自模型 ⇒ 直接当文件名就是目录穿越入口。哈希后**根本构造不出**越界路径；顺带让 upsert 天然成立（同名→同文件） |
| 58 | **记忆密级复用 `AccessLevel`**（`visibleTo`），不引入新概念 | 协议第 13 条的正例：与检索同一个枚举、同一条规则 ⇒ "记忆永远不会比检索更宽松" |

### 阶段 14 新增（本轮拍板）

| # | 决策 | 理由摘要 |
|---|---|---|
| 59 | ★ **一轮一行落库（`chat_turn`），不引 Micrometer / Actuator** | 消费者的三问：Micrometer 的消费者是监控系统（本项目没有 Prometheus），且它给的是聚合数字，**回答不了"这一轮为什么慢"**；引它要加依赖 + 暴露未鉴权端点。而 SQL 直查 + 前端那一行小字，两个消费者都具体 |
| 60 | ★ **`Final.usage` 的语义改为"整轮累加"**（此前是"最后一片"） | 实测反例：一次 3 步 workflow 调了 **7 次**模型（规划 1 + 每步 2），日志只打了最后一次的 `3419`。若拿它当成本，**低估 3~7 倍且不报错** |
| 61 | ★ **三种结局都落一行**：`SUCCESS` / `FAILED` / `CANCELLED`，且**取消独立一档** | 成功率的分母是"所有开始的轮次"；只在成功路径打点，成功率永远是 100%。取消并入 FAILED 会把"用户关页面"算成故障，并入 SUCCESS 又会掩盖真失败 |
| 62 | ★ **`turn_id` 在轮次开始时生成（UUID），收尾时一次性 INSERT** | 工具审计在轮次**中途**就要写，那时自增主键还不存在。UUID 让"归轮"当场成立，且**不需要 UPDATE / RUNNING 状态 / 僵尸行清理**。代价：进程被 kill 的轮次不留痕（已登记） |
| 63 | ★ **本表不给 enabled 开关**（与工具审计刻意不同） | 工具审计的开关是为"`result` 几 KB、增长最快"设的；本表一行约 100B，量级差两个数量级。多一个能关错的键，就多一种"以为在统计其实没有" |
| 64 | ★ **给 `chat_tool_invocation` 补 `user_id` / `turn_id`**（本项目**第一次表迁移**） | 审计的核心是"**谁**做了什么"，而表只会变长、历史补不回来。历史行一律 `unknown`（**不伪造**）。迁移脚本：`db/migrations/001-*.sql` |
| 65 | **`stats` 用独立 SSE 事件名，不改 `done` 的载荷** | 加事件是**纯增量**（未知事件名前端本来就忽略）；改 `done`（`[DONE]` → JSON）是**改已有契约**，两种风险量级不同 |
| 66 | **帧里不发 `user_id` / `role`**（`ChatAppService#statsData` 只放 5 项） | 浏览器就是这个人，回显没有信息量，只是把身份写进前端状态与日志。**record 内部流转、帧里只留要显示的** |
| 67 | **会话删除删三样（消息 / Redis 上下文 / 目录），审计与回合账保留** | 审计是"系统被调用过"的事实，不随业务删除消失。**内容先删、目录后删** —— 让失败可见（先删目录会让"数据还在库里"变成看不见的失败）。⚠️ 代价：审计的 `arguments` 含用户原话 ⇒ "删内容不删痕迹"，**已拍板接受** |
| 68 | **删除不走门面 `ConversationMemory#delete`**（它吞异常） | 对"写回"吞异常是对的（回复已产生，不能为写库失败丢掉它）；对"删除"就是**假删除**（接口 200 而数据还在）。所以删除直接调记录层，让异常走正常错误路径 |
| 69 | ★ **历史上那一行统计靠 `chat_message.turn_id` 关联，不把数字抄进消息** | 抄一份就是两处定义，迟早出现"库里一行、历史另一行"且**不报错**（协议第 13 条）。关联键走 properties ⇒ **`MessageStore.append` 的接口一行没改** |
| 70 | ★ **实时与历史共用 `SessionDtos.TurnStatsView`** | 同一行字只有一个形状 ⇒ 前端一份渲染逻辑。用两套 Map 拼键名 = 同一语义两处定义 |
| 71 | ★ **鉴权只预留接口，不深究**（2026-10-21 拍板） | 账户登录 / 个性化属于**扩展内容**，会把这个项目的重心从"Agent 工程化"拖到"做一套账号体系"。本项目保留"身份传递链路"（`UserTokenResolver` → `UserContext` → 检索过滤 / 审计主体），真正的认证授权**不做** |

### 阶段 16 新增（2026-10-22，MCP 客户端 + 时间 server 样板）

| # | 决策 | 理由摘要 |
|---|---|---|
| 72 | ★ **MCP 只做客户端**（server starter 不进主应用） | 否则主应用自己也对外提供 MCP 服务（多一个协议端点、多一份未鉴权面），而我们要的是消费方 |
| 73 | ★ **MCP server 是独立项目**（`MCP/time-server`） | 独立 pom / 独立构建 / 独立进程：不污染主应用 classpath、边界清晰、换语言只改一行命令 |
| 74 | ★ **stdio**，不是 HTTP | 无端口、无未鉴权端点、不用先手动起两个服务；换 HTTP 只改 `application-mcp.yml` 两行 |
| 75 | ★ **工具清单合并点 = `ToolRegistration`**，并关掉 starter 的 `SyncMcpToolCallbackProvider` | 保持"唯一注册入口"（阶段 9）。两个 `ToolCallbackProvider` 并存 → `AgentPipeline` 按类型注入单个 → **启动即失败** |
| 76 | **MCP 工具名一律 `mcp_<server>_<tool>`** | 工具名由**外部进程**决定，无法假设它不叫 `retrieveKnowledge`；撞名会让本地工具被悄悄顶掉 |
| 77 | **连不上 → 启动失败**，且加**前置检查**（jar 不存在就立刻失败） | 工具缺了不会报错，模型只会说"我没有这个能力"。实测不拦的话要等 **20 秒**，报的却是 `TimeoutException … in 'map'`（一个字没提 jar） |
| 78 | **MCP 配置单独 `application-mcp.yml` + `mcp` profile** | 否则 369 条测试每次拉起 JVM：变慢变脆，还依赖"jar 已构建" |
| 79 | **日志走 stderr**（server 的 `logback-spring.xml`） | stdio 用 stdout 传协议；日志混进去 = "进程活着但一个工具都看不到"，两侧都觉得自己正常 |
| 80 | **只做时间 server；Excel server 与其余工具移出范围**（不是延期） | 形状已经证明过（"多一条 connection 即可"），第二个实例只增加维护面、不增加可讲的东西；`listKnowledgeSources` 与检索重叠，`queryChatHistory` 属鉴权范畴（决策 71） |

> ★ **收尾归档**：上表是决策流水账（保留"当时为什么这样选"）。
> 其中所有"待拍板 / 后续阶段"类内容已在阶段 17 结清或移出；最终范围见 `PROCEDURES.md`《最终范围与移出项》。

---

## 五、已撤回的设计（**红线：不要再做**）

### 前置意图提取层（intent）—— 整体删除

**曾经做完的东西**：`TaskIntent` DTO（含 `Deliverable` 枚举）、`TaskIntentAppService`（含解析后校验）、
`prompts/task-intent-prompt.md`、`POST /api/chat/intent` 端点、`taskIntentPrompt` Bean —— **全部已删除**。

**删除理由（三条独立否决）**：
1. **产出没有任何消费者** —— `ChatAppService` 完全不读它，"等人用"的产出就是死代码
2. **与模型自身的工具调用重复同一个判断** —— 给模型看"交付物枚举"问该产什么，
   又给模型看"工具 JSON Schema"问该调哪个工具，同一判断做两遍
3. **引入了第二对手工同步的字典** —— 工具改名时意图枚举不会跟着变

**⚠️ 附带的两个红线**：
- **不要再做 `ToolCapabilityAdvisor`**（原计划的"工具清单与 prompt 一致性校验切面"）—— 它服务的是 intent 层
- **"不可逆操作需要确认"要做在工具执行层**（代码级强制），**不靠模型自述"我需不需要确认"**

**保留但已降级为历史的**：阶段 3 的"两条结构化输出通道"（提示词通道/原生通道）与
"双字典只有两处需手工同步"是**关于框架的事实**，阶段 7/9 重新用结构化输出时直接复用机制。

---

## 六、环境与常用命令

### 6.1 基础设施（Docker 容器）

| 容器名 | 镜像 | 端口 | 用途 |
|---|---|---|---|
| `pgvector` | `pgvector/pgvector:pg16` | 5432 | 向量库（库名 `hagent`，扩展 `vector 0.8.6`，UTF8） |
| `hagent-mysql` | `mysql:8.0.39` | **3307** | 业务主库（root/`hagent123`，库 `hagent`） |
| `hagent-redis` | `valkey/valkey:8` | 6379 | 缓存（与 Redis 协议完全兼容） |

**⚠️ MySQL 客户端字符集**：`character_set_client = latin1`，用 `mysql -e` 查中文会显示乱码
（存储是 utf8mb4，数据没问题）。**命令行查询要加 `--default-character-set=utf8mb4`**。

**★★ 跑测试 / 启动应用前，先确认三个容器都在跑**：

```powershell
. "$h\tools\sql.ps1"
查依赖          # 三个容器逐个报 ✅/❌，缺了就打印该执行的 docker start
```

- **宿主机的休眠或重启会把它们杀掉**（实测发生过两次，其中一次导致十几个 `@SpringBootTest`
  类莫名全红，纯单元测试却全绿 —— 那种"红得没有规律"的表现最容易让人往代码上找原因）。
- ★ **判断依据只看"此刻在不在跑"，不看 Docker 报的退出时间** ——
  实测见过"刚跑通的容器，报 23 小时前退出"这种明显不准的时间戳。
- MySQL 起来后还要等它 ready：容器日志里出现 `ready for connections` 才算（约 10~30 秒）。

**Ollama**：`http://localhost:11434`，模型 `mxbai-embed-large:latest`。

### 6.2 常用命令

```powershell
$h = "C:\Users\Rong\Desktop\实习准备\Hagent"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
Set-Location $h

# 全量测试（369 个用例；★ 需要三个容器 + Ollama 都在跑，否则集成测试会整类报错）
# ⚠️ 改了签名/删了类时，本地验证也必须带 clean —— 不带 clean 的 test-compile 会【假绿】（坑 43）
.\mvnw.cmd -B -ntp clean test

# 启动（需要 DEEPSEEK_API_KEY；IDEA 运行配置里已配好）
$env:DEEPSEEK_API_KEY = "<key>"
.\mvnw.cmd spring-boot:run          # 端口 8123，context-path /api

# 前端（阶段 10 新增；独立进程，端口 5173，strictPort 被占用时【直接失败】而不是悄悄换端口）
Set-Location $h\frontend
npm install                         # 只需一次
npm run dev                         # 打开 http://localhost:5173
npm run build                       # 产物在 frontend/dist（仅用于验证能编译）

# 端口占用时清理（mvnw spring-boot:run 会 fork 子进程，杀 wrapper 不够）
Get-NetTCPConnection -LocalPort 8123 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Get-NetTCPConnection -LocalPort 5173 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# ★ 查库一律用这个工具，不要手写 docker exec（原因见 6.3 第 3 条）
. "$h\tools\sql.ps1"
查依赖                                                     # 三个容器在不在
查MySQL "SHOW TABLES"                                      # 业务主库（chat_message 在这里）
查MySQL "SELECT id, role, LEFT(content,20) FROM chat_message ORDER BY id DESC LIMIT 5"
查PG    "SELECT metadata->>'source', COUNT(*) FROM hagent_knowledge GROUP BY 1"   # 向量库

# ★ 阶段 14：观测四个问题，四条 SQL 就够（表结构见 src/main/resources/db/chat_turn.sql）
查MySQL "SELECT status, COUNT(*) FROM chat_turn GROUP BY status"                  # 成功率（分母=全部轮次）
查MySQL "SELECT turn_id, status, elapsed_ms, first_token_ms, model_calls, tool_calls, tool_ms, total_tokens, usage_missing_calls FROM chat_turn ORDER BY created_at DESC LIMIT 10"
查MySQL "SELECT ROUND(AVG(elapsed_ms)) avg_ms, COUNT(*) n FROM chat_turn WHERE status='SUCCESS'"
查MySQL "SELECT t.turn_id, t.tool_calls, COUNT(i.id) audit_rows FROM chat_turn t LEFT JOIN chat_tool_invocation i ON i.turn_id = t.turn_id GROUP BY t.turn_id HAVING audit_rows <> t.tool_calls"
#   ↑ 最后一条是【对账】：结果集非空 = 有工具调用没写进审计（平时看不出来）

# 测试工具（UTF-8 安全；注意开头的「点 + 空格」）
. "$h\tools\hagent-test.ps1"
问 -Message "我叫张三"                 # 发一次对话
探测 -Path '/nope'                    # 测 404/405/415（不需要 Key）

# ===== 阶段 16：MCP（★ 顺序不能反：先构建 server，再起主应用）=====
.\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml package    # 构建时间 server（独立项目）
node tools\mcp-probe.mjs                                  # ★ 直接说 JSON-RPC 验证：握手/工具列表/调用/stdout 纯净
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,mcp"   # 主应用带 MCP 启动
#   ↑ 启动日志里应看到：[工具] 已注册 5 个工具（本地 4 + MCP 1）… mcp_hagent_time_getCurrentTime
#   ↑ 没构建 server 时：3 秒内失败，并直接告诉你缺哪个 jar、执行哪条构建命令
#   ↑ 忘了带 mcp profile 时：应用照常启动，但会打一条 WARN 说"外部工具不可用"
#   ⚠️ IDEA 打开 MCP/*.java 报 "outside of the module source root"：右键 pom.xml →
#      Add as Maven Project（每个 clone 一次；.idea/ 不进仓库）。命令行构建不受影响。
#   ⚠️ 不需要第二个 IDEA 窗口 / 第二个 run 配置：stdio 下 server 由主应用 fork 成子进程
#      （日志以 "STDERR Message received" 出现在主应用控制台）。
#      但**改了 server 代码必须先重新 package**，否则客户端跑的是旧 jar（症状：改了代码但行为没变）。

# ★ 汇报第一段【本次改动文件】用它生成，不要靠回忆（协议第 2 条 + 第 6 条）
. "$h\tools\change-list.ps1"
改动清单                              # 刚提交的这一阶段
改动清单 -Commit dc926c0              # 指定提交
# 工作区有未提交改动时它会先警告 —— 那种情况下清单必然漏文件，别直接拿去汇报
```

### 6.3 手工测试的三个已知坑

1. **PowerShell 5.1 的 `Invoke-WebRequest -Body '<字符串>'` 会把中文变成 `????`**
   （不带 charset 时用 ISO-8859-1 编码，逐字符替换成 `?`，ASCII 63）
   → **必须用 `tools/hagent-test.ps1`**，它显式传 UTF-8 字节
2. **控制台是 936 代码页，中文显示乱码是「显示」问题**，不影响实际发送。
   要确认真实发送内容，看服务端日志的 `[PromptTraceAdvisor] → 请求 | 内容=...`
3. ★★ **`docker exec ... sh -c 'mysql ... -e "SELECT 1"'` 里的双引号会被 PowerShell 吃掉**
   → 容器收到空语句 → `ERROR 1064 (42000) ... near '' at line 1`

   这个报错**极具误导性**：读起来像"SQL 写错了"或"MySQL 没起来"，
   而真实原因是**参数根本没传到**。本项目**因此误判过两次**：
   - 第一次：以为命令写错，反复调引号；
   - 第二次：拿它做"容器是否就绪"的探针，得出"MySQL 60 秒内未就绪"的**错误结论** ——
     而容器日志里明明写着 `ready for connections`。

   ⇒ **已机制化（协议第 6 条）**：收口到 `tools/sql.ps1` 的 `查MySQL` / `查PG` / `查依赖`。
   它们用"写无 BOM 的 .sql → `docker cp` → 容器内重定向"这条路径，
   **PowerShell 完全不参与文本编码，也没有引号会被吞**。

### 6.4 Git

Git 在 `D:\Git\cmd\git.exe`；Maven 本地仓库 `D:\devZgq\Java\.m2\repository`（阿里云镜像）；
**Docker Hub 不可达**（只能复用本地已有镜像）。
`.gitignore` 已覆盖：`target/`、`tmp/`、`application-local.yml`、`.idea/`。

---

## 七、收尾归档：已完成 / 已移出 / 验证状态

> 项目在阶段 17 结束。本节取代过去按优先级排列的"待办清单"——
> 现在没有待办，只有"已经做完的"和"明确不做的"两类。详细理由见 `PROCEDURES.md`《最终范围与移出项》。

### 7.1 最终验证状态（可复现）

```text
.\mvnw.cmd -B -ntp clean test                       → Tests run: 369, Failures: 0, Errors: 0, Skipped: 2
.\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml test  → Tests run: 3,   Failures: 0, Errors: 0
cd frontend; npm run build                          → 构建通过
```

- 2 条 skipped 是需要环境开关（`LIVE_PROBE_FLAG`）的真实探针，不是失败。
- 真实 Key 的端到端验证由本人逐阶段确认：对话 / RAG+密级 / 工具 / 长期记忆 / 工作流 / 回合账 / MCP 工具调用均有效。
- `MCP/time-server` 的端到端证据：审计表出现 `tool_name = mcp_hagent_time_getCurrentTime`（见 `PROCEDURES` §16.6）。

### 7.2 各事项的最终结论

| 事项 | 最终结论 |
|---|---|
| 阶段 0–14 | ✅ 全部完成；关键认知见 `PROCEDURES` 各节的"踩坑"与"阶段结果" |
| 阶段 15（新工具 / 工具分组） | ❌ 移出范围：唯一需要权限的工具已在 SQL 层过滤；`getCurrentTime` 由 MCP 交付 |
| 阶段 16（MCP） | ✅ 完成：客户端 + 独立时间 server + 模型真实调用 |
| 阶段 17（工程收尾） | ✅ 完成：注释精简 / 文档全量核对 / README / 失效页删除 |
| 阶段 14 欠的"真 Key workflow 取证" | ✅ 已由本人验证有效 |
| 阶段 13 欠的"前端计划面板" | ❌ 最终不做（纯前端呈现，不改后端能力） |
| 阶段 12 的命名尾巴 | ❌ 最终不做（纯重构 / 契约变更，收尾期风险 > 收益） |
| 鉴权缺口 | ❌ 移出范围（决策 71：只预留身份传递链路） |

### 7.3 明确不做（一句话版；完整表见 PROCEDURES）

- 鉴权 / 账号体系；限流与 token 配额；部署说明 / docker-compose；演示脚本
- 阶段 15 工具分组、Excel MCP server、`listKnowledgeSources`、`queryChatHistory`
- 知识入库的运行时入口（保持"测试调用 + 显式 DDL"）
- 前端工作流计划面板；死配置键处置；两条机制化（API key / 异常兜底）
- 死依赖清理 —— **刻意保留**：POI / MyBatis-Plus / Markdown & PDF reader 是扩展铺垫，
  已在 `pom.xml` 注释写明各自落点
- 阶段 12 尾巴（`IDENTIFIER` 两份 / `SafePaths` / `SessionDtos` 改名 / HTTP 路径 / 配置前缀 / `ignoreUnknownFields`）
- Micrometer / Actuator / 单工具耗时

### 7.4 已知限制（诚实清单）

- **身份自报**：`X-Hagent-Token: <userId>:<ROLE>` 明文可伪造，检索越权防护只证明链路正确。
- **删会话不删痕迹**：审计 `arguments`/`result` 含用户原话（已拍板接受）。
- **进程被 kill 的轮次不留账**（一次性 INSERT 的代价，换零 UPDATE / 零僵尸行）。
- **`chat_message.turn_id` 之前的历史没有回合数字**（NULL，不伪造）。
- **单工具耗时**拿不到（框架 `executeToolCalls` 整批执行）。
- **embedding 中文区分度弱**：阈值只能粗筛，兜底靠提示词；换模型必须重新校准。
- **知识入库无运行时入口**；PDF / DOCX 不支持（`SourceLoader` 只认 `.md` / `.txt`）。
- **单实例**：无 Micrometer / Actuator；多实例与限流不在范围。

### 7.5 没有"下一步"

项目按计划在此结束：**不存在待办列表**。若将来继续，边界与扩展落点已在
`PROCEDURES`《最终范围与移出项》与各阶段的"诚实清单"里，那是新项目的事，不是本项目的遗留任务。

---

## 八、目录结构（最终）

> 与 `PROCEDURES.md` 的目录树一致；以实际文件为准。
> 只到"包 → 关键类"这一层；每个包的设计理由见对应类的类注释与 PROCEDURES 各阶段记录。

```text
Hagent/
├── README.md                       ← 项目入口：能力 / 架构 / 快速开始 / 边界
├── docs/                           ← WORKING-AGREEMENT / PROCEDURES / HANDOFF /
│                                     KNOWLEDGE-SAMPLE-DATA / 架构问题
├── knowledge/                      ← 4 份示例知识文档（虚构数据，进版本库）
├── frontend/                       ← Vue3 + Vite（:5173）
├── tools/                          ← sql.ps1 / hagent-test.ps1 / change-list.ps1 /
│                                     rename-check.ps1 / mcp-probe.mjs
├── MCP/time-server/                ← 独立 MCP server（stdio；独立 pom / 独立进程）
└── src/
    ├── main/java/com/hagent/
    │   ├── HagentApplication.java
    │   ├── agent/        PipelineEvent（sealed 契约）/ ToolEventSink
    │   ├── app/          AgentPipeline（唯一编排者）/ PromptBuilder / ChatAppService /
    │   │                 ConversationService / HealthAppService / DocumentIngestService
    │   ├── config/       全部配置与装配（*Properties + *Config + ToolRegistration + 启动期校验器）
    │   ├── controller/   ChatController / ConversationController / HealthController
    │   ├── common/       BaseResponse / ErrorCode / 全局响应包装 / AtomicWrites / Fingerprints / Texts
    │   ├── dto/          ChatRequest / ChatResponse / SessionDtos
    │   ├── document/     入库领域模型 + chunking/（三策略 + 调度）
    │   ├── exception/    AgentException / GlobalExceptionHandler / FailureMapper
    │   ├── memory/       门面 + record / context / catalog / longterm + 共用工具
    │   ├── observability/ StartupProbe + 回合账（Turn*）
    │   ├── prompt/       PromptFragments / WorkflowPlanPrompt
    │   ├── retrieval/    KnowledgeSearch / RetrievedChunk / RelevanceBand / RetrievalFilterBuilder
    │   ├── security/     Role / UserContext / UserTokenResolver / UserContextInterceptor
    │   ├── stream/       StreamEventType / SseSupport
    │   ├── tool/         KnowledgeRetrievalTool / LongTermMemoryTools / ToolContextSupport / audit/
    │   └── workflow/     WorkflowPlan / PlanStep / WorkflowPlanParser / InvalidPlanException
    ├── main/resources/   application.yml / application-local.yml.example / application-mcp.yml /
    │                     db/*.sql（显式 DDL + migrations/）/ prompts/*.md / META-INF/spring.factories
    └── test/java/com/hagent/   50 个测试类（369 用例）+ 抽象契约类 + 测试辅助
```

---

## 九、踩坑速查（每条都可迁移）

> 本表是**历史索引**（按阶段分组）：每条结论都可迁移；具体代码形态以 `PROCEDURES.md` 为准。

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 1 | 配了 `initialize-schema: false` → 表从未被创建 | 框架两个开关都为假时**既不建表也不校验**，直接 return | **配置是否生效，要看有没有代码走那条路**；阶段 0 的探测是只读的，所以整条写入路径的配置从未被验证 |
| 2 | `validFrom: 2024-01-01` 解析失败，报 `Mon Jan 01 ...` | **YAML 1.1 里这是时间戳字面量**，被转成 `java.util.Date` | **引号不是装饰，是类型声明** |
| 3 | 降级链先试 `BY_HEADING` 再试 `BY_PARAGRAPH` | 误把"结构化程度"当降级依据 | **平行策略之间不存在"降级"关系**；降级对象是**切分粒度** |
| 4 | ★ 块大小**系统性突破上限**（60 字的块产出 95 字） | **大小检查在追加重叠之前**，而 `tailOverlap` 返值可能远大于 `overlap` | **"先追加、后检查"是危险模式**；真实配置恰好避开缺陷 → 手写测试给了**虚假的安全感**；核心不变量要写**穷举测试** |
| 5 | 集成测试单独跑过、全量跑失败 4 个 | 依赖"上一次运行留下的数据库状态"；加自动清库后又打破了"依赖前序用例"的断言 | **两次失败同一根因：测试方法之间有隐式依赖**；**能独立运行的测试才是可靠防线** |
| 6 | 人工"跑测试前先 drop 表"的步骤会忘 | — | **凡是"必须做但容易忘"的步骤，都要做成自动的**（与协议第 6 条同源） |
| 7 | `EnvironmentPostProcessor` 用 `HIGHEST_PRECEDENCE` 读不到配置 | **加载 YAML 本身也是由一个 `EnvironmentPostProcessor` 完成的** | 扩展点的执行时机由"谁先产出我依赖的数据"决定 |
| 8 | 把校验做成 Bean，永远轮不到它执行 | Spring 解析构造器参数时**第一个缺依赖就直接抛错返回**，不会继续创建后面的参数 | 用"依赖关系"保证执行顺序，前提是那个依赖真能被解析到 |
| 9 | `SseEmitter.send(ServerSentEvent)` 一个事件都发不出去 | `SseEmitter` 字节码里**根本没有 `ServerSentEvent` 的引用** | **同一类型名在不同执行路径上含义不同**；判断能否这样用要看真实签名 |
| 10 | 协议级异常（404/405/415）被报成 500 | 这些异常**没有显式处理器**，落进 `Exception.class` 兜底 | **补异常分支要按"一类"补**；**兜底处理器的正确写法是"报出缺口"**；405 必须回 `Allow` 头 |
| 11 | 上游 401 被报成 500 | 真实抛的是 `NonTransientAiException`（框架重试层包装），而它**单参数构造器丢弃了 cause** | 状态码只存在于消息文本里；**单测全绿 ≠ 功能可用**（第一版端到端实测失败） |
| 12 | 一个字段被赋值但无人读取（死代码） | 注释在为一个不存在的实现作保 | **"写了"不等于"生效"**；唯一发现方式是**逐个字段问"谁读它"** |
| 13 | `ChatMemoryAdapter` 用"取最大预算"回避歧义 | 用数量级掩盖歧义 | **"取一个足够大的值"不是消除歧义，是隐藏歧义** |
| 14 | PowerShell 传中文 body 变成 `????` | PS 5.1 不带 charset 时用 ISO-8859-1 编码 | 已机制化：`tools/hagent-test.ps1`；**症状极具误导性**（服务端全绿、只有内容是错的） |
| 15 | 文档小节编号手工改必错（已 2 次） | 编号是全局有序属性；正文交叉引用不会跟着变 | **脚本化重排 + 双校验**（协议第 8 条） |

### 阶段 6 后半新增（全部有实测/字节码证据）

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 16 | ★★★ **检索永远返回空、不报错** | `FilterExpressionBuilder.in` 的 List 重载参数是 **`List<Object>` 而非 `List<?>`**；`List<String>` 因**泛型不变**匹配不上 → **静默回退**到 `in(String, Object...)`，把整个 List 当成一个值 → 渲染成 `$."level" == ["PUBLIC","INTERNAL"]`（字符串比数组字面量，**恒为 false**） | **重载 + 泛型不变 = 静默降级。** 传集合给重载方法时必须确认命中了哪个重载；本项目用 `list.toArray()` 显式走变长参数。**"过滤写错"与"库里没数据"在结果上完全一样** → 必须有**正向断言**（"必须查到"）作对照 |
| 17 | ★★ 注释写着"日志能看到注入后的 prompt"，实际看不到 | 切面链**单向转发**：`nextCall` pop 出下一环后把 request 的新版本往下交，**内层修改不回流** | **外层切面记的是"注入前"**。要记"模型真正收到的"必须放**最内层**。两个各自"看起来正常"的日志，会掩盖一个方向错误 |
| 18 | ★★ 文档里 `ToolCallingAdvisor` order 写成 `MIN+200` | 抄写数字而不是引用框架常量 | 实为 **`MIN+300`**（`javap -constants`）。差 100 不是小事：`MIN+201…299` 是**可用的"循环之外"空档**。**引用常量 + 测试对着常量断言** |
| 19 | ★★ 一个切面同时做"读历史"与"写回"，两条位置要求相反 | 读要在拼装之前，写要在整条链收尾 | **判据：需不需要"请求级业务数据"**。需要 → 属"这一次请求的编排"；不需要 → 属"每次调用的横切"。**一个切面只有一个位置** |
| 20 | ★ 分档在边界那一档偶尔判错 | `1.0 - 0.70 = 0.30000000000000004`（浮点不精确）→ `<= 0.30` 为假 | 边界比较必须带容差。**真实检索永远不会恰好落在边界上 → 手工测试一辈子碰不到它**，只有单元测试能抓 |
| 21 | ★ `clean` 失败："另一个进程正在使用此文件" | 把构建日志重定向进了 `target/`，而 `clean` 要删 `target/` | **工具的产物不要和工具要清理的目录放一起**。已改到 `tmp/` |
| 22 | ★ 框架会探测"记忆切面是否在工具循环内层" | `autoRegisterToolCallingAdvisor` 里 `instanceof MemoryAdvisor && order > 工具循环order` → 决定自己的 `conversationHistoryEnabled` | **实现一个"看起来更专业"的标记接口，可能是在把控制权交出去**。本项目刻意不实现 `MemoryAdvisor` |
| 23 | ★★★ **非法 token 返回 500，而 177 个测试全绿** | 抛的是 `IllegalArgumentException`，而 `GlobalExceptionHandler` **没有**它的处理器 → 落进 `Exception.class` 兜底 | **"单测全绿 ≠ 功能可用"是一个可定位的结构缺口**：A 类负责抛、B 类负责接时，测 A 与测 B 的用例都过，**而 A→B 那条边没人测**。补 `ChatEndpointIdentityTest`（真实 HTTP 往返）把两端放进同一断言。★ 这是踩坑 #10 的**同一类问题换了个异常类型重演** |
| 24 | ★ `@AutoConfigureMockMvc` / `TestRestTemplate` 编译不过 | Spring Boot 4 把它们挪到了 `spring-boot-webmvc-test` / `spring-boot-resttestclient`（本项目未依赖） | 别为一条断言加依赖 —— **用 JDK 的 `HttpClient` 发真实请求**，证据还更强（`context-path` 真的生效） |
| 25 | ★★ **新类放错了包**（`Service` 放进领域包、Properties 没进 `config`、常量类单独占包） | 本项目**同时有两套组织规则**（按技术角色 / 按能力域），而我按"它讲哪个业务"放了，没按"它是什么种类"放 | **类的种类有既定归属时跟种类走，没有时才跟能力域走。** 核实手段：`Get-ChildItem -Recurse -Filter '*Service.java'` 列出来看（实测：`*Service` 3/4 在 `app/`、`*Properties` 7/10 在 `config/`）。★ 另一信号：**一个能力被劈成两个包**（做事的三切面在 `advisor/`、它们约定的 key 在 `prompt/`）——说明放错了。判据：**谁用它，它就跟谁走** |
| 26 | ★★★ **差点删掉一个"零调用方"的 Bean**（`ChatMemoryAdapter`），而它其实在挡着框架的默认实现 | 文档把它写成"仅作为 **Bean 预留**（将来挂框架自带切面时才用）"—— "预留"读起来就是"备而不用"，于是"零调用方"被当成"可以删"。实测框架 `ChatMemoryAutoConfiguration` 两个 `@Bean` 都是 `@ConditionalOnMissingBean` → 没我们的就装它自己的（**纯内存 + 无预算**） | **判断零调用方的类能不能删，不能只看"谁调它"，还要看"没有它会发生什么"。** ★ **"预留"和"占位"是两件事**：预留是"将来才有用"，占位是"**现在就在起作用**"——而占位最容易因为"没人调用"被误删。核实手段：对有疑问的 Bean，查框架自动配置是否 `@ConditionalOnMissingBean` |
| 27 | ★ **一个测试"跑过"不等于它是有效防线** | `MemoryBudgetsTest` 的回归断言第一次就通过 —— 但"通过"只说明**当前代码是对的**，说明不了"**错了会被抓住**" | **对关键回归断言，要临时植入缺陷验证它真的失败**，再恢复。本轮实测：把主链路的预算改歪 → 该断言立刻失败 ✅。这与"结论必须有证据"（协议第 3 条）同源 —— **测试本身也是一个需要被验证的断言** |
| 28 | ★★★ **业务主库的数据源从来就不存在** —— 而"本项目的全局 DataSource 是 MySQL"这句写进了三处注释 | 声明 `pgVectorDataSource` 让 `DataSourceAutoConfiguration`（带 `@ConditionalOnMissingBean(DataSource.class)`）**退避** → `spring.datasource.*` 整段被静默忽略。★ 长期没暴露，是因为**阶段 7 之前没有任何代码使用业务主库** | **当应用里出现第二个同类 Bean 时，"默认的那个"会消失，而且不报错。** 核实手段：条件评估报告会直接写"匹配/未匹配 + 原因"，比读代码猜快得多。**"某段配置一直在生效"是一个需要证据的断言** |
| 29 | ★★★ **`@Primary` 压过"按参数名匹配"** | 给业务主库加 `@Primary` 后，`pgVectorJdbcTemplate(DataSource pgVectorDataSource)` 按类型解析拿到了 **MySQL**（primary 优先），于是 `PgVectorStore` 对着 MySQL 执行 `CREATE EXTENSION IF NOT EXISTS vector`。★ 报错读起来像"PostgreSQL 版本太老不支持 EXTENSION" | **`@Primary` 的优先级高于参数名匹配**；参数名只在"没有 primary"时用来打破平局。**有歧义的地方按 `@Qualifier` 取，让"用哪个"在代码上看得见** —— 猜错的注入与猜对的注入在代码上一模一样，只有运行时才分得开 |
| 30 | ★★★ **为一个没人消费的属性，付了锁与死锁的代价** | 表上多一列 `seq`（无洞的会话内序号）+"读 MAX + 唯一键 + 撞车重试"。实测：普通读（REPEATABLE READ 下是*一致性读、不加锁*）+ 重试 → **重试被耗尽**；改锁定读 `FOR UPDATE` → 空区间间隙锁 + INSERT 插入意向锁 → **死锁**。而 `seq` 提供的属性（"会话内第 N 条"）**没有任何消费者** | **引入一个"更规整"的属性之前，先问谁消费它。** 若答案是"排序能用上"，通常已经有更好的东西在提供它（这里是自增 `id`）。**"无洞"听着是质量，成本是锁、死锁与丢数据风险，收益是零** |
| 31 | ★★ **并发用例是"设计缺陷探测器"，不是"稳定性负担"** | 并发用例第一次跑就失败（2 线程 × 3 次就把重试耗尽）。★ 当时最容易做错的反应是"把并发度调低"或"删掉这条 flaky 用例" | **一个刚写出来就失败的测试，先假设"它是对的、代码是错的"**，尤其并发用例。处置是**升到 4 线程 × 5 次加压**，然后去修设计。它失败时说的话，往往比它通过时更有价值 |

### 阶段 10 新增（对应 `PROCEDURES.md` 的坑 38/39/40）

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 32 | ★★★ **回答的 markdown 排版偶发错乱**（段落空行消失、后段变正文以外的东西） | **SSE 的 `data:` 行放不下裸换行**：`SseEmitter$SseEventBuilderImpl.data(...)` 只 `append("data:") + append(数据)`，**不转义**（`javap -c` 可证）。于是 token 里的 `\n` 直接切断了帧，而 `\n\n` 的第二个换行被当成帧分隔符**吃掉** | **自由文本不能直接塞进协议字段**。判据：先问"这文本能含换行吗"，能就必须编码（本项目用 JSON）。★ **不要指望对端把续行拼回去 —— 那是"有损"的**：被协议吃掉的空行拼不回来 |
| 33 | ★★ **流式过程中界面完全不动**，流结束才一次性出现整段回答 | 裸对象 push 进 `reactive` 数组后，手上的引用仍是**原始对象**；改它绕过 Proxy，**不触发更新** | **"数据在响应式容器里"≠"我手里的引用是响应式的"**。凡"创建对象 → 放进容器 → 继续用旧引用改它"的写法都要检查。（已用 `reactive()` 修） |
| 34 | ★ **改名成同一个标题时前端收到 404** | MySQL `UPDATE` 在"新旧值相同"时 `affected rows = 0` —— 与"记录不存在"**同形** | **用 `affected rows` 判断"记录是否存在"之前，先问"有没有可能值没变"**。这不是某个数据库的怪癖（PG/SQL Server 也有同类语义），所以要修的是**判断依据**，不是记住一句注释。★ 与坑 #28 同源：**两种不同原因产生同一种结果时，必须再加一次判别** |

### 阶段 10 联调新增（真机才暴露，mock 永远测不到）

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 35 | ★★★ **问年假/差旅，检索一律返回"未找到"，而库里就有** | 检索跑在 **Reactor 的 Netty 事件循环**（`reactor-http-nio-*`）上；工具里的阻塞调用（Ollama embedding 的 RestClient 底要 `Mono.block()`）被 Reactor 拒绝 → 异常被吞 → 空列表 → "没命中" | **整条管道允许阻塞，所以整条管道都不该在事件循环上跑**（`publishOn(boundedElastic)`）。★ 加的位置很关键：`publishOn` 只影响**下游**，必须加在 `concatWith(handleToolCalls)` **之前**；加在外层仍然红（实测过）。★ 同根因还压着 `writeBack`/`recordAudit`：JDBC 不报错，只**静默堵事件循环** —— 更隐蔽 |
| 40 | ★★ **新写的两个配置类又放错了包**（`config/` 里已有 7 个 `*Properties`、4 个 `*Config`，我却把它们放进了 `memory.longterm`） | 判据又用了"它讲什么业务"（装配记忆 → 跟记忆走），而不是"它是什么种类"。**这是踩坑 #25 的同一种错，第 2 次** | 协议第 6 条：重复 ≥2 次**必须机制化**。已加 `PackagePlacementTest`，现在守 **5 条规则零例外**：`*Properties`/`@Configuration`→`config`、`*Controller`→`controller`、`*Service`→`app`、`*Tool(s)`→`tool`。★ 机制验证过"会红"：先后植入 4 个违规类（Properties/Config/Controller/Service/Tool），**每条规则都抓到**；删掉后绿。★ 植入时还发现守卫第一版漏了**全限定注解写法**（`@org.springframework...Configuration`），已修。★ 随后把 3 个历史例外（`MemoryProperties`/`MemoryConfig`/`ToolRegistration`）也一并归位 —— **零例外**比"白名单带理由"更可靠 |
| 36 | ★★★ **每次都被答"知识库中未找到相关内容"** | `retrieve` 失败 `return List.of()` → 与"没命中"**同形** → 工具回"没找到" → 模型照说。**系统在诚实地撒谎** | **降级要发生在"能说出原因"的那一层。** 在只看得到 `List` 的层里降级，原因永久丢失。修法：失败**抛**（`RetrievalUnavailableException`），由工具翻成"检索不可用（**不是**没有资料）"。★ 与坑 #16（过滤坏掉→返回空）、#34（affected rows）**是同一类** |
| 37 | ★ **282 个用例全绿，而真机每次都失败** | 假模型都是 `Flux.just(...)`：元素在**调用线程**（允许阻塞）上发出；真机元素来自 Netty 线程 | **bug 长在 mock 边界之外**。补的机制化用例：`publishOn(Schedulers.parallel())` 假装"元素来自非阻塞线程" + 假工具**在工具外**断言 `isInNonBlockingThread()==false`（★ 工具内抛断言会被框架翻成文本，测试**假绿**）。先红后绿都验证过 |

### 阶段 11 新增

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 38 | ★★★ **`mvnw test-compile` 报 BUILD SUCCESS，而 11 个测试根本编译不过** | 增量编译以**源文件时间戳**为判据：改了 `AgentPipeline.java`（加构造器参数），调用方（测试）自己的源文件没变 → 判"不必重编" → **依赖方的新签名没被检查**。加 `clean` 后立刻暴露 15 个错误 | **构建说成功 ≠ 代码能编译。** 判据：**凡"改签名/删类"的验证必须 `clean`**（全量命令本来就带，是我手工局部验证时偷懒）。这类"假绿"比红灯危险 —— 它会让人把错误结论继续往下推 |
| 39 | ★ **一个配置键从阶段 5 就写着，却没有任何 Java 字段绑定它**（`hagent.memory.long-term.dir`） | 全项目搜不到 `longTerm`；改了它以为记忆换目录了，实际什么都没发生 | **"配了没人读"要靠专门找**：新增/接手一个配置段时，`grep` 一下有没有字段绑定它（这是坑 #12 的第三个实例） |

#### 两条"假警报"（排错方向比结论更值得记）

| 现象 | 真相 | 判据 |
|---|---|---|
| 中文落库变成 `????`（`HEX` 是 `3F3F…`） | **发出去的那一侧**已经把中文替换掉了（`Invoke-WebRequest` 内联中文体）。按码点构造 + UTF-8 字节后，`HEX` 是 `E5B9B4E58187E694BFE7AD96`（正确的"年假政策"） | **中文落库异常时先看 `HEX()` 字节，再检查"发出去的那一侧"** —— 不要直接怀疑服务端编码（与坑 #14 是同一类，但这次是测试命令而非服务端） |
| `Get-Content` 看到 `鏉窞浠婂ぉ` | 控制台按 GBK 解码 UTF-8 文件，**文件本身是好的** | 读文件一律 `-Encoding utf8` 或用 `read` 工具；**"看起来乱码"不等于"文件坏了"** |

### 阶段 14 新增（对应 `PROCEDURES.md` 的坑 44–48）

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 44 | ★★★ **一次 7 次调用的工作流，只记了最后一次的 token（3419）** | `buildFinal` 的 usage 取 `state.lastChunk`（最后一片）；该累加的是"每次调用的收尾片" | **"最后一个"不等于"总共"**。凡"从流里取一个值当整轮结果"的地方，先问"这次之前的那几次去哪了" |
| 45 | ★★ **`EmptyUsage.getPromptTokens()` 返回 0**（javap 实证） | 框架用"零值对象"表达"没有数据" | **零值对象与真值同形**：直接累加会把"没数据"记成"0"。必须另记一个计数（本项目用派生值 `modelCalls - 有 usage 的次数`） |
| 46 | ★ 加了一个事件后，4 个用例 `ClassCastException`（`getLast()` 拿到 `TurnFinished`） | 断言写的是**位置**（"最后一个就是 Final"），位置会随"又加了一个事件"而变 | **对事件流的断言要按类型找，不要按位置取**。位置的语义是"当前实现恰好这样" |
| 47 | ★ 只在 `doOnComplete` 打点 → 失败与取消的轮次一条账都没有 | Reactor 三条终止通道（onComplete / onError / cancel）**只有 `doFinally` 都看得到** | **成功率的分母必须在三出口都能打点的位置算**；否则"失败"与"正常"在数据上同形（§十 第 4 条的又一实例） |
| 48 | ★★ **失败轮在 MySQL 里零痕迹**（不是"少统计"，是"根本不存在"） | `writeBack` 只在 `finish()` 里执行；异常/断线到不了那里 | **"有一张记录对话的表"不等于"记录下了每次服务"** —— 要问的是"没成功的那次记在哪" |
| 49 | ★★ **刷新页面后那一行统计消失了** | 数字只放在 SSE 事件里；事件是**一次性的**，页面重建时它已经过去了 | **凡是"用户要看到"的东西，都要问：刷新之后它从哪来？** 事件是通道，不是存储 —— 能重建的必须能重建 |

### 阶段 16 新增（对应 `PROCEDURES.md` 的坑 50–55）

| # | 现象 | 根因 | 认知 |
|---|---|---|---|
| 50 | ★★ 缺 server jar 时启动**等 20 秒**，报 `TimeoutException: Did not observe any item … in 'map'` | 真正的错误（`Error: Unable to access jarfile …`）在更早的一行 server stderr 里；`request-timeout` 把失败包装成了超时 | **超时是最没信息量的失败形式**：它只说"没等到"，不说"为什么"。前置检查要放在**能说清原因**的那一层 |
| 51 | ★★ 前置检查第一版**静默什么都没查** | 绑定到 `Object` 时，`Binder` 把 `args[0]/args[1]` 绑成 **`Map{"0":…,"1":…}`** 而不是 `List`；第一版只判了 `instanceof List` | **"检查写了"≠"检查跑了"**。凡"不满足就 fail"的守卫，必须有一条**会红的用例**（本次是它自己先红，才暴露类型假设错） |
| 52 | ★ `spring.factories` 多了一个反引号 → `ClassNotFoundException`，而**应用照常起来**（EPP 加载失败被当成警告） | 手工拼续行符写错；注册文件坏了不会让应用起不来 | **注册文件写错可能"看起来正常"**。改完必须**实跑一次"该失败时失败"**才算验证 |
| 53 | 日志写进 stdout 会污染 JSON-RPC 帧 → "进程活着但一个工具都看不到"（本阶段**预判并规避**） | stdio 用 stdout 传协议，而 Spring Boot 默认日志走 stdout | server 的 `logback-spring.xml` 把 appender target 改成 `System.err`；探针**断言 stdout 全是 JSON** |
| 54 | ★★ **不带 `mcp` profile 启动**：应用正常、4 个工具、回答流畅，但模型手里**没有**时间工具（实测：问"现在是什么时间"→ `工具=0 次`） | `application-mcp.yml` 只在 `mcp` profile 下加载；"少一个工具"不报错，只表现为"模型没这个能力" | **能力缺失与正常工作在观测上同形** → 装配点必须说出来（已加 WARN + 启动参数）。协议第 6 条：不要用"记得加 profile"解决第二次 |
| 55 | ★ IDEA 报 `Java file is located outside of the module source root` | 根 pom 把整个目录当 content root，而 `MCP/time-server` 是另一个 Maven 项目 | 在 IDEA 里对它的 `pom.xml` 执行 **Add as Maven Project**（每个 clone 一次）；命令行构建不受影响 |

---

## 十、项目完结后的接手与讲解建议

### 10.1 新对话的第一句话（让 AI 快速接手）

```text
读 docs/HANDOFF.md、docs/PROCEDURES.md、docs/WORKING-AGREEMENT.md、docs/架构问题.md（含顶部复核结论）。

状态：项目已完结（阶段 0–17）。369 用例 + MCP 3 条全绿；真实 Key 端到端由本人验证通过。
最终范围与明确不做项见 PROCEDURES 的《最终范围与移出项》。
不要再把"限流 / 部署 / 演示脚本 / 入库入口 / 前端计划面板 / 鉴权"当成待办 —— 它们已明确不做。
```

### 10.2 简历 / 面试讲解顺序（一页纸）

1. **一句话**：企业办公 Agent，Spring AI 直连 `ChatModel`，自己实现编排（`AgentPipeline`），
   把 RAG、记忆、工具、工作流、可观测性都做成可解释的模块。
2. **架构图**：`controller → AgentPipeline → 工具 / 检索 / 记忆 → 四个存储`（见 PROCEDURES）。
3. **三个最值得讲的决策**（每个都能说出"不做会怎样"）：
   - RAG **工具化**：由模型决定何时检索，而不是每轮必跑；
   - 记忆**两个角色**：MySQL 记录层（权威）+ Redis 上下文层（可重建），不是一个东西两种存法；
   - 回合账**一轮一行 + 三出口**：成功率的分母必须包含失败与取消。
4. **两个可迁移的踩坑**：`then()` 丢弃上游事件（坑 35）；
   阻塞调用跑在 Reactor 事件循环上（坑 41，真机才暴露）。
5. **边界**：身份自报、删除不删痕迹、单实例 —— 主动说清，比被追问好。

### 10.3 最容易被问到的 5 个问题

| 问题 | 回答要点 |
|---|---|
| 为什么不用 `ChatClient` / advisor 链？ | 需要自己掌控工具循环与事件流；`AgentPipeline` 顺序即代码行序，比 order 常量可读 |
| 为什么 RAG 由模型决定？ | 无关问题不该白烧 embedding；检索是可插拔工具，`retrieval.enabled=false` 即整体拔掉 |
| 双层记忆怎么保证一致？ | 写路径固定"先记录层、后上下文层"（上下文永不比记录新）；读失败降级为空历史 |
| 工作流和 chat 的区别？ | 唯一分叉点在 `run()`：多一次规划调用 + 逐步执行 + 重试/重规划；共用同一个 `loop()` |
| 回合账为什么不用 Micrometer？ | 消费者不同：要回答"这一轮为什么慢 / 花了多少"，SQL 直查比聚合指标更直接 |

### 10.4 记住六件最容易被违反的事

1. **未获明确放行不写代码**（协议第 1 条）。
2. **每个阶段开头要有数据流向图 + 思维导图**，并同步进 `PROCEDURES.md`（协议第 2 条）。
3. **结论必须有证据** —— 日志 / 字节码 / 原始字节 / 真实 HTTP，不许说"应该是这样"（协议第 3 条）。
4. **"失败"与"正常"同形的地方，必须有正向断言**（空列表、affected rows=0、零值对象）。
5. **A 类负责抛、B 类负责接时，测 A 与测 B 都过而 A→B 那条边没人测** —— 要专门问"有没有只被两端各自覆盖的边"；
   mock 边界之外（真机线程 / 真容器 / 真 HTTP）的行为必须另外覆盖。
6. **汇报第一段必须是【本次改动文件】的完整清单**（用 `tools/change-list.ps1` 从 git 生成，不靠回忆）。

### 10.5 交接检查表（约 30 分钟跑起来）

1. 三个容器 + Ollama 在跑：`. "$h\tools\sql.ps1"; 查依赖`
2. 设 `DEEPSEEK_API_KEY`（测试用占位值即可）
3. `.\mvnw.cmd -B -ntp clean test` → 369 / 2 skipped
4. `.\mvnw.cmd -f MCP/time-server/pom.xml package` + `node tools\mcp-probe.mjs`
5. 起应用（profile `local` 或 `local,mcp`）+ 起前端（`npm run dev`）
6. 按 `README.md` 的快速开始走一遍
