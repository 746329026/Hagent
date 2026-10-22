# MCP servers（独立项目）

这个目录里放的是 **MCP server**，每个子目录是一个**独立的 Maven 项目**，
与根目录的主应用（Hagent 本体）**互不依赖**：

```
MCP/
└── time-server/     ← ✅ 本项目只做这一个：stdio，工具 getCurrentTime
```

> ★ **范围说明（2026-10-22 拍板）**：Excel 合并 server 与"其余工具"**已移出本项目范围**
> （不是延期）。保留这一段是记录"为什么没做"，而不是留一个待办。

## 为什么它们是独立项目（不是主应用里的几个类）

| 理由 | 说明 |
|---|---|
| **主应用只做客户端** | 把 server starter 放进主应用，主应用自己也会对外提供 MCP 服务（多一个协议端点、多一份未鉴权面） |
| **边界清晰** | server 看不到主应用的知识库 / 会话 / 长期记忆 / 数据库 |
| **可替换** | 换语言、换实现只改 `application-mcp.yml` 里的一行命令 |
| **构建解耦** | server 的依赖（POI、MCP server starter）不会污染主应用 classpath |

## 构建与运行

```powershell
# 1) 构建 server（★ 必须先做：主应用的构建不会构建它们）
.\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml package

# 2) 起主应用并激活 mcp profile（不激活则外部工具不可用 —— 会有 WARN 明确提示）
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local,mcp"

# 3) 不经过模型、直接验证 MCP 协议（握手 / 工具列表 / 调用 / stdout 纯净）
node tools\mcp-probe.mjs
```

> ★ 忘了第 1 步时：主应用**3 秒内启动失败**，并直接告诉你缺哪个 jar、该执行哪条构建命令
> （由 `McpServerPreflight` 在 Bean 创建前检查）。
> ★ 忘了第 2 步的 `mcp` 时：应用照常启动，但启动日志会有一条 **WARN** 说明"外部工具不可用"。

## ★ 不需要"第二个 IDE 窗口 / 第二个 run 配置"

stdio 形态下，**server 不是你去跑的独立应用** —— 主应用在连上时会 `java -jar` **拉起它作为子进程**：

```
你只 run 主应用（一个窗口、一个 Run Configuration，profiles = local,mcp）
      └─ 启动时 fork 出  java -jar MCP/time-server/target/time-server-….jar
            └─ 主应用退出 / 崩溃时，子进程随之结束
```

server 的日志会以 `STDERR Message received: …` 的形式出现在**主应用的同一个控制台**里。

### ⚠️ 唯一的坑：改了 server 代码必须**重新 package**

客户端跑的是**已经打好的 jar**，不是 `target/classes`、更不是源码。
所以"改了 `TimeTools.java` → 重启主应用 → 行为没变"的根因往往只是**忘了 `package`** ——
而这个现象极容易被误判成"配置没生效"或"代码没写对"。

**改动后请固定执行**：

```powershell
.\mvnw.cmd -B -ntp -f MCP/time-server/pom.xml package   # 重新打包
node tools\mcp-probe.mjs                                 # 顺手确认 server 行为已变（不经过模型）
# 再重启主应用
```

> 如果将来觉得"每次改都要手动打包"麻烦，可以把传输方式换成 **HTTP**
> （`MCP/time-server` 改用 `spring-ai-starter-mcp-server-webmvc`，主应用 `application-mcp.yml`
> 换成 `sse` / `streamable-http` 连接）。**那时才需要**两个 run 配置、两个端口分别启动。
> 代价是多了进程管理，收益是"改完 server 重启它自己即可"。

## 在 IntelliJ IDEA 里打开

**症状**：打开本目录下的 `.java` 时提示
`Java file is located outside of the module source root, so it will not be compiled`。

**原因**：根目录的 Maven 项目把整个 `Hagent/` 当作自己的 content root，
而本目录是**另一个** Maven 项目 —— IDEA 不知道这些源码属于谁。

**处置（二选一，都是一次性操作）**：

1. ★ **推荐**：右键 `MCP/time-server/pom.xml` → **Add as Maven Project**
   （或 Maven 工具窗格 → `+` → 选这个 pom）。之后它成为一个独立模块，提示消失、可导航可编译。
2. 只想让提示消失：Project Structure → Modules → 根模块 → Sources → 把 `MCP` 标记为 **Excluded**。
   代价是本目录在 IDEA 里不参与编译（但命令行构建照旧有效）。

> `.idea/` 在 `.gitignore` 里，所以这一步是**每个 clone 各做一次**，不写进仓库。

## 约束（改代码前先看）

- **stdout 是协议通道**：json-rpc 走 stdin/stdout。任何写进 stdout 的日志/banner 都会破坏协议，
  症状是"进程活着但客户端一个工具都看不到"（两侧都觉得自己正常）。
  ⇒ 日志一律走 stderr，见 `time-server/src/main/resources/logback-spring.xml`。
- **不要往这里放业务数据访问**：server 是"外部工具"，不该碰主应用的库；要碰就该是主应用自己的工具。
- **工具名由 server 决定**，但主应用会给它加前缀 `mcp_<server>_<tool>`（防撞名、可追溯）。
