# Hagent 开发过程记录（PROCEDURES）

> 本文件仿照参考实现 kewei-ai-agent 的 `PROCEDURES.md` 而建。
> **它本身是作品的一部分**：面试问"你遇到过什么困难、怎么定位的"，答案全在这里。

---

# 阶段 0 · 准备工作

**开始时间**：2026-09-20
**目标**：建立可构建、可启动、可验证的工程骨架；把"环境是否真的可用"变成有证据的结论。

---

## 0.1 环境审计结果（实测，非假设）

| 依赖 | 实测结果 | 处理 |
|---|---|---|
| JDK | `JAVA_HOME` 指向 Temurin 21.0.12.1 | 无需处理 |
| **Git** | 已装在 `D:\Git`（2.51.0），但**用户级 PATH 无此目录** | 追加 `D:\Git\cmd` 到用户 PATH |
| **Maven** | **无全局 `mvn`** | 复制 kewei 的 Maven Wrapper（`only-script` 模式） |
| Maven 本地仓库 | 由 `~/.m2/settings.xml` 指定为 **`D:\devZgq\Java\.m2\repository`** | 排查依赖问题要看这里，不是默认路径 |
| Docker | 28.4.0 | — |
| **Docker Hub** | `registry-1.docker.io` **连接超时** | 复用本地已有镜像：`valkey/valkey:8`、`mysql:8.0.39` |
| **Redis** | 6379 未监听 | 用本地 valkey 镜像起 `hagent-redis`（协议与 Redis 完全兼容） |
| MySQL | 3306 被宿主机原生 mysqld 占用 | **另起项目专属容器 `hagent-mysql`（3307）**，不碰本机环境 |
| pgvector | `pgvector/pgvector:pg16` 已运行 | 新建独立库 `hagent` + `CREATE EXTENSION vector`（0.8.6） |
| Ollama | 0.33.2，已装 `mxbai-embed-large`(1024维) | embedding 用它 |

**数据库归属决策**：MySQL 用项目专属容器（3307）。理由：项目自包含（换机器一条 `docker run`）、不污染其它项目、版本固定可复现。

---

## 0.2 版本决策

| 依赖 | 参考实现 kewei | **Hagent** | 理由 |
|---|---|---|---|
| `spring-boot-starter-parent` | 4.0.3 | **4.1.1** | 最新 GA |
| `spring-ai-bom` | 2.0.0-M2（**里程碑版**） | **2.0.1** | 避雷：不用里程碑版 |
| mybatis-plus | 3.5.16 | **3.5.17** | 最新 |
| poi-ooxml | 5.4.1 | 5.4.1 | — |

**实测生效版本矩阵**：Java 21.0.12.1 · Spring Boot 4.1.1 · Spring Framework 7.0.9 · Tomcat 11.0.24 · Hibernate 7.4.5 · mysql-connector-j 9.7.0 · HikariCP 7.0.2 · PostgreSQL 16.15 · pgvector 0.8.6 · Maven 3.9.12

> **避坑手法**：Maven Central 的 Solr 搜索接口（`search.maven.org/solrsearch`）**索引滞后**，查 `spring-ai-bom` 只返回 1.0.0（2025-05），与实际严重不符。
> 可靠做法：**直查仓库目录** `https://repo1.maven.org/maven2/org/springframework/ai/spring-ai-bom/`。
> 经验：**版本事实以仓库目录或官方发布公告为准，不能信搜索接口。**

---

## 0.3 踩坑记录

### 坑 1 · Docker Hub 不可达

**现象**：`docker run redis:7-alpine` 超时，`registry-1.docker.io` 连接失败。
**解决**：`docker images` 先看本地有什么 —— 发现已有 `valkey/valkey:8`（协议兼容 Redis）与 `mysql:8.0.39`，直接复用，无需下载。

> **可迁移认知**：国内拉不动镜像时，**复用本地已有且协议兼容的镜像**往往比配镜像加速更快。

### 坑 2 · JPA 会在启动时急切连接数据库

**现象**：配置里写了占位密码，启动直接失败：
```
WARN  HHH000247: ErrorCode: 1045, SQLState: 28000
ERROR Failed to initialize JPA EntityManagerFactory: Unable to determine Dialect without JDBC metadata
```
**错误假设**：以为"我还没用到数据库，它不会去连"。
**真相**：只要引入 `spring-boot-starter-data-jpa`，Spring Boot 就会创建 `DataSource` 并初始化 `EntityManagerFactory`，HikariCP 默认 `failFast` 立刻连库校验。

> **可迁移认知**：**加一个 starter 就等于加了一整条启动链路**。"我还没用到它" 不等于 "它不会在启动时干活"。

### 坑 3 · 两个模型 starter 同时存在导致 ChatModel Bean 冲突

**现象**：
```
expected single matching bean but found 2: deepSeekChatModel, ollamaChatModel
```
**错误假设**：以为 `spring.ai.ollama.init.chat.pull-model-strategy: never` 能让 Ollama 不参与 chat。**错** —— 该配置只阻止"自动拉取模型文件"，**不阻止创建 `ChatModel` Bean**。

**正解**：显式声明能力归属
```yaml
spring.ai.model:
  chat: deepseek
  embedding: ollama
```
**为什么不用 `@Primary`**：`@Primary` 只是让冲突消失，**没有表达"chat 用云端、embedding 用本地"这个设计意图**。

> **可迁移认知**：**"让报错消失"和"把设计意图写进配置"是两件事。** 前者是打补丁，后者是立契约。

### 坑 4 · pgvector 自动装配的 DataSource 归属（隐蔽性最高）

**现象**：启动日志出现 `PgVectorStore : Using the vector table name: vector_store. Is empty: false`，但项目配的 `hagent` 库里一张表都没有 —— 它读到的是**另一个库**里的同名表。

**根因（读 jar 内元数据实测得出，非猜测）**：解压 `spring-ai-autoconfigure-vector-store-pgvector-2.0.1.jar`，读 `META-INF/spring-configuration-metadata.json`，`spring.ai.vectorstore.pgvector.*` 下**只有** table-name / schema-name / dimensions / distance-type / index-type / id-type / initialize-schema 等，**没有任何数据源参数** —— 它复用 Spring 全局 `DataSource`。

**解法**：
1. 自定义 `pgVectorDataSource`（前缀 `hagent.vectorstore.datasource.*`，**不标 `@Primary`**，保留 MySQL 作为业务主库），并 `initializationFailTimeout=-1`（向量库不可用不阻断启动）；
2. **显式**声明 `PgVectorStore` Bean（传入自己的 `JdbcTemplate` + `EmbeddingModel`），不依赖自动装配的"二选一"逻辑；
3. 表名改为 `hagent_knowledge`，在**名字层面**与其它项目隔离。

> **可迁移认知**：**自动装配的"便利"与"不确定性"是一体两面。** 涉及数据归属的地方，宁可显式装配 —— "显式"本身就是可维护性。

### 坑 5 · @EnableAutoConfiguration 会主动创建 Bean 并导致启动失败

**现象**：做组件扫描实验时，一个只想看"扫到哪些 Bean"的极简程序启动失败：
```
Failed to instantiate [com.zaxxer.hikari.HikariDataSource]: Failed to determine a suitable driver class
```
排除数据源后，又变成：
```
Failed to instantiate [DeepSeekChatModel]: DeepSeek API key must be set
  at DeepSeekChatAutoConfiguration.deepSeekApi(...)
```
**根因**：`@EnableAutoConfiguration` **主动**创建 Bean，某些 Bean 创建失败会**拖垮整个应用启动** —— 即使当时根本不需要那个能力。

> **可迁移认知**：**自动装配不是"用到才创建"，而是"启动时就尝试创建"。** 这解释了为什么"配好密钥"是启动前置条件，而不是"调用模型时才需要"。

### 坑 6 · 非 UTF-8 编码导致的文件损坏（★ 教练自己的失误）

**现象**：pom.xml 的中文注释全部变成乱码，而**构建照常成功**。
**根因**：用下面命令改文件，造成"读错编码 → 写回正确编码"的**不可逆双重转换**：
```powershell
$pom = Get-Content "pom.xml" -Raw    # 按 GBK 读入，中文已损坏
Set-Content "pom.xml" -Value $pom    # 按 UTF-8 写回，损坏被固化
```
**ASCII 部分完全没受影响**，所以 Maven 照常构建成功 —— 乱码只存在于注释里，而注释不影响构建。

**修复**：用固定 UTF-8 的方式完整重写，并补上标准防线：
```xml
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
<project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
```

> **可迁移认知（很值钱）**：**"构建成功"不等于"文件正确"。**
> 乱码躲过了所有自动检查：编译器不看注释、Maven 不看注释、测试不看注释 —— **只有肉眼能发现**。
> 三条防线要同时上：
> | 防线 | 作用 |
> |---|---|
> | `project.build.sourceEncoding=UTF-8` | 告诉 Maven 源码编码 |
> | 编辑器显示编码设为 UTF-8 | 让你**看得见**乱码 |
> | **任何"读文件→改→写回"都必须显式指定编码** | 防静默损坏 |

### 坑 7 · XML 注释里不允许连续两个连字符

**现象**：`Non-parseable POM: in comment after two dashes (--) next character must be > not -`
**原因**：注释分隔线写成 `<!-- ---- 5.1 Web 服务 ---- -->`，而 XML 规范不允许注释内出现 `--`，导致 Maven 无法解析整个文件。
**修复**：所有区域改用 `=====` 分隔。

> **可迁移认知**：XML 注释比 HTML 严格得多。**"看起来只是注释"的东西也能让整个构建失败** —— 所以改完 pom 必须真跑一次构建。

---

## 0.4 关键实验记录

### 实验 A · Lombok 注解处理器（三步对照）

**目的**：搞清 `annotationProcessorPaths` 到底有什么用。

| 步骤 | 配置 | 结果 |
|---|---|---|
| 1 | 有 `annotationProcessorPaths` | 编译成功；`javap` 显示产物里有 `log` 字段与 `getName()` 方法 |
| 2 | **完全无配置** | **编译仍成功**，但 javac 打印"注解处理未显式请求"告警 |
| 3 | 加 `<proc>none</proc>` | **编译失败**：找不到符号 `log`、找不到符号 `getName()` |

**注意**：第 2 步结果与教练最初的断言不符（教练说会编译失败，实际只是告警）—— **这正说明"结论必须验证"，包括对教练的断言**。

**结论**：`annotationProcessorPaths` 的作用**不是"能不能编译"，而是"把这件事从碰巧变成保证"**。不写时依赖 javac 扫描 classpath，只是**碰巧能用**；写了才是明确声明"这些 jar 是拿去生成代码的，不是拿去运行的"（JSR-269），且版本锁定。

**验证方式**：`javap -p target/classes/.../LombokProbe.class` 直接看编译产物。

### 实验 B · @ComponentScan 的扫描范围

**目的**：证明"启动类必须放在根包"不是教条。**方法**：在 `com.hagent.app` 放一个 `@Service`，只改变启动类位置。

| 启动类位置 | 结果 |
|---|---|
| `com.hagent`（根包） | `ScanProbeService : FOUND (beanName=scanProbeService)` |
| `com.hagent.controller`（子包） | `ScanProbeService : NOT FOUND (outside scan scope)` |

**唯一变量是启动类位置，结果相反。** 第二次连"被实例化了"的日志都没有 —— 它根本没被创建。

**这个错误的报错具有迷惑性**：它不说"扫描范围不对"，而是说 `required a bean of type '...' that could not be found`，让人先去怀疑"是不是 @Service 忘了写"。

---

## 0.5 涉及文件

```
Hagent/
├── pom.xml                                    依赖清单（注释含每个决定的理由）
├── .gitignore                                 排除密钥与运行时产物
├── mvnw / mvnw.cmd / .mvn/wrapper/...         Maven Wrapper
├── docs/PROCEDURES.md                         本文件
└── src/main/java/com/hagent/
    ├── HagentApplication.java                 启动类（含扫描范围说明）
    └── app/HealthAppService.java              app 层第一个类（占位）
```

---

## 0.6 阶段结果

| 验收项 | 状态 |
|---|---|
| Maven 工程可构建 | 完成（BUILD SUCCESS，5.9s） |
| 依赖解析正确 | 完成（14 个直接依赖；传递依赖因果链已实测） |
| 编码规范统一 | 完成（全部源文件无 BOM，UTF-8） |
| 基础设施就绪 | 完成（hagent-mysql 3307 / hagent-redis 6379 / pgvector hagent 库） |
| 空骨架能启动 | **阻塞：需配置 DeepSeek API Key** |
| 健康检查通 | 待启动类与 web 层就位 |
| 首个 git commit | 待阶段收尾 |

---

## 0.7 遗留项

| # | 遗留项 | 计划 |
|---|---|---|
| 1 | DeepSeek API Key 未配置导致**应用无法启动** | 待提供（坑 5 是根因） |
| 2 | 启动探针（StartupProbe）未重写 | 待密钥就绪 |
| 3 | 配置分层（application.yml / application-local.yml） | 待写 |
| 4 | 向量表 `hagent_knowledge` 未建 | 阶段 7 显式 DDL 创建 |
| 5 | 业务表未建 | 阶段 6 |
| 6 | 回归测试为空（含架构约束测试） | 阶段 2 起补 |


---

# 阶段 0 补充 · 真实联调后暴露的 5 个新坑

> 配置好 DeepSeek 密钥后，第一次真实启动联调又暴露了 5 个问题。
> 这一批比之前的更值钱 —— 因为它们全部属于"**配了但没生效**"类型。

## 0.8.1 实测确认的模型清单（★ 修正了原先的假设）

调用 `GET /models` 得到该账号**实际可用**的模型只有两个：

```
deepseek-flash
deepseek-v4-pro
```

**假设中的模型名全部不存在**：`deepseek-chat`、`deepseek-reasoner` 都不在列表里。

### 坑 8 · 请求不存在的模型名不会报错，而是"静默降级"

**实测现象**：

```
请求 model=deepseek-reasoner
响应 model=deepseek-flash     ← 换了模型，但 HTTP 200、无任何警告
```

**危害**：你以为在用某个强模型，实际在用另一个；账单价、能力上限、上下文长度全都对不上，
而**系统不报错**。这与"工具名硬编码在域白名单里靠人工对齐"是同一类病。

**防线**：启动探针必须核对"**响应里实际返回的 model 字段**"，而不只是看"我配置了什么"。
本项目已在探针中实现该核对，输出形如：

```
ChatModel 实调 : [OK] OK(1304ms) 返回模型=deepseek-flash [与配置一致]
```

模型名一旦配错，这一项会变成 `[!! 与配置不一致，可能发生了静默降级]`。

**模型选择理由（记录在案）**：选用 `deepseek-flash`。它本身就是**推理模型**
（响应里带 `reasoning_content` 字段与 `reasoning_tokens` 计数）。
多步 Agent 每一步都要重新决策，token 消耗会被放大，所以"单步便宜"比"单步最强"更重要。

### 坑 9 · `reasoning_effort` 大小写敏感，大写直接 422

**实测对照**：

| 发送值 | 结果 |
|---|---|
| `"high"` | 接受，reasoning_tokens 从基准 28 升到 44 |
| `"HIGH"` | **HTTP 422 Unprocessable Entity** |

而 Spring AI 的枚举字面量是**大写**的 `HIGH` / `MAX` —— 那它序列化出去是哪个？

**用 `javap -v` 读字节码常量池得到确定答案**：

```
#39 = Utf8  high
#40 = Utf8  max
RuntimeVisibleAnnotations: com.fasterxml.jackson.annotation.JsonProperty("high")   <- HIGH 常量
RuntimeVisibleAnnotations: com.fasterxml.jackson.annotation.JsonProperty("max")    <- MAX 常量
```

即 `ReasoningEffort.HIGH` 带 `@JsonProperty("high")`，**序列化出去是小写，与 API 期望一致**。
配置写成 `reasoning-effort: HIGH` 是安全的（Spring 松散绑定也接受小写 `high`）。

> **可迁移认知**："字段名/枚举值的大小写约定"这类问题不能靠猜。
> 字节码常量池、官方 Javadoc、真实 HTTP 往返 —— 三者任一都能给出确定答案。

### 坑 10 · 推理模式下 `temperature` 被静默忽略

**启动日志原文**：

```
WARN DeepSeekChatModel : Thinking mode does not support the temperature parameter(s).
     Please note that, for compatibility with existing software, setting these parameters
     will not trigger an error but will also have no effect.
```

**注意措辞**："will not trigger an error but will also have no effect" ——
**配了不报错，但完全不生效**。这比直接报错更危险：你会以为自己在控制随机性，实际什么都没控制。

**处理**：配置里**刻意不设 temperature**，并写明原因。两条路可选 ——
要么不配（承认它不生效），要么显式关掉 thinking 才配。
本项目选前者：Agent 的稳定性靠"推理能力 + 结构化输出约束"保证，而不是靠一个其实被忽略的参数。

### 坑 11 · `PgVectorStore` 手写 builder 时，配置项会被完全忽略（★ 最隐蔽）

**实测现象**：配置文件写了 `table-name: hagent_knowledge`，启动日志却是：

```
PgVectorStore : Using the vector table name: vector_store
```

**根因**：`spring.ai.vectorstore.pgvector.*` 这些属性是给**自动装配**用的
（绑定到官方 `PgVectorStoreProperties`）。本项目选择了**显式装配**（原因见坑 4），
于是属性类根本不会被创建、配置无人读取，而 `PgVectorStore.builder(...)` 是一个"裸"构建器，
它不会自己去读那些配置。

**更麻烦的是失败方式**：`initializeSchema` 默认为 true，它会**自动建一张叫 `vector_store` 的表**，
于是"配置没生效"完全不报错 —— 数据写进了另一张计划外的表。

**修复（两步，缺一不可）**：
1. 自己写 `VectorStoreProperties` 绑定 `spring.ai.vectorstore.pgvector.*`，
   并把 tableName / schemaName / dimensions / indexType / distanceType /
   initializeSchema / maxDocumentBatchSize 全部传给 builder；
2. **加启动自检**：直查 `information_schema.tables` 确认"实际存在的表"是否等于"配置的表名"，
   不一致或不存在时明确告警 —— 用**事实**而不是配置来确认。

**修复后的验证证据**：

```
[向量库] 装配 PgVectorStore：table=hagent_knowledge schema=public dimensions=1024 index=HNSW distance=COSINE_DISTANCE
PgVectorStore : Using the vector table name: hagent_knowledge      <- 一致了
[向量库] 表 'hagent_knowledge' 尚不存在（schema=public）—— 阶段 7 会用显式 DDL 创建
```

数据库直查确认 `hagent` 库 `public` schema 下**0 张表**（不再自动建表）：

```sql
SELECT table_name FROM information_schema.tables WHERE table_schema='public';
-- (0 rows)
```

> **可迁移认知**：**选择"显式装配"就要自己承担配置绑定**。
> 获得可控性的代价，是失去自动装配附带的那些便利 —— 这笔账要提前算清楚。
> 而"配置写了却没被读取"这类问题，必须靠**启动自检**兜住，因为它不会自己报错。

### 坑 12 · `-Xlint:processing` 是无用噪声（已撤销）

为了验证"注解处理是否被隐式请求"，给编译插件加了 `-Xlint:processing`。
结果它把**所有"没有处理器认领的注解"**都报了出来：

```
未由任何处理器处理: /org.springframework.context.annotation.Bean,
/org.springframework.web.bind.annotation.RestController, ... 共 15 个
```

**结论**：javac 的这个告警**无法区分**"隐式注解处理"与"普通注解无处理器"，
作为"配置是否生效"的断言不合格。已撤销，保留先前实测有效的
`annotationProcessorPaths` 显式声明即可。

> **可迁移认知**：**告警不等于信号**。加一个检查之前先确认它能否区分"我们关心的那件事"；
> 否则只是给日志增加噪声，反而降低真实异常的可见度。

---

## 0.8.2 阶段 0 最终结果

**空骨架能启动** —— 已完成：

```
Started HagentApplication in 2.012 seconds
```

**探针全部通过**（`/api/health/detail` 实测输出）：

| 探测项 | 结果 |
|---|---|
| ChatModel 实现 | `[OK] org.springframework.ai.deepseek.DeepSeekChatModel` |
| EmbeddingModel 实现 | `[OK] org.springframework.ai.ollama.OllamaEmbeddingModel` |
| 配置的 chat 模型 | `[OK] deepseek-flash` |
| DeepSeek API Key | `[OK] 已配置(长度=35)` |
| ChatModel 实调 | `[OK] OK(1304ms) 返回模型=deepseek-flash [与配置一致]` |
| Embedding 实测维度 | `[OK] 1024 维 (86ms)` |
| Redis 连通性 | `[OK] PONG=PONG` |
| pgvector 连通/扩展 | `[OK] vector 0.8.6 @ jdbc:postgresql://localhost:5432/hagent` |

**统一契约**（实测响应）：

```json
{"code":0,"message":"success","data":{"status":"UP","application":"hagent", ...}}
```

（`isSuccess()` 曾被 Jackson 当成属性序列化出多余的 `"success":true`，
已用 `@JsonIgnore` 修正 —— 这类"多出字段"不报错，只会让前端与契约悄悄漂移。）

**遗留（已知且可接受）**：

| # | 遗留 | 计划 |
|---|---|---|
| 1 | `No MyBatis mapper was found in '[com.hagent]'` 告警 | 阶段 6 建实体与 Mapper 后自然消失 |
| 2 | 向量表 `hagent_knowledge` 未建（`initialize-schema: false` 是刻意的） | 阶段 7 用显式 DDL 创建 |
| 3 | 业务表未建 | 阶段 6 |
| 4 | AsyncConfig / WebConfig 未写 | 阶段 3 / 9 |
| 5 | 回归测试为空（含架构约束测试） | 阶段 2 起补 |

## 0.8.3 环境补充（本机特有，避免重复踩）

| 事实 | 说明 |
|---|---|
| **PowerShell 5.1** | `Set-Content -Encoding UTF8` **会加 BOM**。写无 BOM 文件必须用 `[System.IO.File]::WriteAllText($p,$s,[System.Text.UTF8Encoding]::new($false))` |
| **Python 3.8.10** | f-string 不能含反斜杠；`Path.write_text()` 不支持 `newline` 参数 |
| **控制台代码页 936** | 中文日志/输出在终端会显示为乱码，但**文件与 HTTP 返回都是正常 UTF-8**，不要误判为编码损坏 |
