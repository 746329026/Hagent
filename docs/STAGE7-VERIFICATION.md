# 阶段 7 验收清单（会话记忆双层：MySQL 记录层 + Redis 上下文层）

> **这份清单要本人用真实 `DEEPSEEK_API_KEY` 跑。** AI 不能代跑 —— 它没有 Key。
>
> **为什么必须人工跑**：自动化测试（239 个用例）能证明"装配正确、语义正确"，
> **不能证明"换成 MySQL 之后，用户第二天回来历史还在"** —— 那要跨一次真实的应用重启。
>
> **预计耗时**：10–15 分钟。

---

## ★ 核心 4 项

| 顺序 | 测什么 | 判定标准 | 状态 |
|---|---|---|---|
| 1 | [三](#三核心项-1记忆链路端到端) | 同会话第二轮答出「张三」 | ❌ 待跑 |
| 2 | [四](#四核心项-2重启应用历史仍在mysql-是权威) | **重启应用**后同会话仍答得出 | ❌ 待跑 |
| 3 | [五](#五核心项-3清空-redis-后仍答得出上下文层是可重建的) | **删掉 Redis 里的缓存**后仍答得出 | ❌ 待跑 |
| 4 | [六](#六核心项-4直查表证据) | `chat_message` 里每轮都在，中文/emoji 完好 | ❌ 待跑 |

**第 2、3 项是本阶段的核心主张**：
「**MySQL 是权威、Redis 只是模型上下文的缓存**」—— 这两条分别从"权威持久"与"缓存可丢"两个方向验证它。

---

## 一、前置：配置与启动

### 1.1 配置已改（阶段 7 的破坏性变更）

`hagent.memory.chat.type` **已废弃**。现在是两个键：

```yaml
hagent.memory.chat:
  record-store: mysql      # 对话记录（权威·完整·永久）：mysql | file
  context-cache: redis     # 模型上下文（有损·TTL·可重建）：redis | none
  circuit-break-seconds: 30
```

> ⚠️ **配了旧的 `type` 键会启动失败**并提示改成这两个 —— 这是刻意的（不留兼容层）。
> 若你的 `application-local.yml` 里覆盖过 `type`，先删掉那一行。

### 1.2 基础设施必须在跑（三个容器）

```powershell
docker ps --format '{{.Names}}\t{{.Status}}'
```

必须看到 `hagent-mysql`（3307）、`hagent-redis`（6379）、`pgvector`（5432）。
没起就 `docker start pgvector hagent-mysql hagent-redis`。

**表必须先建好**（DDL 是显式执行的，应用不会自动建表）：

```powershell
docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot -N -e "SHOW TABLES FROM hagent"' 2>$null
```

应当输出 `chat_message`。

> ★★ **本阶段中途改过一次表结构，此前的表要重建。**
> 原设计多一列 `seq`（会话内序号）+ 唯一键；实测发现"无洞序号"必须加锁才能生成，
> 而两种锁方案分别导致**重试耗尽**与**间隙锁死锁**，且 `seq` 提供的属性没有任何消费者
> （顺序由自增 `id` 天然提供）。于是删掉 `seq`，改用 `id` 排序 + `KEY idx_conv_id`。
> 完整论证见 `src/main/resources/db/chat_message.sql` 的注释。
>
> 重建（表为空、无数据损失）：
> ```powershell
> Set-Content -Path 'tmp\drop7.sql' -Value 'DROP TABLE IF EXISTS chat_message;' -Encoding ascii
> docker cp 'tmp\drop7.sql' hagent-mysql:/tmp/drop7.sql
> docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot hagent < /tmp/drop7.sql'
> docker cp 'src\main\resources\db\chat_message.sql' hagent-mysql:/tmp/chat_message.sql
> docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent < /tmp/chat_message.sql'
> ```
> 应用启动时会核对"表在、content 是 utf8mb4、`idx_conv_id` 在"，不符即启动失败。

### 1.3 ★ 先停掉旧实例再启动（新代码才会生效）

```powershell
Get-NetTCPConnection -LocalPort 8123 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
$env:DEEPSEEK_API_KEY = "<你自己的 key>"
Set-Location "C:\Users\Rong\Desktop\实习准备\Hagent"
.\mvnw.cmd spring-boot:run
```

### 1.4 启动日志必须出现这 5 行

```text
[数据源] 业务主库（MySQL）已显式装配：url=jdbc:mysql://localhost:3307/hagent?... pool=HagentMysqlPool
[记忆] 记录层 = mysql（对话记录的权威存储：完整、永久、可审计）
[记忆-记录-mysql] 启动自检通过：表=chat_message content 字符集=utf8mb4
[记忆-上下文-redis] 已启用：keyPrefix=hagent:chat:memory: TTL=24h 最多保留=200条（★ 它是【模型上下文的缓存】，不是权威存储）
[记忆-门面] 已就绪：记录层（权威完整）+ 上下文层（有损可重建），每层熔断窗口=30s
```

> ★ 第一行特别重要：**业务主库的数据源以前根本不存在**（`spring.datasource.*` 整段被静默忽略），
> 阶段 7 才发现并修掉。看不到这行说明修复没生效。

---

## 二、怎么发请求

用项目自带工具（**发送与接收都处理过编码**，阶段 6 修过它的响应解码 bug）：

```powershell
. "C:\Users\Rong\Desktop\实习准备\Hagent\tools\hagent-test.ps1"
```

**★ 一条命令跑完两轮，全程不手工复制 UUID**（手工复制丢过前 8 位，表现成"记忆没生效"）：

```powershell
$r1 = 问 -Message "我叫张三，在一家做企业服务的公司上班"
$cid = [string]$r1.会话ID                 # 强制转字符串
"会话ID = [$cid]  长度=$($cid.Length)"    # 应当是 36
$r2 = 问 -Message "我叫什么？我公司在哪个行业？" -ConversationId $cid
$r2.回复
```

**记下 `$cid`** —— 第 2、3、4 项都要用它。

**盯日志**：

```powershell
Get-Content 'C:\Users\Rong\Desktop\实习准备\Hagent\logs\hagent.log' -Encoding UTF8 -Wait -Tail 20
```

---

## 三、核心项 1：记忆链路端到端

上面那段命令跑完即可。

- **✅ 通过**：`$r2.回复` 里出现 **「张三」**
- 日志同时应有：
  ```text
  [记忆-读] 会话 <cid> 模式=CONVERSATION 注入历史 0 条   ← 第一轮
  [记忆-读] 会话 <cid> 模式=CONVERSATION 注入历史 2 条   ← ★ 第二轮
  [记忆-写] 会话 <cid> 已写回 2 条（问 + 答）
  ```
- 实际结果：____________________

---

## 四、核心项 2：重启应用，历史仍在（MySQL 是权威）

**步骤**：

```powershell
# ① 停掉应用
Get-NetTCPConnection -LocalPort 8123 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# ② 顺便把上下文层也清干净 —— 这样"答得出"就只可能来自 MySQL
docker exec hagent-redis redis-cli KEYS 'hagent:chat:memory:*'
docker exec hagent-redis redis-cli FLUSHDB

# ③ 重新启动（用第 1.3 节那段）
```

启动完成后，**用同一个 `$cid` 再问一次**：

```powershell
问 -Message "我叫什么？" -ConversationId "<第 1 项记下的 cid>"
```

- **✅ 通过**：仍答出 **「张三」**，且日志里 `注入历史` 是 **2 条**
- **❌ 不通过**：说"不知道" → 说明历史只在内存/缓存里，**MySQL 没真正落库**
- 实际结果：____________________

> ★ 这一项把"重启"和"清空 Redis"合在一起做：**两个易失的地方都清掉之后还能答出来**，
> 才真正证明"权威数据在 MySQL"。

---

## 五、核心项 3：清空 Redis 后仍答得出（上下文层是可重建的）

与第 2 项的区别：**这次不重启应用**，只清缓存。

```powershell
# ① 先确认缓存里确实有东西（否则本项证明不了任何事）
docker exec hagent-redis redis-cli KEYS 'hagent:chat:memory:*'
docker exec hagent-redis redis-cli LLEN "hagent:chat:memory:<你的 cid>"

# ② 只删这一个会话的缓存
docker exec hagent-redis redis-cli DEL "hagent:chat:memory:<你的 cid>"

# ③ 再问一次
问 -Message "我叫什么？" -ConversationId "<你的 cid>"
```

- **✅ 通过**：仍答出 **「张三」**
- **同时验证回填**：`LLEN "hagent:chat:memory:<你的 cid>"` 应当又变成 2（读取时从 MySQL 重建并回填）
- 实际结果：____________________

> 本项在自动化测试里也有对应断言：`MysqlRedisContractTest#historySurvivesCacheWipe`
> （它还会先断言"缓存里确实有 2 条"，避免"缓存根本没生效"导致的假通过）。

---

## 六、核心项 4：直查表证据

```powershell
docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent -e "SELECT id, conversation_id, role, LEFT(content, 30) AS content_head, created_at FROM chat_message ORDER BY id DESC LIMIT 10"'
```

- **✅ 通过**：
  1. 能看到刚才那两轮的四条记录（`id` 递增，role 是 USER/ASSISTANT 交替）；
  2. `content` 中文完好，**不是 `?` 或乱码**；
  3. `created_at` 是刚才的时间。
- 实际结果：____________________

**顺带验 emoji / 生僻字**（utf8mb4 的真实检验）：

```powershell
问 -Message "请原样重复这串字符：😀𠮷" -ConversationId "<你的 cid>"
docker exec hagent-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot hagent -e "SELECT HEX(content) FROM chat_message WHERE conversation_id = \"<你的 cid>\" ORDER BY id DESC LIMIT 1"'
```

`HEX` 里应当出现 `F09F9880`（😀 的四字节）与 `F0A0AEB7`（𠮷）。
若被截断成 `3F`（`?`）→ **字符集不是 utf8mb4**，属不可逆问题，要重建表。

---

## 七、顺手验：就绪状态与熔断

### 7.1 就绪状态（`/health/detail`）

```powershell
探测 -Path '/health/detail'
```

body 里应当有：

```json
"memoryLayers": [
  {"layer":"record","implementation":"mysql","reachable":true,"detail":"表 chat_message 可用，content 字符集=utf8mb4"},
  {"layer":"context","implementation":"redis","reachable":true,"detail":"PING=PONG,..."}
],
"memoryReady": true
```

> ★ **依赖状态刻意放在 `/health/detail` 而不是 `/health`**：后者是存活检查，
> 它的定位是"不碰任何外部依赖"。把会真连数据库的探测放进去，会让
> "依赖抖动"被判定成"进程死了"，进而被编排系统反复重启 —— 把小故障放大成雪崩。

### 7.2 熔断（可选，但很能说明设计）

```powershell
docker stop hagent-redis
# 然后连续问两次同一个会话
问 -Message "我叫什么？" -ConversationId "<你的 cid>"
```

- **✅ 通过**：**回答照常**（只是少了缓存），日志出现
  `[记忆] 上下文层读取失败（熔断 30s，其间直接回源记录层）`，
  且**第二次不再有连接等待**（熔断生效）。
- 然后 `docker start hagent-redis` 恢复。
- 实际结果：____________________

> 若不熔断，每一次请求都要白等一次连接超时（Redis 3s）——
> 故障期体验反而比正常时差得多。

---

## 八、把结果告诉我（**不要贴 API Key**）

```text
【核心 4 项】
1  第二轮回复提到「张三」了吗：
   日志里两次「注入历史」分别是几条：
2  重启 + 清空 Redis 后，用同一个 cid 还答得出吗：
3  只删 Redis 缓存后还答得出吗：删完 LLEN 是几？再问之后 LLEN 是几？
4  chat_message 里有几条记录？id 是不是递增、role 是不是 USER/ASSISTANT 交替？中文完好吗？
   emoji 的 HEX 里有 F09F9880 吗：

【启动日志】
5  有「[数据源] 业务主库（MySQL）已显式装配」这行吗：
6  有「[记忆-记录-mysql] 启动自检通过：表=chat_message content 字符集=utf8mb4」吗：

【顺手】
7  /health/detail 的 memoryReady 是什么？两层分别报什么：
8  （可选）停掉 Redis 后还能答吗？日志里有没有「熔断 30s」：
```

**任何一项不符合预期，把相关的 `[记忆-*]` / `[数据源]` 日志行贴给我，我来定位。**
