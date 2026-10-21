# Hagent 项目交接文档

> **用途**：新开对话时先读本文件，即可在**不重读历史对话**的前提下继续推进。
> 本文件是"当前状态 + 已定决策 + 全部待办 + 踩坑红线"的**唯一权威快照**。
>
> **最后更新（2026-10-21）**：**阶段 13 已完成**（工作流真实编排：先拆解 → 逐步执行 →
> 两次重试后放弃），`346 用例全绿`。阶段 12 的成绩保留在下方：包级环 **8 → 0**、
> 双守卫（ArchUnit 裸 `beFreeOfCycles()` + `PackagePlacementTest`）、`archunit_store/` 已删除。
> 详细记录见 `docs/PROCEDURES.md` §12 与 §13。

---

## 一、这个项目是什么

为大模型应用岗位实习准备的简历项目：**企业办公 Agent**，主张"高扩展性 + 高度工程化、模块化"。

**要求的功能**：会话/工作流模式、统一异常处理、SpringAI 框架、RAG 企业知识库、
基于 MySQL/Redis/`.md` 的存储与记忆系统、tool call 工具调用。

**当前阶段号与进度**（章节号与阶段号对齐，`docs/PROCEDURES.md` 为准）：

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 初始化项目（依赖/配置分层/启动探针/基础设施） | ✅ 已提交 |
| 1 | 接入模型 + 最小对话（同步链路）+ 日志切面 | ✅ 已提交 |
| 2 | 流式对话（SSE 三种实现）+ 放行判定 | ✅ 已提交 |
| 3 | 结构化输出 —— **该层的"任务意图理解"已整体撤回** | ✅ 已提交（历史记录保留） |
| 4 | （有意跳过：手册里的阶段 4 已在阶段 1 用 `PromptTraceAdvisor` 完成） | — |
| 5 | 会话记忆（memory 抽象 + 切面）+ 上游失败分类 + 撤回意图层 | ✅ 已提交（`6e5600e`） |
| **6** | **RAG 企业知识库**（入库链路 + 检索链路 + 权限过滤 + 切面拆分） | ✅ 已提交（`0338493`） |
| **7** | **会话记忆双层重构**（MySQL 记录层 + Redis 上下文层）<br>★ 顺带修掉两个潜伏的数据源装配缺陷 | ✅ 已提交（`468413e`） |
| **8** | **结构重构：advisor 链 → `AgentPipeline`**（直连 `ChatModel`，工具调用顺延阶段 9） | ✅ 已完成（当时 234 用例；见第 8 节） |
| **9** | **工具调用与多步 Agent**（工具循环 + `ToolCall`/`ToolResult` 事件 + 审计表） | ✅ 已完成（当时 251 用例；见第 9 节） |
| **10** | **前端页面 + 会话目录 + 思考落库**（Vue3 前端 / 3 个会话接口 / `@@step:` 落进 `reasoning` / 真机联调修掉 2 个 bug） | ✅ 已完成（当时 282 用例；见第 10 节） |
| **11** | **长期记忆**（`{dir}/{userId}/MEMORY.md` + 索引常驻注入 + `saveMemory`/`readMemory`/`forgetMemory` + 包归属守卫） | ✅ 已完成（见第 11 节） |
| **12** | **结构治理**：修守卫 → 修"现在就在错" → 收敛唯一出处 → **解包级环** | ✅ **已完成**（环 **8 → 0**；详见 `PROCEDURES` §12，下一步 = 阶段 13） |
| **13** | 工作流模式（真实编排）：计划 → 逐步执行 → 汇总 | ✅ **已完成**（`chat` 早就跑完整工具循环；本阶段补的是显式计划 + 步对应 + 重规划，见 §七） |
| **14** | 可观测性与**审计**（耗时 / 成功率 / 工具计数 / token 成本） | 顺延 |
| **15** | 新工具追加（工具分组 / 最小权限） | 顺延 |
| **16** | MCP 引入（外部工具生态） | 顺延 —— **仅占位，尚未论证**（协议第 7 条三问还没答） |
| **17** | 工程收尾（README / 演示脚本 / 限流 / 部署说明） | 顺延（本就未拍板） |

**当前测试状态**：`318 个用例全绿，2 skipped`（`mvnw.cmd -B -ntp clean test`；
35 个测试类 + 1 个抽象契约类由 3 个子类继承）

**能力现状（这个项目现在能做什么）**：

| 能力 | 状态 |
|---|---|
| 静/流式对话（SSE，带思考过程） | ✅ |
| RAG 检索 —— **由模型自主决定何时调工具**（不是每轮必跑） | ✅ |
| 密级过滤（PUBLIC / INTERNAL / CONFIDENTIAL，SQL 层拦截） | ✅ |
| 会话记忆（MySQL 权威 + Redis 缓存，双层） | ✅ |
| 思考过程落库（`@@step:` 标记）+ 历史回放 | ✅ |
| 会话列表 / 改名 / 全部历史（前端可用） | ✅ |
| 长期记忆（跨会话的事实与偏好，模型自主读写） | ✅ |
| 工作流编排 | ⏳ 阶段 13 |
| 可观测性 / 成本统计 | ⏳ 阶段 14 |
| 鉴权（当前只有明文 token 的身份传递，**没有真正的鉴权**） | ❌ 缺口，见 §七 优先级 2 |

> ⚠️ **跑测试前必须设 `DEEPSEEK_API_KEY`**（任意非空值即可，测试不调真模型 ——
> 启动期只做 `Assert.hasText` 校验）。本轮的跑法：
> ```powershell
> $env:DEEPSEEK_API_KEY = "test-placeholder-never-used"
> ```
> **这是一个"已重复多次却没机制化"的操作性错误**（见协议第 6 条），
> 机制待定，见第七节。

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
| 34 | **保留 `ChatMemoryAdapter` 与 `chatMemory` Bean（★ 不可删）**<br>⚠️ **已被阶段 8 推翻：`ChatMemoryAdapter` 已删除**（启动自检反向断言取代它，见第 8 节） | 它是框架边界的 **闸门 + 占位**：`ChatMemoryAutoConfiguration` 的两个 `@Bean` 都是 `@ConditionalOnMissingBean`，我们的 Bean 一删，框架会装 `MessageWindowChatMemory` + `InMemoryChatMemoryRepository` —— **纯内存、重启即丢、无 token 预算**。这正是 `MemoryProperties` 自己警告过的失败模式。详见 `PROCEDURES.md` 阶段 5"坑 4 的复核补充" |
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

# 全量测试（322 个用例；★ 需要三个容器 + Ollama 都在跑，否则集成测试会整类报错）
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

# 测试工具（UTF-8 安全；注意开头的「点 + 空格」）
. "$h\tools\hagent-test.ps1"
问 -Message "我叫张三"                 # 发一次对话
探测 -Path '/nope'                    # 测 404/405/415（不需要 Key）

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

## 七、待办（按优先级，新对话从这里开始）

### ✅ 优先级 1（历史）：阶段 12 已完成（结构治理，包级环 8 → 0）

**为什么单独立一个阶段**：`docs/架构问题.md` 是一份第三方审计（101 类 / 14,297 行）。
它不是新功能，但它发现的问题（4 个包级环、9 个无效配置键、~20 个死成员、
3 类"同一规则多重出处"、1 个路径穿越）**会被后续阶段放大** ——
工作流 / 新工具 / MCP 落下来时，新代码只会照着旧形状写。所以先还债、再添功能。

**这份报告的可信度（已逐条复核，别当成传话）**：主体成立、行号级引用精确；
但有 **7 处需要修正，其中 2 处改变优先级** —— 见 `docs/架构问题.md` 顶部的「复核结论」块。
**结论先行**：它是**债务台账**，不是**施工图**。

#### 本阶段的四步（顺序不可换）

| 步 | 内容 | 为什么必须这个顺序 |
|---|---|---|
| **0** | **装 ArchUnit**（1.5.1，`com.tngtech.archunit:archunit`）做"包级无环"与分层方向断言；**同时保留** `PackagePlacementTest` 的源码扫描做 import 级规则 | 搬包动作全被"`*Service` → `app`"这条后缀规则**物理挡住**。★ 两条守卫**必须并存**：ArchUnit 看**字节码**，而 `WebConfig → ChatController`（header 常量）因**常量内联**在字节码里**不存在**（javap 实证），ArchUnit 永远看不见它；源码扫描补这个盲区 |
| **1** | 修 **4 条"现在就在错"**：① `file` 模式会话 ID **路径穿越** + `_catalog` 撞名 ② 扩展名白名单双出处 ③ `written()` 语义说谎 ④ 步数上限：孤儿键 `hagent.agent.max-steps` → `AgentLoopProperties`（**已拍板，形状见下**） | 零结构变化；每项都能"先写红、再修绿" |
| **2** | 收敛**唯一出处**：密级解析 ×3 / 原子文件写 ×3 / "实现名"字典 ×3 / 模型可见文本 ×3 | 必须在搬包前做，否则同一份重复逻辑会被搬两次 |
| **3** | 解 **1 个 4 节点强连通分量** `{app, config, memory, tool}`（ArchUnit 实测**8 条**基本环），逐一拆它上面的反馈边 | ★ **不是"删 4 条边"**：审计说的"4 个双向环"只是 4 条**两节点**环，真实结构是一个 SCC。只砍 `app⇄tool` 那一对，`app→memory→config→tool→app` **仍然成环**（ArchUnit 已列出来）。环不消灭，阶段 15/16 只能"照着旧形状抄" |

**本阶段结果**：四步全部完成 —— 第 0 步 4/4（ArchUnit + `PackagePlacementTest` 双守卫；
★ 环基线冻结机制已随环归零**删除**）、第 1 步 4/4、第 2 步 6/6、
**第 3 步 5/5：包级环 8 → 0**。详细记录见 `docs/PROCEDURES.md` §12（12.3 / 12.6）。

**已登记、刻意不混入本阶段的尾巴**：`IDENTIFIER` 正则两份、`SafePaths` 抽取、
`FileSessionStore.write` 改原子写、`SessionDtos`→`ConversationDtos` + HTTP `/chat/conversations`、
3 个死键（`min-tail-ratio` / `ingest.dept` / `heartbeat-interval-ms`）、
`MemoryProperties.ignoreUnknownFields`；以及一个**待拍板**的命名问题 ——
`hagent.memory.chat` 前缀里的 `chat` 指的是"会话记忆"，导致预算键成了
`hagent.memory.chat.chat-max-tokens`（`chat` 出现两次），建议改成 `hagent.memory.conversation`。

#### 本阶段**明确不做**（防止无限膨胀）

审计的第 4 批（拆 `AgentPipeline` 5 个协作者、`memory.impl.*` 重组、`dto` 逐 record 拆文件）
与全部**改名类**建议都不在本阶段：

- `MemoryMode → MemoryBudgetProfile`：**拒绝**。`mode` 是落库值（`chat_conversation.mode`）+ HTTP 契约 +
  前端标签，改名会造出一对**新的同义异名**（违反协议第 12/13 条）
- `AccessLevel → security`：**拒绝**。它是文档域词汇；搬过去会让 `document` 从"零依赖叶包"
  变成依赖 `security`（与审计自己 §2.1-⑧ 的表扬相反）
- `clear` / `delete` / `evict`：**不删**。审计把它们算"死成员"（确无生产调用者），但
  `HANDOFF` 的诚实清单里"会话删除接口"是明确欠着的功能 —— 删掉端口方法等于删掉下一个落点

#### 待拍板（未定就动手 = 违反协议第 1 条）

| # | 问题 | AI 倾向 |
|---|---|---|
| ① | ~~`hagent.agent.max-steps: 20` 删键还是迁~~ | ✅ **已拍板：迁 + 按模式两档**（`AgentLoopProperties`，chat 10 / workflow 20 **现在就生效**）—— 形状见下 |
| ② | ~~守卫升级走哪条路~~ | ✅ **已拍板：B（引 ArchUnit）** —— 细节见下 |
| ③ | 死成员删到什么程度（8 个死错误码 / 4 个死方法 / `chunkTotal` / `RetrievedChunk.level`）？ | 先删**纯删除、零行为变化**那批；`RetrievedChunk.level` 是复核时**新发现**的零读者字段，随第 2 步定 |
| ④ | **鉴权缺失**（"我的会话"其实就是"所有会话"）排到哪个阶段？ | 并入**阶段 15（新工具 / 权限分组）**，或单独立一阶段 —— 需要你定 |

#### ① 已拍板：`AgentLoopProperties`（**按模式两档**，不是全局一个值）

**这条前提由本人给出，纠正了一个原本的设计误解**：10 / 20 不是"一个上限的两个候选值"，
而是 **chat 与 workflow 各自的单轮执行上限**，目的是**防烧 token**。原来那个
`hagent.agent.max-steps: 20` 是**写对了数值、写错了位置（没有模式维度）、也没人读**。

```yaml
hagent:
  agent:
    chat-max-steps: 10        # 取代现在生效的 hagent.memory.chat.max-steps: 10
    workflow-max-steps: 20    # 认领原来那个孤儿 20
```

形状与 `MemoryProperties` 的预算**同构**（两个字段 + `AgentPipeline` 里一个**穷尽 switch**）：

```java
private int maxStepsOf(MemoryMode mode) {   // 刻意不写 default：新增模式会编译失败
    return switch (mode) {
        case CONVERSATION -> agentLoopProperties.getChatMaxSteps();
        case WORKFLOW     -> agentLoopProperties.getWorkflowMaxSteps();
    };
}
```

- **为什么不并进已有的 `TokenBudget`**（协议第 13 条要求先找复用）：`TokenBudget` 的消费者是
  `MemoryTrimmer`（裁历史），塞一个 `maxSteps` 进去会让 trimmer 拿到一个**它永不读的字段**（坑 #12）。
  两者是不同轴：**历史有多长** vs **循环跑几轮**
- ★ **已知副作用（本人已确认接受）**：前端 `Composer.vue:85` 现在就允许选"工作流"，
  所以选它的会话，最坏情况模型调用 **10 → 20**。因此阶段 12 的口径是
  "**除 workflow 步数外**零行为变化"，不是"零行为变化"

#### ② 已拍板的技术底座（已实跑验证，不是"应该能行"）

| 项 | 事实 | 怎么验的 |
|---|---|---|
| 制品 | **`com.tngtech.archunit:archunit:1.5.1`**（只依赖 `slf4j-api`） | 从阿里云镜像 `dependency:get` 拉到本地仓库；读它的 POM，`<dependencies>` 只有一条 |
| ★ **不要** `archunit-junit5` | 本项目 JUnit 是 **6.0.3**，那个制品会拽 `junit-platform` 5.x 进来 | 本地仓库里 `junit-jupiter` 有 5.12.2 / 6.0.3 两个版本，混用是自找的 |
| 能读我们的字节码 | `ClassFileImporter().importPackages("com.hagent")` 正常导入，Java 21 类文件无告警 | 写了个一次性的 `tmp/archprobe/Probe.java` 跑通（`java exit=0`） |
| 能报出环 | `slices().matching("com.hagent.(*)..").should().beFreeOfCycles()` → **8 条环**（全落在 `{app, config, memory, tool}` 这个 SCC 内） | 同上；环清单见 `docs/架构问题.md` 复核结论第 7 条 |

★ **结论性的一条**：ArchUnit **不能单独用** —— `config → controller` 那条边在字节码里被**常量内联**抹掉了（`javap` 实证），
它永远看不见。所以守卫是 **ArchUnit（字节码）+ 现有源码扫描（import）** 并存，
后者继续管"谁 import 了 controller"这类源码级规则。

### ✅ 优先级 1（历史）：阶段 11 已完成（长期记忆 `.md` + 索引）

**结果**：`310 用例全绿（2 skipped）`；详细记录见 `docs/PROCEDURES.md` 第 11 节（两张 Mermaid + 坑 43）。

#### 拍板结果（本次讨论定下的五条，别再重新论证）

| # | 问题 | 结论 |
|---|---|---|
| 1 | 谁能写记忆 | **只有模型通过工具**（与"检索由模型决定"同源） |
| 2 | 怎么读 | **索引常驻注入 system** + 明细按需 `readMemory` |
| 3 | 归属 | **`{dir}/{userId}/`**（记忆是"关于这个人的事实"，共享会串号） |
| 4 | 索引上限 | **1000 字**；超限**拒绝写**（不静默淘汰旧条目） |
| 5 | 工具数量 | **3 个**：`saveMemory`（**upsert**）/ `readMemory` / `forgetMemory`。**不加 `updateMemory`** |

#### 实际做出来的形状

```
{dir}/{userId}/
  MEMORY.md                       索引（物化产物，每次写后重生成，供人看）
  mem-{sha256(title)前8位}.md      一条记忆（frontmatter + 正文，正文 ≤2000 字）
```

| 决策 | 理由 |
|---|---|
| 文件名 = 标题哈希 | 标题来自模型 ⇒ 直接当文件名就是目录穿越入口。用前 8 位十六进制后，**不是被校验挡住，而是根本构造不出来**；顺带让 upsert 天然成立 |
| 密级存 **`AccessLevel`** | **复用**（协议第 13 条）：写入取 `user.clearance()`，注入前 `entry.level().visibleTo(当前密级)` —— 与检索同一个枚举、同一条规则 |
| 不加 `updateMemory` | upsert 就是更新；`forget+save` 是两步非原子操作（中间失败 = 丢记忆），而 `save` 是单步幂等 |
| 记忆工具与检索开关**解耦** | 关掉检索不该顺带让人失忆 |
| `MEMORY.md` 是物化产物 | 真相是那些 `mem-*.md`；索引每次重生成 ⇒ **不可能与条目漂移** |

#### 本阶段换来的可迁移认知

1. **"配了没人读"要主动查**：`hagent.memory.long-term.dir` 从阶段 5 就写在 yml 里，
   而没有任何 Java 字段绑定它 —— 改了它以为换目录了，其实什么都没发生（坑 #12 的又一个实例）。
   本阶段绑定并加了启动校验。
2. **降级要发生在能说出原因的那一层**（沿用阶段 10 的判据）：记忆读失败 → WARN + 本轮不注入，
   而不是让请求失败，也不是静默空。
3. **`test-compile` 不带 `clean` 会假绿**（坑 43）：改了构造器签名，增量编译**不重编依赖方**，
   于是"BUILD SUCCESS"而测试根本编译不过。

#### 留下的诚实清单

`updateMemory` / 记忆向量检索 / 自动过期 / 跨用户共享 / 冲突消解 / 记忆管理页 —— 理由见 `PROCEDURES.md` 11.7。

★ 另一笔**本轮付清又添新账**：配置类放错包（踩坑 #40）已挪正并由 `PackagePlacementTest` 守住；
随后把 3 个**历史例外**也一并归位（`MemoryProperties` / `MemoryConfig` → `config`，
`ToolRegistration` → `config`），现在是**零例外**。

#### ✅ 那条验证已经跑通（2026-10-19，真实 Key）

**新会话（历史=0）问"我是谁" → 答"你是李四（管理员）"**。
链条：`长期记忆索引 140 字` 注入 system → 模型调 `readMemory` → 答对。
它同时就是 `STAGE5-VERIFICATION.md` 第 2.4 步欠的那条 —— **两条一起结清**。
证据见 `docs/STAGE5-VERIFICATION.md` 末尾「验证记录」（含库里的回答原文）。

---

### ✅ 优先级 1（历史）：阶段 10 已完成（前端页面 + 会话目录 + 思考落库）

**结果**：`279 用例全绿（2 skipped）` + 真实 HTTP 联调取证；
详细记录见 `docs/PROCEDURES.md` 第 10 节（含两张 Mermaid 图 + 坑 38/39/40）。

#### 实际做出来的形状

| | 计划 | 实际 |
|---|---|---|
| 会话列表 | 新表 `chat_conversation` | ✅ 一会话一行（标题 / 模式 / 时间），**懒创建**（第一句话时） |
| 标题 | 取第一句消息 | ✅ 用户可 `PATCH` 改名；**改名后再说话不会被覆盖回去** |
| 历史回放 | "返回该会话全部历史" | ✅ `GET /chat/sessions/{id}/messages`（含思考分段） |
| 思考落库 | 加一列 | ✅ `chat_message.reasoning MEDIUMTEXT` + `@@step:N` 标记（**文本，不是 JSON/子表**） |
| 步边界 | 前端要"第 N 步" | ✅ 后端发 `step` 事件（`StepStarted`）—— 只发步号，其余由已有事件推导 |
| 工具调用可视化 | 悬浮窄框 | ✅ 前端 `TaskPanel`（DSH 风格），一个 step 下挂 N 个工具 |
| 模式 | `ChatRequest.mode` | ✅ 真实生效**并落库**；首次创建后不再由请求改变 |
| CORS | 加 5173 | ✅ 加了 `localhost` 与 `127.0.0.1` 两个（浏览器视为不同源） |
| 工具参数进历史 | 待拍板 | ★ 用户选 **A：不进**（历史里只有"第 N 步 · 工具名"） |

#### ★★ 本阶段最重要的一个发现：SSE 的 `data:` 行放不下裸换行（坑 38）

这条**一直存在**（阶段 8 起），只是没人认真看排版。字节码证据：
`SseEmitter$SseEventBuilderImpl.data(...)` 只做 `append("data:") + append(数据)`，**不转义换行**。
于是 token 里的 `\n` 会把帧切断、把段落空行吃掉 —— 表现为"markdown 排版偶发错乱"。

**修法**：所有**文本**载荷包成 JSON（`{"text":"…"}` / `{"message":"…"}`），
换行被转义，序列化结果永远只有一行。⇒ **这是本阶段唯一一处 SSE 契约变更**（前端同步改）。

#### 两条被"验证为假警报"的排查记录（省下后来者的时间）

1. **中文落库变问号**：用 `Invoke-WebRequest -Body '…中文…'` 发出去，库里查到 `3F3F…`。
   改用**按码点构造 + UTF-8 字节**后 `HEX(title)` 是 `E5B9B4E58187E694BFE7AD96`（正确的"年假政策"）。
   ⇒ **服务端没问题，是测试命令里的中文在到达 HTTP 之前就被替换了**。
   判据：中文落库异常时先 `HEX()` 看字节 + 检查"发出去的那一侧"。
2. **`Get-Content` 显示 `鏉窞…`**（老坑）：文件是好的，是控制台编码问题 —— 用 `-Encoding utf8` 或 `read` 工具。

#### ★★ 真机联调修掉的两个 bug（联调才有，mock 永远测不到）

| bug | 根因 | 认知 |
|---|---|---|
| **问年假/差旅，检索全返回"未找到"，而库里就是有** | 检索**跑在 Reactor 的 Netty 事件循环线程上**（`reactor-http-nio-*`）。工具里的阻塞调用（Ollama embedding 的 RestClient 底要 `Mono.block()`）被 Reactor 直接拒绝：`block() ... not supported in thread reactor-http-nio-4` → 异常被吞 → 空列表 → "没命中" | **整条管道允许阻塞，所以整条管道都不该在事件循环上跑。** 修法：`loop()` 里 `concatWith(handleToolCalls)` **之前**加 `publishOn(boundedElastic)`（加在外层无效 —— publishOn 只管它下游）。★ 同一根因还压着 `writeBack`/`recordAudit` 两个 JDBC 调用：它们不报错，只**静默堵事件循环** |
| **每次都被答"知识库中未找到相关内容"** | `retrieve` 失败时 `return List.of()` → 与"没命中"**同形** → 工具回"没找到" → 模型照说 | **降级要发生在"能说出原因"的那一层。** 在只看得到 `List` 的层里降级，原因永久丢失。修法：失败**抛** `RetrievalUnavailableException`，工具接住后返回"检索不可用（系统故障，**不是**没有资料）"。★ 顺带修掉阶段 9.7 留的账：工具自己知道失败 → 不必从文本猜 |

**为什么 282 个用例都没抓到**：所有假模型都是 `Flux.just(...)`，元素在**调用线程**上发出；
真机上元素来自 Netty 线程。**bug 正好长在 mock 边界之外**（§十 第 5 条）。
⇒ 已机制化：新用例用 `publishOn(Schedulers.parallel())` 假装"元素来自非阻塞线程"，
假工具在**工具外**断言 `Schedulers.isInNonBlockingThread() == false`（工具内抛异常会被框架翻成文本 → 假绿）。
**先红（跑在 `parallel-1`）后绿**都验证过。

**顺带澄清**：执行轨迹里"一步两个 retrieveKnowledge"**不是重复渲染** ——
日志里两条 query 不同，是模型在同一步发了两个并行工具调用（自己做查询改写）。
已改为显示 `「query」` 以便区分；代价是双倍 embedding/向量查询，**决定接受**（召回更好）。



#### 三条可迁移认知

1. **自由文本不能直接塞进协议字段**（坑 38）：先问"这文本能含换行吗"，能就必须编码。
   让对端"聪明地拼回续行"是有损的 —— `\n\n` 的第二个换行已经被协议吃掉，拼不回来。
2. **"数据在响应式容器里"≠"我手里的引用是响应式的"**（坑 39）：
   裸对象塞进 `reactive` 数组后，改裸对象不触发更新 —— 表现为"流式时界面完全不动"。
3. **`affected rows = 0` 有两种含义**（坑 40）：`UPDATE` 在"值没变"时也返回 0。
   拿它判断"记录不存在"，用户原样提交改名就会收到 404。

#### 本阶段建立的机制

- **`ReasoningTrace` 是标记格式的唯一出处**（`render` 写 / `parse` 读），并有一个**往返恒等**测试守着 ——
  两侧不对称的症状是"历史里的思考错位或消失"，**不报错**。
- **`MemoryMode.wireName()` / `fromWire()`** 把"枚举名 vs 线上名"的翻译收在一处：
  将来把 `CONVERSATION` 改名成 `CHAT` 时，请求体、响应体、数据库**一个字都不用动**。

#### 留下的诚实清单（本阶段没做）

会话删除接口 / 历史回放的工具参数与结果 / 工具成功失败标识 / 单次工具耗时 /
会话按用户隔离（无鉴权）/ 工作流的真实编排（阶段 13）/ 会话列表分页 —— 理由逐条见 `PROCEDURES.md` 10.10。

---

### ✅ 优先级 1（历史）：阶段 9 已完成（工具调用与多步 Agent）

**结果**：`251 用例全绿`；详细记录见 `docs/PROCEDURES.md` 第 9 节（含两张 Mermaid 图 + 坑 35/36/37）。

#### ★★ 本阶段最重要的一个认知转变

**RAG 从"每轮无条件检索"变成"模型决定要不要查"。**

| | 阶段 8 及之前 | 阶段 9 之后 |
|---|---|---|
| 检索何时发生 | 每次请求**必跑**（管线第③步） | 模型调 `retrieveKnowledge` 工具时才跑 |
| 谁决定 | 代码 | **模型** |
| 问"今天几号" | 白烧一次 embedding + 向量查询 | 不检索 |
| 无关片段 | 被塞进 system，**干扰模型** | 不进 system |

⇒ 这才是 agent 与"固定 RAG 流程"的实质差别。`retrieval.enabled=false` 现在表示
"**检索这个能力整体拔掉**"（模型看不到该工具）—— 检索成了一个**可插拔的插件**。

#### 实际做出来的形状

| | 计划 | 实际 |
|---|---|---|
| 循环驱动 | `AgentPipeline` 自己驱动 | ✅ 一致（框架 `ToolCallingAdvisor` 早已不参与） |
| 工具注册 | `com.hagent.tool` + 反射注册 | ✅ `MethodToolCallbackProvider`（**不引第三方包**）+ `ToolRegistration` 唯一入口 |
| 工具数量 | 4 个 | ★ **只做 1 个**（`retrieveKnowledge`）—— 工具数量不是目标，先打通链路 |
| 事件 | 加 `ToolCall` / `ToolResult` | ✅ 另加：`Retrieved` 改由工具发出（结构化出处，不经过文本解析） |
| 审计 | 独立表 | ✅ `chat_tool_invocation`（显式 DDL + 启动自检） |
| 工具分组 | 9.4 要做 | ★ **刻意不做** —— 只有 1 个工具且权限已在 SQL 层；等第二个实例出现再做 |
| 工具消息进记忆 | 讨论中曾倾向"进" | ★ **不进**（只存问+答）。理由：循环内必然可见（已实测），跨轮信息已在最终答里 |

#### 三条本阶段换来的可迁移认知

1. **`then()` 会丢弃上游所有事件**（坑 35）—— 前端只收到 `Final`，日志全绿。
   抓它的是"事件数量必须等于 3"的断言。判据：**需要上游元素就用 `concatWith`，只要完成才用 `then`**。
2. **"我能构造出一个集合"≠"容器里有那个类型的 Bean"**（坑 36）——
   改依赖类型时要同时问"谁来提供它"。
3. **改"数据怎么到达模型"时，必须同步检查提示词里对数据的引用是否仍成立**（坑 37）——
   两者分开维护，编译器不会帮你。

#### 留下的诚实清单（本阶段没做）

工具分组 / 中断续跑 / 其余 3 个工具 / `ToolResult.error` 字段 —— 理由逐条见 `PROCEDURES.md` 9.8。

---

### ✅ 优先级 1（历史）：阶段 8 已完成（advisor 链 → `AgentPipeline`）

**结果**：`234 用例全绿`；详细记录见 `docs/PROCEDURES.md` 第 8 节（含两张 Mermaid 图 + 坑 32/33/34）。

#### 实际做出来的形状（与原计划的差异）

| | 原计划 | 实际 |
|---|---|---|
| 契约名 | `AgentEvent` / `AgentOutput` | ★ **`PipelineEvent`** —— 因为你指出 "agent = harness + model"，**不该有"agent 模式"**，模型产出叫 `ModelOutput` 而非 `AgentOutput` |
| 事件种类 | `Retrieved` / `Token` / `Final` | ★ **4 种**：`Retrieved` ⊥ `ModelOutput`{`Reasoning`/`Token`/`Final`} —— 因为要展示**思考过程**，而 `reasoningContent` 是**独立字段**（不在 `getText()` 里） |
| `/chat` 响应体 | 只有 `reply` | ★ **加 `reasoning`** —— 否则刷新页面后思考永久丢失（记忆只存"问+最终答"） |
| `ChatMemoryAdapter` | 删 + 启动自检 | ★ **第一版方案是错的**：框架 `ChatMemoryAutoConfiguration` **没有 enabled 开关**，删了必然补位 → 自检永远通不过（65 个集成测试全红）。正解是 `@SpringBootApplication(exclude=...)` + 自检**反向确认** |
| `MemoryBudgets` | 保留（选项 A） | ★ **删除** —— 你指出"它只是声明，唯一消费者是 trimmer"；框架边界消失后它只剩一个调用点，映射挪进 `AgentPipeline#budgetOf`（穷尽 switch，编译期保证保留） |
| `PromptLogFormat` | 列在"删除" | **保留**并换包（原清单自相矛盾：它有两个消费者） |
| `AdvisorProperties` | 列在"删除" | ★ **改名 `TraceProperties`**（原清单写错：它装的是日志开关） |
| 测试用例 | 删 4 个 | ★ **删 12 个**（原清单少算；其中 3 个是"问题被结构性消灭"而失去意义） |
| 新增测试 | `AgentPipelineTest` | 另加 **`PromptBuilderTest`**（14 条由 `PromptAssemblyAdvisorTest` 迁移）+ **`ChatBudgetTest`**（由 `MemoryBudgetsTest` 重建，改为与**期望值**比较） |

#### 三条本阶段换来的可迁移认知

1. **"删掉一个占位 Bean"不是删除，是把占位权交给框架** —— 删之前必须确认"没有它会怎样"，
   **并确认那个后果能被拦住**。我漏掉的正是后半句（自检上线前没先问"框架到底会不会装它"）。
2. **框架的"可关闭性"要读字节码/元数据确认**：`@ConditionalOnMissingBean` 是"让位"语义，**不是开关**。
3. **配置层的字符串生效与否不会报错** —— 我把 `spring.autoconfigure.exclude` 缩进插错层级
   （落进 `spring.ai:` 之内），且 `spring.ai:` 出现两次导致**后者覆盖前者**（`model.chat=deepseek` 被盖掉），
   而报错只说"自检失败"，**一个字都没提 yml 写错了** ⇒ **能用编译期的就用编译期**。

#### 阶段 9 的第一件事（已写进 §七）

★ **读参考实现 `.toolcall()` 的真实签名**（协议第 3 条：不凭记忆写）。

#### ★★★ 阶段 8 动手前的全量复核（已做完，**上面那份清单已被本节的修正取代**）

复发指令：本人要求"重新读 `src` 下所有代码，没问题就动手，有问题再讨论"。
复核方式：**我逐行读** + **4 个只读子代理独立复核全部 5 个包**（未修改任何文件）。
结论：**有问题，原清单低估约 3 倍**。以下为修正后的账目。

##### 一、阻塞性问题（改设计 / 改工作量量级）

| # | 问题 | 修正后的做法 |
|---|---|---|
| 1 | ★ `MemoryBudgetsTest#advisorAndAdapterAgreeOnTheSameBudget` 的**回归防线失效** —— 它比较的正是 `ChatMemoryAdapter` 与 `MemoryReadAdvisor`，**两个都要删**。类注释 28–43 行把"两条路径"写成该类存在的理由 | 断言搬进 `AgentPipelineTest`，比较对象从"另一条路径"换成**期望值**（`MemoryBudgets.forMode(mode)`） |
| 2 | ★ `PromptAssemblyAdvisorTest` 的 17 个用例**不能"复用"** —— 全部建立在 `ChatClientRequest` + `DefaultAroundAdvisorChain` 上（第 87–97 行是统一装配辅助）。**外壳全部重写**，其中 **2 条应直接删除**（"context 类型不符告警"= 被方法参数编译期保证取代；"getOrder()"= 无 order），1 条保留语义，**14 条迁移** | 量级修正：不是"断言基本可复用"，而是**重写外壳 + 删 2 条** |

★ 深层影响：`MemoryBudgets` 的"两处共用"理由**随框架边界一起消失**。但仍应保留 ——
理由换成**它真正保住的那条性质**：`budgetOf` 是穷尽 `switch` → **新增 `MemoryMode` 会编译失败**。
理由一换，类注释 / `MemoryProperties` 引用 / 测试注释都要改。

##### 二、我原清单里的 4 处错误（已更正）

1. ★ **`PromptLogFormat` 被自相矛盾地列进"删除"**，而 pipeline 日志要复用它 —— 它**保留**，
   但从 `advisor/` 换包（它是**包私有** `final class`，换包必须改可见性）；
2. ★ **`AdvisorProperties` 不能删**（原文写错）—— 它装的是日志开关与截断长度，**改名** `TraceProperties` + `hagent.trace` 继续用；
3. ★ **`AiConfig` 是整篇重写**，不是"删几个 Bean"：第 22–194 行几乎全在讲 ChatClient / 切面链 / 顺序约束。
   真正值得保留的只有 **86–93 行**那段"两个 starter 会各建一个 `ChatModel` Bean"（讲的是 `ChatModel`，**要搬家**）；
4. ★ **测试数少算 5 个**：待删 12（不是 4），待改写 1。

##### 三、悬空引用清单（协议第 12 条规则 1）：**19 个文件**

```
main(13)  ConversationMemory(26,45,46)  MemoryBudgets(12,13,46-51)  MemoryMode(11,12,67,94,98)
          MemoryConfig(28,140)  MemoryConfigValidator(18-27 整段论证)  KnowledgeSearch(21,22,35,49)
          UserContext(11,22,27-40)  UserContextInterceptor(28,42-43)  LayeredConversationMemory(16,24)
          WebConfig(97,116)  RagConfig(14,33,64)  StreamProperties(11)  AiConfig(整篇)
          ChatAppService(90,141,142,152)
config(2) RetrievalProperties(53)  application.yml(156)      ← ★ 配置文件的注释最隐蔽
既有陈旧(3) MemoryTrimmer(13,17)  MessageCodec(16,20)  ChunkingStrategy(12)   ← "三个实现"是阶段 5 旧措辞
已修(1)   PromptProperties（javadoc 写着"放在 prompt 包"，而那个包不存在）✅
```

##### 四、会**编译失败**的文件（只有这些）

`config/AiConfig`（29 处）；`test/memory/MemoryBudgetsTest`；`test/advisor/` 三个测试类。
★ **其余悬空引用全部只在注释/javadoc 里** —— 因为 `pom.xml` **没有 `maven-javadoc-plugin`、没有 `-Xdoclint`**，
所以 `mvn compile/test/package` **全部通过**，只有 IDEA 会标红、`mvn javadoc:javadoc` 会失败。
⇒ **"编译通过"不能用来检查"注释有没有撒谎"，必须靠清单逐条改。**

##### 五、复核新发现的 3 条技术事实（都改变了设计）

| # | 事实 | 对设计的影响 |
|---|---|---|
| 1 | ★★ `new Prompt(messages)` **会自动带上配置的 options**（`DeepSeekChatModel.buildRequestPrompt` 字节码：`if (prompt.getOptions() == null) → prompt.mutate().chatOptions(this.getOptions())`；而 `Prompt(List)` 把 options 置 null）。**显式传 options 反而覆盖配置** | 原"⓪ 先写探针验证 options 保持"这一步**取消**（已用字节码解决，省一次真实调用）。pipeline 必须写 `new Prompt(messages)`，**不传 options** |
| 2 | ★★ `SseSupport` 的三条 true 分支**在生产路径上从未被执行**：Spring 7.0.9 里 `ResponseBodyEmitterReturnValueHandler` 与 `ReactiveTypeHandler` **完全不引用 `ResponseBodyAdvice`**（javap 核实），SSE 端点根本不进 `beforeBodyWrite` | `SseSupport` **保留不动**，但**保留理由要换**：不是"修 bug"，而是"**防框架路由变化 + 防未来误用**"。★ 分支 3（`text/event-stream`）恰恰是唯一能兜住前两种失效的一条。另记一条**待澄清项**：若 `beforeBodyWrite` 从不经过 SSE 路径，则踩坑 #9 的症状**不可能由统一响应包装造成**（真实原因就是它自己写的"`SseEmitter` 不认识 `ServerSentEvent`"）—— 记为待澄清，**不擅自改写历史** |
| 3 | ★ `LayeredConversationMemory.append` **返回 void 且 `catch (Exception)` 吞掉一切**（记录层写失败**只打 ERROR、不抛异常**）；`load` **永不返回 null、永不抛异常**（失败降级 `List.of()`） | pipeline **不可能**从返回值判断写回成败 ⇒ `Final` 事件**不得**带"写回成功与否"之类字段（那是编造）。写回成败的唯一可观测渠道是**日志**（与决策 40 一致） |

##### 六、本次复核**新增的 3 个待拍板项**

| # | 问题 | 建议 |
|---|---|---|
| 1 | `MemoryBudgets` 处置 | **A 保留类 + 重写注释与测试**（理由换成"穷尽 switch 保证新增模式编译失败"） |
| 2 | `RagConfig.ragAnswerPromptResource` Bean 存废（它**唯一消费者**是 `AiConfig`，将消失） | **保留**，消费者换成 `PromptBuilder`（保住"启动期校验资源存在"） |
| 3 | `MemoryConfigValidator` 的定位 | **保留**，但注释里"`chatClient` 依赖它"必须改成"`AgentPipeline` 依赖它"（否则第 18–27 行整段论证指向不存在的装配链） |

##### 七、`StartupProbe` **零必改**（复核确认）

它早已是 `chatModel.call(new Prompt("ping"))` —— **不传 options、不依赖 `ChatClient`、不碰 context**。
⇒ **阶段 8 要迁往的那条裸 `ChatModel` 路径，启动探针已经跑了很久**，这是对上面事实 1 的独立佐证。

##### 八、前端契约的一条已知限制（留给前端阶段）

`RetrievedChunk` 是 record、**无 null 校验**，且 `AccessLevel` / `RelevanceBand` 会被 Jackson 按
**枚举 name**（`HIGH`/`MEDIUM`/`LOW`）序列化，而 `RelevanceBand.displayName()` 的中文"高/中/低"
**不是属性** ⇒ 前端要显示中文档位得自己映射。**本阶段不加 `displayName` 字段**
（协议第 7 条：还没有消费者时不加）。

#### ★ 顺带：阶段 7 的 4 项人工验收**还没跑**（不阻塞阶段 8，但别丢）

应用已能启动（启动日志 5 行全中）。4 项**核心**（用 `tools/hagent-test.ps1` 发请求）：

| # | 怎么跑 | 判定 |
|---|---|---|
| 1 | 同会话先"我叫张三，在一家做企业服务的公司上班"，再问"我叫什么？" | 第二轮答出**「张三」**，日志里 `注入历史` 从 0 变 2 |
| 2 | **停掉应用 → 清 Redis（`FLUSHDB`）→ 重启 → 用同一个 `cid` 再问** | 仍答得出 → 证明 **MySQL 是权威** |
| 3 | **不重启**，只 `DEL hagent:chat:memory:<cid>`，再问 | 仍答得出，且该 key 被**回填** → 证明上下文层**可重建** |
| 4 | `查MySQL "SELECT id, role, LEFT(content,30) FROM chat_message ORDER BY id DESC LIMIT 10"` | 每轮都在、中文完好；再发一条含 `😀𠮷` 的，`HEX` 里应出现 `F09F9880`/`F0A0AEB7` |

> ★ 查库用 `tools/sql.ps1` 的 `查MySQL`（**不要手写 `docker exec ... -e "SQL"`** —— 引号会被 PowerShell 吃掉，
> 见 §六 6.3 第 3 条）。

---

#### 附：阶段 7 的提交内容（**留档**，已于 `468413e` 提交）

内容如下：

- **新增（10）**：`LayeredConversationMemory` / `FailureCircuit` / `LayerStatus` /
  `record/ConversationRecordStore` / `record/MysqlConversationRecordStore` /
  `context/ConversationContextCache` / `context/NoOpContextCache` /
  `config/BusinessDataSourceConfig` / `config/BusinessDataSourceProperties` /
  `resources/db/chat_message.sql`
- **改造（2，含改名）**：`file/FileConversationMemory` → `record/FileConversationRecordStore`；
  `redis/RedisConversationMemory` → `context/RedisConversationContextCache`
- **修改（10）**：`MemoryProperties`（两个开关 + 熔断秒数）/ `MemoryConfig`（装配两层 + 修陈旧注释）/
  `MemoryConfigValidator`（两个开关 + 旧键报错）/ `ConversationMemory`（javadoc 改成两层语义）/
  `application.yml` / `HealthAppService` / `HealthController` / `VectorStoreConfig`（★ 加 `@Qualifier`）/
  `DocumentIngestService`（★ 加 `@Qualifier`）/ 3 个集成测试（★ 加 `@Qualifier`）
- **删除（3）**：`InMemoryConversationMemory`（死代码）、`FileConversationMemory`、`RedisConversationMemory`
  —— 以及随之空掉的 `memory/file/`、`memory/redis/` 两个包
- **测试**：**+50 用例（189 → 239）**；契约测试抽象类 + 3 个组合子类 + 装配断言 + 校验器测试
- **文档**：`PROCEDURES.md`（阶段 7 开头两张图 + 坑 12–15 + 阶段结果 + 验证证据）/
  `HANDOFF.md`（决策 1、2、3 修订 + 决策 37–50 + 坑 28–31）
  / 新增 `docs/STAGE7-VERIFICATION.md` —— ★ 该文档随后按**协议第 5 条**删除（不再为每阶段写验收清单文档），
  内容已压缩进本节上面的「阶段 7 的 4 项人工验收」

★ **注意**：`chat_message` 的表结构在本轮**中途改过一次**（删掉了 `seq` 列）。
如果之前已经建过表（**表结构在本轮中途改过一次，删掉了 `seq` 列**），需要重建 —— 表为空、无数据损失：

```powershell
docker cp src\main\resources\db\chat_message.sql hagent-mysql:/tmp/chat_message.sql
docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_message.sql'
```

若报 `table already exists` 说明表已存在（**刻意的**：没写 `IF NOT EXISTS`，因为它查不出"表在但结构不对"）——
先 `DROP TABLE chat_message;` 再执行上面第二条。

- 合计工作树 **41 项变更**（`git status --porcelain`：16 新增 / 21 修改 / 4 删除；
  注：未跟踪的目录各算 1 项）

### ★ 优先级 2：剩下的待拍板事项（**新对话必须问**）

| # | 问题 | 背景 | AI 倾向 |
|---|---|---|---|
| 1 | ~~**`UserContext` 的传递方式**~~ ✅ **阶段 8 已解决**（pipeline 无 context，身份是方法参数） | 曾经：拦截器 → request attribute → Controller 的 `@RequestAttribute` → **方法参数** → app 层放进 context（切面从 context 读）。四段里前两段是编译期检查，**最后一段仍是字符串 key** | ✅ 阶段 8 删掉切面链后，"最后一段字符串 key"随之消失 |
| 2 | **`PromptFragment` 这个类没有建** | 计划里有它，但它是 `RetrievedChunk` 的纯透传（没有第二个使用者）→ 按协议第 7 条不建，片段格式化当时放在 `PromptAssemblyAdvisor#renderFragments`（现为 `PromptBuilder`） | 不建。若将来出现第二种片段来源（例如长期记忆注记），那时再抽 |
| 3 | **默认 system prompt 现在是"约束文件"** | `AiConfig` 旧注释说 system 留到"工具就位后用 `ToolCapabilityAdvisor` 校验"—— 那个切面已撤回。现在 system 由 `PromptBuilder` 从 `prompts/rag-answer-prompt.md` 拼出（角色 + 5 条约束 + 可选的长期记忆索引） | 保持。若要加"通用办公助手"人格描述，改那个 `.md` 即可 |
| 4 | ~~**方式一/二的记忆语义**~~ ★ **已解决**（两个对照端点已在阶段 8 删除，现在只有 `/stream-sse`） | `/stream` 与 `/stream-flux` 是"流式机制对照实验"，**不传 `conversationId`** → 落到兜底会话 `"default"`，与"所有没传会话 ID 的调用"共享历史。**这是原实现就有的行为，本轮只是把它写进了注释**（此前"读代码看不出来"） | 建议**给这两条路径传一个请求内唯一 ID**，让它们不落进共享会话。但那会改变对照实验的行为，**等本人定** |
| 5 | **非法凭证要不要 401 语义的错误码？** | 当前用 `INVALID_PARAM(40001)` → HTTP 400。语义上"凭证无效"更接近 **401 Unauthorized**，但 `ErrorCode` 的 4xxxx 段只有参数/协议类错误，**新增 401 = 契约变更** | 保持 400。理由：400 已足以让调用方知道"该改凭证、别重试"；要不要加 401 是契约问题，不是技术问题 |
| 6 | **★ 把"异常必须有着落"机制化（协议第 6 条）** | 踩坑 #10（协议级异常被报成 500）的教训是"按类补分支"，但**没机制化** → 本轮换个异常类型又踩一次（177 用例全绿但非法 token 报 500） | 二选一：<br>(a) 写一个测试，**扫描所有自定义异常类**，断言每个都在 `GlobalExceptionHandler` 里有分支或明确归入兜底；<br>(b) 在 `GlobalExceptionHandler` 的 `Exception.class` 兜底分支里**打一条 WARN 带上异常类名**（让它"报出缺口"—— 该类的注释里其实已经写了这个原则） |

> ★ 第 6 条是**唯一一条"已经重复两次"的**（协议第 6 条明确要求机制化）。
> 建议优先做它 —— 它防的是"下一类异常又被报成 500"。

### 优先级 3：新增一份 PUBLIC 示例文档（本轮未做）

计划加 `knowledge/企业文化与价值观.md`（约 300 字，`level: PUBLIC`，整篇 1 块）——
用于验证"匿名只能看到 PUBLIC"与"相关度分档的区分度"。
**⚠️ 文件名可能被当成"让用户编辑真实内容的模板"** → 用 `example-` 前缀规避。

### ✅ 优先级 4：两条欠着的验证 —— **2026-10-19 已补完**

| # | 缺什么 | 结果 |
|---|---|---|
| 1 | `STAGE5-VERIFICATION.md` 第 2.4 步："下一轮能否答出名字" | ✅ **成立**，而且是更强的形式：**全新会话（历史=0）**问"我是谁" → 索引注入 → 模型 readMemory → 答"你是李四（管理员）" |
| 2 | "模型是否真的依据检索片段作答" | ✅ **成立**：答"出差怎么报销"时引用了《员工手册 第四章 差旅与报销》，并给出片段里的"一线城市 600 元/晚" |

> 完整证据（日志片段 + 库里的回答原文）见 `docs/STAGE5-VERIFICATION.md` 末尾的「验证记录」。
> ★ 顺带纠正一条旧结论：**流式路径的 token 用量是拿得到的**
> （实测 `usage=DefaultUsage{promptTokens=2591, completionTokens=446, totalTokens=3037}`），
> 阶段 14 可直接用 `Final.usage`。

### 优先级 5：机制化 `DEEPSEEK_API_KEY`（协议第 6 条）

**同一个操作性错误已重复 ≥2 次**：跑测试/启动时忘设 `DEEPSEEK_API_KEY`，
报错是 `DeepSeek API key must be set`（Bean 装配失败，堆栈很长，容易被误读成别的问题）。
**机制化候选**（需本人选）：
- (a) 在 `pom.xml` 的 surefire 配置里给测试 JVM 注入一个**假 key**（非空即可）——
  好处：测试永远能跑；风险：极低（任何真调模型的测试会 401 失败，不会静默通过）
- (b) 加一个 `tools/run-tests.ps1`（带 BOM），内部设好环境变量再调 `mvnw`
- (c) 在 `docs` 里写清楚（**这不算机制化**，协议第 6 条明确反对）

### 后续阶段（`docs/PROCEDURES.md` 的「待推进」，编号以此处为准）

- ✅ **阶段 9：工具调用与多步 Agent** —— 已完成（工具循环 + 事件 + 审计表）
- ✅ **阶段 10：前端页面 + 会话目录 + 思考落库** —— 已完成
- ✅ **阶段 11：长期记忆（`.md` + 索引）** —— 已完成
- ✅ **阶段 12：结构治理** —— 已完成（包级环 **8 → 0**；双守卫 + 唯一出处 6 项 +
  4 条"现在就在错"；细节见 `PROCEDURES` §12 与本节「优先级 1（历史）：阶段 12」）
- ✅ **阶段 13：工作流模式（真实编排）** —— 已完成（计划可见、逐步执行、两次重试后放弃；
  细节见 `PROCEDURES` §13）。★ **前端计划面板未做**：`workflow` 事件已发到浏览器，
  前端目前忽略它 —— 这是纯前端工作，单列
- **阶段 14：可观测性与审计** ← ★ **下一步**（耗时 / 成功率 / 工具调用计数 / token 用量）
- **阶段 14：可观测性与审计** —— 耗时 / 成功率 / 工具调用计数 / token 用量
  - ★ `Final.usage` 在流式路径**已实测拿得到**（2026-10-19：`promptTokens/completionTokens/totalTokens` 都有值），
  可直接用于成本统计；但换模型提供方要重测。工具调用计数可直接查 `chat_tool_invocation`
- **阶段 15：新工具追加**（工具分组 / 最小权限）—— 审计报告指出"只有 1 个工具时不做分组"的决定仍有效，
  第二个工具出现时才需要它
- **阶段 16：MCP 引入**（外部工具生态）—— ⚠️ **仅占位，尚未论证**：
  协议第 7 条三问（解决什么具体问题 / 产出谁消费 / 不做会怎样）都还没答。
  它与阶段 15 强相关：MCP 会把"工具从哪来"变成**动态注册**，而那正是 `ToolRegistration` 该有的形状
- **阶段 17：工程收尾（未拍板）** —— README / 演示脚本 / token 预算与限流 / 部署说明

---

## 八、目录结构（当前关键部分）

```
Hagent/
├── docs/
│   ├── WORKING-AGREEMENT.md        ← 13 条协作协议（最重要）
│   ├── PROCEDURES.md               ← 按阶段的完整记录（~150 KB，含数据流向图与踩坑）
│   ├── STAGE5-VERIFICATION.md      ← 阶段 5 验收清单（第 2.4 步未跑）
│   ├── KNOWLEDGE-SAMPLE-DATA.md    ← 示例数据的说明与 frontmatter 规则
│   ├── 架构问题.md                 ← ★ 第三方架构审计（阶段 12 的输入台账；顶部有复核结论）
│   └── HANDOFF.md                  ← 本文件
├── knowledge/                      ← 4 份示例知识文档（进版本库）
│   ├── 员工手册.md                  POLICY      964 字  4 个 ## 标题  → 按标题切
│   ├── 公司介绍.md                  COMPANY_PROFILE 717 字 无标题      → 超长降级
│   ├── 公司简介.md                  COMPANY_PROFILE  51 字 无标题      → 整篇一块
│   └── 办公设备管理.txt              POLICY      678 字 无 frontmatter → 段落切
├── tools/hagent-test.ps1           ← UTF-8 安全测试工具（★ 必须带 BOM）
├── frontend/                       ← ★ 阶段 10 新增：Vue 3 + Vite（独立进程，:5173）
│   ├── vite.config.js              ← port 5173 + strictPort（不让它悄悄换端口）
│   ├── src/api.js                  ← 唯一的后端出口（fetch + SSE 解码器 + 身份头）
│   ├── src/App.vue                 ← 状态都在这里（一条事件流喂三个视图）
│   ├── src/style.css               ← 全部样式（白底 + 两种强调色：chat 蓝 / workflow 紫）
│   └── src/components/             ← AppSidebar / TaskPanel（执行轨迹）/
│                                     ThinkingBlock（思考区）/ MessageBubble /
│                                     Composer / MarkdownText（marked + DOMPurify）
├── src/main/java/com/hagent/
│   ├── HagentApplication.java     ← ★ 含 exclude = ChatMemoryAutoConfiguration（阶段 8）
│   ├── common/       BaseResponse / ErrorCode / GlobalResponseBodyAdvice /
│   │                 Texts（按码点截断的唯一实现，阶段 11 抽出复用）
│   ├── exception/    AgentException / GlobalExceptionHandler / FailureMapper / FailureMapping /
│   │                 RetrievalUnavailableException（★ 阶段 10 联调："查不了" ≠ "没查到"）
│   ├── config/       ★ 所有配置/装配类都在这儿（由 PackagePlacementTest 守着）：
│   │                 AiConfig（阶段 8 起几乎空）/ WebConfig / RagConfig /
│   │                 VectorStoreConfig / BusinessDataSourceConfig /
│   │                 MemoryConfig（★ 阶段 11 从 memory 搬来）/ ToolRegistration（同上）/
│   │                 LongTermMemoryConfig（阶段 11）/
│   │                 *Properties ×13（含 MemoryProperties / LongTermMemoryProperties）
│   ├── controller/   ChatController（3 个对话端点）/
│   │                 ConversationController（★ 阶段 10：会话列表 / 历史 / 改名）/
│   │                 HealthController
│   ├── dto/          ChatRequest（+mode）/ ChatResponse（含 reasoning）/
│   │                 SessionDtos（★ 阶段 10：四个嵌套 record 组成一套契约）
│   ├── security/     Role / UserContext / UserTokenResolver / UserContextInterceptor
│   ├── retrieval/    检索侧领域：RetrievedChunk / RelevanceBand / RetrievalFilterBuilder
│   ├── memory/       ConversationMemory 门面 + LayeredConversationMemory（组合两层）│   │                 ├── record/   记录层（权威·完整）：ConversationRecordStore + Mysql/File 实现
│   │                 └── context/  上下文层（有损·可重建）：ConversationContextCache + Redis/NoOp 实现
│   │                 共用：MemoryTrimmer / MessageCodec / TokenBudget /
│   │                       FailureCircuit / LayerStatus / MemoryConfigValidator（唯一例外）
│   │                 ★ 阶段 8 删除：ChatMemoryAdapter、MemoryBudgets
│   │                 ★ 阶段 10 新增：session/（会话目录 ConversationRepository +
│   │                                Mysql/File 实现，与记录层共用同一开关）
│   │                               ReasoningTrace（思考标记格式的唯一出处，写读两侧）
│   │                 ★ 阶段 11 新增：longterm/（长期记忆）
│   │                               LongTermMemoryStore + FileLongTermMemoryStore
│   │                               MemoryEntry（配置类在 config/）
│   ├── document/     入库领域模型（AccessLevel / DocumentKind / DocumentMetadata /
│   │                 SourceLoader / HashingSupport / IngestOutcome 等 9 个）
│   ├── document/chunking/  TextChunker + 三策略 + ChunkingSupport + ChunkingDispatch
│   ├── app/          ★ AgentPipeline（唯一编排者）/ PromptBuilder（唯一拼装者）/
│   │                 PipelineEvent（sealed 契约，阶段 10 新增 StepStarted）/
│   │                 PromptLogFormat / ChatAppService（两条入口）/
│   │                 ConversationService（★ 阶段 10）/
│   │                 KnowledgeSearch /
│   │                 DocumentIngestService / BatchIngestReport
│   │                 ★ 阶段 8 删除：整个 advisor/ 包（8 个类）
│   ├── stream/       StreamEventType（STEP / REASONING / TOOL_CALL / TOOL_RESULT /
│   │                 RETRIEVED / MESSAGE / DONE / ERROR）/ SseSupport
│   ├── tool/         ToolRegistration（唯一装配点）/ KnowledgeRetrievalTool /
│   │                 ★ 阶段 11：LongTermMemoryTools（save/read/forget）/
│   │                 ToolContextSupport（身份键与"缺身份→匿名"的唯一出处）/
│   │                 audit/（ToolInvocationStore + Mysql 实现）
│   └── observability/ StartupProbe（含框架 ChatMemory 启动自检）
├── src/main/resources/prompts/rag-answer-prompt.md   ← 5 条回答约束（缺失即启动失败）
└── src/test/java/com/hagent/       ← 318 个用例，38 个测试类
                                       （另有 1 个抽象契约类 ConversationMemoryContractTest，
                                         由 3 个组合子类继承，本身不出现在报告里）
```

> ★ **包归属的判据（本轮踩坑 #25 换来，下笔前先问）**：
> 本项目同时有两套组织规则 ——
> **按技术角色**（`*Service`→`app`、`*Properties`→`config`、`*Advisor`→`advisor`、
> `*Controller`→`controller`）与**按能力域**（值对象/枚举/能力内部 `@Component` →`document`/`memory`/`retrieval`/`security`）。
> **类的种类有既定归属时，跟种类走；没有时才跟能力域走。**
> 核实手段：`Get-ChildItem -Recurse -Filter '*Service.java'` 看它们都在哪个包。
>
> ★★ **阶段 11 补：这条规则现在有测试守着了**（踩坑 #25 之后<b>又犯了一次</b>，协议第 6 条 → 机制化）：
> **配置类（`*Properties` 与任何 `@Configuration`）一律住 `com.hagent.config`**，
> 由 `PackagePlacementTest` 强制（`src/test/java/com/hagent/config/`）。
> 放错包会直接测试失败，报错里写明该放哪 —— 不用再靠谁记得。
>
> | 规则 | 现状 |
> |---|---|
> | `*Properties` → `config` | **13/13，零例外** |
> | `@Configuration` → `config` | **8/8，零例外**（`MemoryConfig`、`ToolRegistration` 已于阶段 11 末尾归位） |
> | `*Controller` → `controller` | 3/3 |
> | `*Service` → `app` | 6/6 |
> | `*Tool` / `*Tools` → `tool` | 2/2 |
>
> **零例外**是刻意保持的：有过白名单就会有"再加一个也没什么"的下一次。
> 唯一还在原处的是 `MemoryConfigValidator`（全项目唯一的 `EnvironmentPostProcessor`，
> 由 `spring.factories` 按**全限定名**注册，没有既定归属包）—— 它不在上面五条规则里。

---

## 九、踩坑速查（每条都可迁移）

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

---

## 十、新对话的第一句话建议

```
读 docs/HANDOFF.md、docs/WORKING-AGREEMENT.md、docs/架构问题.md（★ 含顶部「复核结论」）。

当前状态：**阶段 13 已完成** —— 工作流 = 先拆解 → 逐步执行 → 两次重试后放弃；
**346 用例全绿**。下一步是 **阶段 14（可观测性与审计）**。

阶段 13 的遗留（已登记、不阻塞）：**前端计划面板未做**（`workflow` 事件已到浏览器，前端忽略它）；
阶段 12 的尾巴同样还挂着（`SessionDtos`→`ConversationDtos`、`IDENTIFIER` 两份、`SafePaths`、
3 个死键、`hagent.memory.chat` 前缀是否改 `conversation`）。

阶段 14 的第一件事：按协议第 1 条走五步，**等明确放行再写代码**。

动手前必须先拍板（问题都在那张表里，逐条给结论，不许替你决定）：
  ③ 死成员删到什么程度（clear/delete 已定：**不删**）？
  ④ 鉴权缺口排到哪个阶段？
  （① 步数上限**已定：迁进 `AgentLoopProperties`，chat 10 / workflow 20 立即生效**；
    ② 守卫路线**已定：引 ArchUnit 1.5.1 + 保留源码扫描** —— 两者理由与实跑证据见 §七）

已定的前提（不要再重新论证）：
  · 这份审计是"**债务台账**"，不是"施工图"：改名类建议（MemoryMode → MemoryBudgetProfile、
    AccessLevel → security）与第 4 批拆分**已明确拒绝**，理由写在 §七
  · 模式只有 chat / workflow；已落库（chat_conversation.mode），**首次确定后不再由请求改变**
  · 思考**永不上送模型**（存 AssistantMessage.properties，不是 reasoningContent —— 后者会被框架回填进请求）
  · 前端只有**一条** SSE 连接；文本载荷是 JSON（`{"text":"…"}`），因为 `data:` 行放不下裸换行（坑 38）
  · 工具状态只到"已返回" —— 没有成功/失败信号，**不画绿勾**
  · **阻塞调用一律在 boundedElastic 上**（`AgentPipeline.loop()` 里的 `publishOn`，坑 35）；
    新增工具时不用管线程，但**别在别处再加一条绕过它的链**
  · 长期记忆：索引常驻注入（≤1000 字）+ 明细按需；三个工具 `saveMemory`(upsert)/`readMemory`/`forgetMemory`；
    密级**复用 `AccessLevel`**
```

### ★ 阶段 13（工作流）的拍板点：**原文留档**（顺延 ≠ 作废）

这四个问题 **全部已有结论**（2026-10-21，阶段 13 已完成；落地口径见 `PROCEDURES` §13.1）：

1. ~~"工作流"到底做什么？~~ ✅ **先拆解、再逐步执行**（"边做边决定"本来就存在于两种模式，不构成区别）
2. ~~拆解结果要不要让用户看见并确认？~~ ✅ **可见但不停下**（`workflow` 事件推计划；不做挂起/恢复）
3. ~~失败/偏离计划时怎么办？~~ ✅ **两次重试就放弃**：同步重试 1 次 → 重规划 1 次 → 停下并写明第 k 步失败
4. ~~步数预算用哪个（chat 10 / workflow 10，要不要分开）~~ ✅ **已拍板：分开** ——
   chat **10** / workflow **20**，随阶段 12 一起落成 `hagent.agent.chat-max-steps` / `workflow-max-steps`。

> ★ 当初第 4 问与阶段 12 的待拍板 ① 是**同一个变量**（`MemoryProperties.maxSteps`）——
> 阶段 12 把 `maxSteps` 迁进 `AgentLoopProperties` 时，顺手就把两档分开写死了。
> 这就是"顺延"该有的样子：**顺延的是任务，不是问题**。

**记住三件最容易被违反的事**：
1. **未获明确放行不写代码**（协议第 1 条）
2. **每个阶段开头要有数据流向图 + 思维导图，并同步进 `PROCEDURES.md`**（协议第 2 条）
3. **结论必须有证据** —— 日志/字节码/原始字节/真实 HTTP，**不许说"应该是这样"**（协议第 3 条）

**再加一条本轮用血换来的（第 4 条）**：
4. **"失败"与"正常"同形的地方，必须有正向断言。**
   本轮 `in` 重载那个坑：过滤条件坏掉 → 返回空列表 → 与"库里没数据"完全一样，
   而所有"匿名只能看到 PUBLIC"的反向断言在空结果上**全部通过**。
   只有那条"员工**必须**检索到 INTERNAL"的正向断言抓住了它。

**第 5 条（同一个坑换了个类型又踩一次）**：
5. **A 类负责抛、B 类负责接时，测 A 的用例和测 B 的用例都通过，而 A→B 那条边没人测。**
   本轮非法 token 报 500 就是这么漏掉的（177 用例全绿）。
   **写测试时要专门问一句：这条链路上有没有"只被两端各自覆盖"的边？**
   另外：踩坑 #10（协议级异常被报成 500）的教训是"按类补分支"，
   本轮因**没把它变成机制**而重演 —— 协议第 6 条要求机制化，这一条目前仍欠着。

**第 6 条（阶段 10 汇报时漏掉的：协议第 2 条的第一段）**：
6. **汇报的第一段必须是【本次改动文件】的完整清单**（新增 / 修改 / 删除）。
   协议第 2 条把它放在第一段，而阶段 10 的汇报**只给了"建议阅读顺序"、跳过了它** ——
   那份汇报读起来很完整，所以漏掉时**看不出来**。
   ⇒ 已机制化：`tools/change-list.ps1`（点号加载后调 `改动清单`）从 git 生成路径，
   **工作区有未提交改动时先警告**（那种情况下清单必然漏文件）。
   判据：**"我列的文件"永远只是"我想到的文件"**；完整清单只能从版本库生成。
