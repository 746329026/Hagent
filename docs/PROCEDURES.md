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


---

# 阶段 0 补丁 · 代码审查发现的 2 处瑕疵（由本人审查发现）

> 这一批是**自己读代码时发现的问题**，不是跑出来的报错 —— 属于"死配置审查"，
> 比报错更难发现，因为**系统不会提示它**。

## 0.9.1 瑕疵 1 · 死配置残留：MyBatis-Plus 项目里留着 JPA 配置

### 现象

`application-local.yml` 与 `application-local.yml.example` 里有这一段：

```yaml
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
    show-sql: false
```

而本项目**根本没用 JPA**。取证结果：

| 检查 | 结果 |
|---|---|
| pom 里有 `spring-boot-starter-data-jpa` 吗 | 没有 |
| 依赖树里有 hibernate-orm / jakarta.persistence / spring-data-jpa 吗 | 没有 |
| 有任何 Java 代码 import JPA 相关包吗 | 没有 |
| 启动日志里有 Hibernate 痕迹吗 | 没有 |

### 根因

这是**历史残留**：早期版本假设用 JPA 写了这份配置，后来持久层改为 MyBatis-Plus 时
**只改了 pom，没回来清配置**。正是参考实现的那个坑（"死代码/死配置仍在消耗资源与注意力"）。

### 危害（为什么它值得单独记一条）

1. **误导性最强**：后人读到会以为项目用了 JPA，去查 Hibernate 行为，白费时间；
2. **破坏配置可信度**：一份配置里有不生效的项，读者就无法判断"哪些项真的在起作用"；
3. **完全静默**：Spring 不会报"未知属性"，它只是忽略。

### 修复与验证

删除两处配置段，并补上说明性注释（说明为什么没有 JPA 配置、建表方式如何）。

验证方式：`grep` 全项目搜 `jpa:|ddl-auto|open-in-view|show-sql`，
结果只剩注释里的说明文字，无任何实际配置项。

### ⚠️ 修复后必须澄清的一条认知（比清配置更重要）

> **建表方式变了。** `ddl-auto: none` 原本表达的是"不让框架自动改表结构"。
> 换成 MyBatis-Plus 后，**框架根本不会建表** —— 所以建表必须由我们写显式 DDL。
>
> 这不是坏事，而是我们想要的：表结构可评审、可版本化，
> 不会被框架在迁移时悄悄改掉。这条要落到阶段 6 的设计里。

### 附带澄清：`hibernate-validator` ≠ `hibernate-orm`

依赖树里会出现 `org.hibernate.validator:hibernate-validator`，它是
**Jakarta Bean Validation 的实现**（即 `@Valid` / `@NotNull` 的引擎），
与 ORM 的 `hibernate-orm` 是**两个完全不同的项目**，只是名字前缀相同。
`spring-boot-starter-validation` 引入的是前者。

---

## 0.9.2 瑕疵 2 · 可调参数外置不彻底

### 现象

pgvector 数据源有三个池参数，处置不一致：

| 参数 | 原位置 | 问题 |
|---|---|---|
| `maximumPoolSize` | `application.yml` | 已外置，但**没有写取值理由** |
| `minimumIdle` | **只在 Java 源码的字段默认值里** | 真实取值**读配置的人看不到** |
| `connectionTimeout` | **只在 Java 源码的字段默认值里** | 同上 |

### 根因

`VectorStoreDataSourceProperties` 是 `@ConfigurationProperties`，
字段上的默认值让代码"能跑"，但也**掩盖了"真实取值究竟是多少、为什么是这个数"**。
读配置的人看不到这两个参数，想调整得去翻 Java 文件甚至猜属性名。

这与本项目自己立的规矩冲突：`application.yml` 里 agent 线程池的
core/max/queue 全都外置了，唯独这里漏了。

### 修复

1. 把 `minimum-idle` 与 `connection-timeout` 补进 `application.yml`
   的 `hagent.vectorstore.datasource.*`；
2. **把"为什么是这个数"写在配置里**（理由与数值同等重要）：

| 参数 | 值 | 依据 |
|---|---|---|
| `minimum-idle` | 1 | **成本导向**：向量库用量远低于 MySQL，保 1 条常驻连接即可避免每次检索重新建连（建连 = TCP 握手 + PG 认证，是慢操作） |
| `maximum-pool-size` | 5 | **数量级导向**：消费者只有 PgVectorStore 且批量入库本身串行；给 5 够用且不会吃光 PG 的连接数（PG 默认 max_connections=100，要留给其它客户端） |
| `connection-timeout` | 5000ms | **可用性导向，刻意与 MySQL 反着设**：MySQL 主库失败要快速暴露；向量库失败要快速走**降级路径** —— 配合 `initializationFailTimeout(-1)`，PG 断连时用户等 5 秒就收到"知识检索暂不可用"，而不是挂 30 秒 |

3. 在属性类里写清**默认值与真实配置的边界**：

> 字段默认值只是"属性缺省时仍可启动"的兜底，**不是**"把参数藏在这里"。
> 判据：**能被环境改变的 = 配置项**（池大小、超时）；
> **这个数据源固有的 = 代码常量**（驱动类名、池名前缀）。

### 附带澄清：什么叫"外置"，放在哪个文件

这次暴露了一个**沟通失误**：教练说"`maximumPoolSize` 已外置"，本人只在
`application-local.yml` 里找，没找到 —— 因为它写在 `application.yml` 里。

| 文件 | 定位 | 内容 |
|---|---|---|
| `application.yml` | 主配置，**进仓库** | 所有非敏感的公共配置（含上述池参数） |
| `application-local.yml` | 私有配置，**已 gitignore** | 只放含密码/密钥的部分（MySQL 密码、Redis 地址、API Key） |

> **"外置"的准确含义**：把值从 **Java 源码**挪到 **YAML 配置**里 ——
> 从而可以**不改代码、不重新编译**就调整它。
> 至于放哪个 YAML，取决于该值是否敏感。

### 验证

清理后重新编译并启动，**启动探针 8 项仍全部通过**（`allPassed = true`），
证明清理死配置与外置参数未破坏任何行为：

```
ChatModel 实现      : [OK] org.springframework.ai.deepseek.DeepSeekChatModel
EmbeddingModel 实现 : [OK] org.springframework.ai.ollama.OllamaEmbeddingModel
配置的 chat 模型     : [OK] deepseek-flash
DeepSeek API Key    : [OK] 已配置(长度=35)
ChatModel 实调      : [OK] OK(1991ms) 返回模型=deepseek-flash [与配置一致]
Embedding 实测维度   : [OK] 1024 维
Redis 连通性        : [OK] PONG=PONG
pgvector 连通/扩展   : [OK] vector 0.8.6 @ jdbc:postgresql://localhost:5432/hagent
```

另验证：日志中已**不再出现** `Hibernate ORM core version` 等 JPA 痕迹 ——
这在之前的版本里是会打印的，可作为"死依赖确实不在 classpath 上"的佐证。


---

# 阶段 1 · 模型接入与最小对话

## 1.1 WebConfig · CORS 白名单

**目标**：让极简前端（HTML + EventSource 直连 8123）能跨域访问本服务。

**设计决定**：
1. **单独建 `WebConfig`，而不是像参考实现那样叫 `GlobalCorsConfig`**：
   Web 层要配的不止 CORS（后续还有异步超时、拦截器、静态资源）。
   按"类名反映职责边界"的原则，用 WebConfig 收口，避免将来再散出一堆小配置类。
2. **不用 `allowedOriginPatterns("*") + allowCredentials(true)`**：
   参考实现里是这么写的，属于反模式 —— 对全网放开且允许携带凭证。
   本项目改用配置项白名单 `hagent.web.allowed-origins`。
3. **默认值不给"放开一切"**：配置缺失时宁可启动失败，也不要静默地对全网开放。

**验证方式（三向验证，全部实测通过）**：

| 测试 | 结果 |
|---|---|
| 白名单内源 `http://localhost:5500` 的预检请求 | 返回 `Access-Control-Allow-Origin: http://localhost:5500`、`Allow-Credentials: true`、`Expose-Headers: Content-Type, X-Request-Id`、`Max-Age: 3600`、`Vary: Origin` |
| **白名单外源 `http://evil.example.com`** | **HTTP 403 被拒绝**，不返回 `Allow-Origin` |
| 普通请求（无 Origin） | HTTP 200，统一契约正常 |

---

### 坑 14 · `@Value` 无法注入 YAML 列表，导致启动失败

**现象**：`WebConfig` 写完第一次启动直接失败：

```
PlaceholderResolutionException:
Could not resolve placeholder 'hagent.web.allowed-origins'
        in value "${hagent.web.allowed-origins}"
```

**而 `application.yml` 里明明有这段配置。**

**排查过程（顺序很重要：先排除外部因素，再定位到用法本身）**：

| 步骤 | 检查内容 | 结果 |
|---|---|---|
| 1 | YAML 缩进是否正确 | 正确（`web:` 2 空格，`allowed-origins:` 4 空格） |
| 2 | 是否有隐藏字符 | **逐个字符打印码点**，全部正常（无 BOM、无全角空格） |
| 3 | `target/classes/application.yml` 副本 | 配置确实被复制过去了 |
| 4 | 于是定位到 `@Value` 用法本身 | ★ 找到根因 |

**根因**：YAML 里的**列表**在 Spring 的 Environment 中会被展开成**带下标的多条属性**：

```
hagent.web.allowed-origins[0]=http://localhost:5500
hagent.web.allowed-origins[1]=http://127.0.0.1:5500
hagent.web.allowed-origins[2]=http://localhost:8080
```

**不存在名为 `hagent.web.allowed-origins` 的单个属性值。**
而 `@Value` 的占位符解析只做"**按键取值**"，不做"**按结构绑定**"，所以取不到。

**正解**：改用 `@ConfigurationProperties`（它做的是"按结构绑定"，能理解下标），
新建 `WebProperties` 绑定 `hagent.web.*`。
这也与本项目其它配置类（`VectorStoreProperties`、`VectorStoreDataSourceProperties`）保持一致。

> **可迁移认知（很实用的一条）**：
> **`@Value` 适合取标量**（单个字符串 / 数字 / 布尔）；
> **一旦配置是结构化的**（列表、嵌套对象、Map），就应该用 `@ConfigurationProperties`。
>
> 这个坑的特征是"配置明明在文件里，却报取不到" —— 排查时容易被"是不是我 YAML 写错了"
> 带偏方向，浪费大量时间在检查缩进和隐藏字符上。
> **先排除外部（缩进/字符/资源复制），再怀疑用法本身**，是更高效的顺序。

---


---

## 1.2 同步对话链路（阶段 1 核心验收）

**目标**：跑通 controller -> app -> ChatClient -> 模型 一条线，且日志能看到完整链路。

**涉及文件**：

```
dto/ChatRequest.java        请求体 record，仅 message 字段（刻意克制，会话字段留到阶段6）
dto/ChatResponse.java       响应体 record，用对象而非 String（避免 StringHttpMessageConverter 绕过 JSON）
app/ChatAppService.java     app 层，隔离 Controller 与 ChatClient（阶段9换多步循环时 Controller 零改动）
controller/ChatController.java  薄控制器：收参数/调 app/包装返回
advisor/PromptTraceAdvisor.java 调用链日志切面（双接口：CallAdvisor + StreamAdvisor）
config/AdvisorProperties.java   切面参数外置（enabled / max-log-length / log-full-content）
config/AiConfig.java            ChatClient 装配 + 挂载切面
```

**结构决策**：

| 决策 | 理由 |
|---|---|
| DTO 用 record，响应体是对象不是 String | String 会走 StringHttpMessageConverter 绕过 JSON 序列化 |
| app 层隔离 ChatClient | 阶段 9 换多步 Agent 循环时 Controller 零改动 |
| 请求体只放 message | DTO 字段是契约，加了不好删；会话字段属阶段 6，现在加是备而不用 |
| ChatClient 而非 ChatModel | 切面/工具/记忆/结构化输出全挂在 ChatClient 层 |

**切面决策（PromptTraceAdvisor）**：

| 决策 | 理由 |
|---|---|
| 实现 CallAdvisor + StreamAdvisor 两个接口 | 流式要"等流读完再记"，与非流式时序不同，需分开控制 |
| getOrder()=0（最外层） | 当前只有一切面，取值不影响；将来多切面时决定"记到注入前还是注入后" |
| 不依赖 context 传原始输入 | 参考实现用 context("userText") 形成隐式跨切面耦合，拼错/漏注册都静默失效 |
| 日志截断 500 字符 | 参考实现把完整 prompt 打进 INFO，日志暴涨 + 隐私泄露 |

**验收（全部实测通过）**：

| 测试 | 结果 |
|---|---|
| 正常对话 | HTTP 200，`{"code":0,"data":{"reply":"我是深度求索开发的AI助手..."}}` |
| 日志完整链路 | `[PromptTraceAdvisor][call] → 请求 | 消息数=1 | 内容=...` 与 `← 响应 | 耗时=1884ms` |
| 中文往返 | reply 正确显示"收到"（控制台乱码是编码显示问题，HTTP 返回正常） |
| 空消息 | HTTP 400，`{"code":40001,"message":"请求参数不合法: message 消息不能为空"}` |

---

### 坑 15 · 校验异常落回 Spring 默认结构，契约断裂

**现象**：空消息被 @NotBlank 正确拦截（好事），但返回：

```
{"timestamp":"...","status":400,"error":"Bad Request","path":"/api/chat"}
```

这是 Spring 的**默认错误结构**，不是我们的 {code,message,data} 契约。

**根因**：MethodArgumentNotValidException 没有被全局异常处理器显式捕获，
走了 Spring 的默认 HandlerExceptionResolver。

**修复**：GlobalExceptionHandler 增加 @ExceptionHandler(MethodArgumentNotValidException.class)，
把字段错误收集成 detail，返回 BaseResponse.failure(INVALID_PARAM, detail)。

**修复后**：

```
HTTP 400
{"code":40001,"message":"请求参数不合法: message 消息不能为空"}
```

> **可迁移认知**：
> **"参数被拦住了"和"异常被正确格式化"是两件事。**
> 校验注解只负责"拒绝"，不负责"怎么回应"；后者是全局异常处理器的职责。
> 只写校验不写处理，就会出现"拦对了，但返回格式是另一套"的契约断裂。

---


---

## 1.3 流式对话（SSE）+ 放行判定

**目标**：跑通流式对话，且事件名集中管理、放行判定集中一处。

**涉及文件**：

```
stream/StreamEventType.java   事件名唯一出处（MESSAGE / DONE / ERROR + 扩展位注释）
stream/SseSupport.java        放行判定（SseEmitter / Publisher / text-event-stream 三种形态）
app/ChatAppService.java       stream() 方法：SseEmitter 逐片推送
controller/ChatController.java /chat/stream 端点（produces text/event-stream）
```

**结构决策**：

| 决策 | 理由 |
|---|---|
| 用 SseEmitter 而非返回 Flux | SseEmitter"手动"（每个事件明确知道自己在推什么），先理解机制；Flux 留给阶段 3 对比 |
| 事件名用常量类 StreamEventType | 事件名是前后端契约，散落字符串会"靠人工对齐、拼错静默失效" |
| 只定义 3 个事件 + 注释留扩展位 | 提前定义没人发的事件是"备而不用"；加事件成本≈0，不预支 |
| SseSupport 集中放行判定 | 判断漏一条就有一条流式接口静默损坏，必须集中 |
| 放行三种形态而非一种 | Spring MVC 流式有三条路径（SseEmitter / Publisher / text-event-stream），漏一处漏一条 |

**工具调用不会成为事件名**：它是模型内部决策，用户关心的是"做到第几步"（阶段 9 的 step 事件承载）。

**验证（curl 实测，逐片输出）**：

```
event:message
data:我是

event:message
data:Deep

...

event:done
data:[DONE]
```

**过程中踩的坑**：

| 现象 | 根因 | 教训 |
|---|---|---|
| 流式接口 500 | 端口被**上一次的旧进程**占着，curl 打到了旧实例 | 改代码后重启前，先确认端口释放；curl 打到旧实例时先想"我在测的是哪个进程" |
| 中文 body 报"不是合法 JSON" | curl 在 Windows 把命令行里的中文 body 编错了码 | 用 --data-binary @file 传 body，file 用 UTF-8 无 BOM 写 |
| 流式报 401 | API key 已吊销 | key 明文出现在对话 = 已泄露，必须换新 |

**关键认知回顾（贯穿本阶段）**：
统一响应包装 vs 流式返回的冲突 —— 流被包装则格式破坏、前端认不出、服务端可能不报错。
所以必须放行。这个认知在 SseSupport 里落地成集中判定，供统一响应包装器调用。

---


---

# 阶段 2 · 统一契约（响应 / 异常 / 错误码）

## 2.1 统一响应包装器 GlobalResponseBodyAdvice

**目标**：让所有 Controller 返回值自动包成 {code,message,data}，业务代码不再手写包装。

**beforeBodyWrite 的四条分支（顺序不能换）**：

| 顺序 | 分支 | 动作 |
|---|---|---|
| 1 | 放行流式（SseEmitter / Publisher / text-event-stream） | 原样返回，绝不包装 |
| 2 | 已经是 BaseResponse | 原样返回，避免二次包装 |
| 3 | 走 StringHttpMessageConverter | 手动 JSON 序列化 + 改写 Content-Type |
| 4 | 其余 | 包成 BaseResponse.success |

**只包 Controller**：`@RestControllerAdvice(basePackages="com.hagent.controller")`
把作用范围限制住。若不限制，会连 Spring 内部（错误页、静态资源）一起包，破坏它们的输出。

## 2.2 SseSupport 回归测试

为放行判定写了 5 个测试，覆盖三种形态 + 两个边界：

| 测试 | 覆盖 |
|---|---|
| sseEmitterMustBypass | SseEmitter 必须放行 |
| publisherMustBypass | Flux（Publisher）必须放行 |
| eventStreamMediaTypeMustBypass | text/event-stream 必须放行 |
| ordinaryValueMustNotBypass | 普通值不能放行 |
| nullBodyWithNullMediaTypeMustNotBypass | 边界：都为 null 不能放行 |

**为什么给它写测试**：放行判定是"统一响应 vs SSE"冲突的唯一防线，
漏一个分支就有一条流式接口静默损坏。属于"错了不容易发现但很致命"的逻辑。

---

### 坑 16 · Jackson 版本冲突：Spring Boot 4 用的是 Jackson 3

**现象**：编译报错，找不到 `com.fasterxml.jackson.core.JsonProcessingException`
与 `com.fasterxml.jackson.databind.ObjectMapper`。

**排查**：看依赖树发现两种 Jackson 并存：
```
tools.jackson.core:jackson-databind:3.1.5        <- Jackson 3（Spring Boot 4 用的）
tools.jackson.core:jackson-core:3.1.5
com.fasterxml.jackson.core:jackson-annotations:2.21  <- 只有注解还是 Jackson 2 的包名
```

**根因**：**Spring Boot 4.1.1 已迁移到 Jackson 3**，其类在 `tools.jackson.*` 包下
（不再是 `com.fasterxml.jackson.databind`）。而 `com.fasterxml.jackson.annotation`
（注解包）保持不变，所以两种包名会同时出现，容易误判。

**修复**：
```java
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.JacksonException;   // Jackson 3：完整异常层次
```
（`writeValueAsString` 声明抛 `tools.jackson.core.JacksonException`，
它继承 RuntimeException，而 Jackson 2 的 `JsonProcessingException` 是受检异常。）

> **可迁移认知**：**大版本升级会改包名**。
> 编译报"找不到包"时，先确认"这个库在当前版本里是不是换了包名"，
> 而不是怀疑依赖没引进来 —— 后者会让人去加依赖，方向就错了。

---

### 坑 17 · 流式接口的错误不能走"返回 JSON"

**现象**：流式接口出错时，日志出现
```
HttpMessageNotWritableException:
No converter for [class com.hagent.common.BaseResponse]
with preset Content-Type 'text/event-stream'
```

**根因链条**：
1. 流式端点声明 `produces = text/event-stream`，响应格式一开始就被**锁死**；
2. 出错时代码调用 `emitter.completeWithError(error)`，它把异常**抛给 Spring 的异步链路**；
3. 异常落到全局异常处理器，它返回 `BaseResponse`（JSON）；
4. Spring 想把 JSON 写进响应，但 Content-Type 已是 `text/event-stream`，
   **HTTP 不允许中途换格式** → 找不到转换器 → 报错。

**为什么"现在才出现"**：阶段 1 没有全局 `@ExceptionHandler(Exception.class)` 兜底，
流式错误靠 `error` 事件自然处理；阶段 2 引入全局兜底后，
它开始**拦截流式里逃逸出来的异常**，于是把"流式错误"和"普通错误"混为一谈。

**修复**（三路并行，缺一不可）：
| 路 | 动作 | 解决什么 |
|---|---|---|
| 前端 | 推 error 事件 | 用户能看到失败 |
| 服务端 | `log.error` 记录完整堆栈 | 弥补被丢弃的根因 |
| 框架 | `emitter.complete()` 正常收尾 | 避免异常逃逸触发上述错误 |

**为什么不能简单地只把 completeWithError 换成 complete**（这是本人提出的质疑）：
| 潜在问题 | 说明 |
|---|---|
| 框架感知不到失败 | 它当成"正常结束"，将来接入指标时"流式成功率"会虚高 |
| 错误可能半路丢失 | 若 error 事件推送也失败（客户端断开），错误彻底消失 |
| 服务端丢根因 | 异常对象被丢弃，日志里没有"为什么失败"的堆栈 |
| 资源清理路径不同 | 异常清理分支在边界情况下往往更彻底 |

所以补上 `log.error` 与显式计数器：
`streamFailureCount`（AtomicLong）—— 因为框架不感知失败，**失败统计必须自己显式记录**。
这是"用显式替代隐式"：既然框架不认，就自己记。

**验证**（用无效 key 专门触发错误路径）：
```
流式: event:error / data:401 Unauthorized ...     <- SSE 格式正确，无 converter 错误
同步: {"code":50000,"message":"服务内部错误"}        <- 统一契约正确
日志: [流式对话] 失败（累计 1 次）：401 Unauthorized... <- 含完整堆栈
```
并用时间戳确认：`No converter for` 只出现在修复前（16:10 / 16:50），
修复后的验证运行（17:24）**0 次**。

> **可迁移认知**：**流式接口的错误，应该是"流的一部分"**（一个 error 事件），
> 而不是"跳出去"变成 JSON。HTTP 响应一旦提交成某种格式，就改不回来了。

---

### 已知遗留 · 日志噪声（暂不修）

流式失败时日志里会出现大量重复：
```
ERROR o.s.ai.chat.model.MessageAggregator : Aggregation Error
ERROR reactor.core.publisher.Operators : Operator called default onErrorDropped
```
Spring AI 内部的聚合器也在处理同一错误，一次失败会刷出几十行堆栈。
**不是功能问题，是可读性问题**；违背"日志分级、别刷屏"的原则。
计划：等做可观测性阶段统一降噪处理。

---
