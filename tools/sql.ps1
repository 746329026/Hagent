# ⚠️ 本文件必须保存为【带 BOM 的 UTF-8】！
# 原因：PowerShell 5.1 读取无 BOM 的文件时会按【本地代码页】(GBK/936) 解释，
#       本文件里的中文（注释、输出）会全部乱码，直接导致语法错误无法加载。
# ============================================================================
# 查 hagent-mysql / hagent-redis / pgvector 的查询小工具（UTF-8 与引号安全）
# ============================================================================
#
# 【为什么需要这个文件 —— 一段踩了两次的坑】
#
# 想在容器里跑一条 SQL，最自然的写法是：
#
#     docker exec hagent-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT 1"'
#                                                                          ^^^^^^^^^^^^
#     ❌ PowerShell 5.1 传参时会【吃掉内嵌的双引号】→ 容器里的 sh 收到空参数
#        → mysql 报：ERROR 1064 (42000) at line 1: ... near '' at line 1
#
# 这个报错极具误导性：它读起来像"SQL 写错了"或"MySQL 没起来"，
# 而真实原因是【参数根本没传到】。
#
# ★ 本项目已经因此误判过两次：
#     第一次：把"能跑通的命令"写成 `-e "SQL"` 后失败，查了半天引号；
#     第二次：用它做"容器是否就绪"的探针，得出"MySQL 60 秒内未就绪"的错误结论 ——
#             而容器日志里明明写着 "ready for connections"。
#
# ⇒ 所以这段命令不该由人手写。本文件把它收口成两个函数。
#
# 【做法】SQL 一律走【文件 + 容器内重定向】：
#     ① 本地写一个【无 BOM】的 .sql（带 BOM 会让 mysql 报 Unknown command '\ï'）
#     ② docker cp 进容器
#     ③ sh -c '... < /tmp/x.sql' —— 重定向发生在容器里，PowerShell 完全不参与文本编码
#   两个好处：中文/emoji 不会被编码破坏；也不会有引号被吞的问题。
# ============================================================================

$ErrorActionPreference = 'Stop'

if (-not $Global:HagentMysqlContainer) { $Global:HagentMysqlContainer = 'hagent-mysql' }
if (-not $Global:HagentDatabase)       { $Global:HagentDatabase       = 'hagent' }
if (-not $Global:PgVectorContainer)    { $Global:PgVectorContainer    = 'pgvector' }
if (-not $Global:PgVectorDatabase)     { $Global:PgVectorDatabase     = 'hagent' }

<#
.SYNOPSIS
    在 hagent-mysql 里执行 SQL（可传多行、可带中文与 emoji）。
.EXAMPLE
    查MySQL "SHOW TABLES"
.EXAMPLE
    查MySQL "SELECT id, role, LEFT(content,20) FROM chat_message ORDER BY id DESC LIMIT 5"
#>
function 查MySQL {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [string]$Container = $Global:HagentMysqlContainer,
        [string]$Database  = $Global:HagentDatabase
    )
    执行SQL -Sql $Sql -Container $Container -Database $Database -Client 'mysql'
}

<#
.SYNOPSIS
    在 pgvector（PostgreSQL）里执行 SQL。它与 MySQL 是【同一个库名、不同引擎】，
    所以容器名必须显式区分 —— 这也是本项目踩过的一个坑（SQL 发给了另一个库）。
.EXAMPLE
    查PG "SELECT metadata->>'source', COUNT(*) FROM hagent_knowledge GROUP BY 1"
#>
function 查PG {
    param(
        [Parameter(Mandatory = $true)][string]$Sql,
        [string]$Container = $Global:PgVectorContainer,
        [string]$Database  = $Global:PgVectorDatabase
    )
    执行SQL -Sql $Sql -Container $Container -Database $Database -Client 'psql'
}

<#
.SYNOPSIS
    检查三个依赖容器是否都在运行（跑测试前的第一件事）。
.DESCRIPTION
    ★ 宿主机的休眠或重启会杀掉这些容器，而 Docker 报的 "Exited (...) 几小时前"
    时间可能不准（本项目实测过：刚跑通的容器，报"23 小时前退出"）。
    所以判断依据只看【此刻在不在跑】，不看时间戳。
#>
function 查依赖 {
    param([string[]]$Names = @($Global:PgVectorContainer, $Global:HagentMysqlContainer, 'hagent-redis'))
    $running = @(docker ps --format '{{.Names}}')
    $allGood = $true
    foreach ($n in $Names) {
        if ($running -contains $n) {
            Write-Host "  ✅ $n 在跑" -ForegroundColor Green
        } else {
            Write-Host "  ❌ $n 没在跑" -ForegroundColor Red
            $allGood = $false
        }
    }
    if (-not $allGood) {
        Write-Host ""
        Write-Host "  先执行：docker start $($Names -join ' ')" -ForegroundColor Yellow
        Write-Host "  然后等 MySQL 就绪（看日志里的 'ready for connections'）" -ForegroundColor Yellow
    }
    return $allGood
}

# 内部：文件 + 容器内重定向（见文件头说明）
function 执行SQL {
    param([string]$Sql, [string]$Container, [string]$Database, [string]$Client)

    $sqlFile = Join-Path ([System.IO.Path]::GetTempPath()) ("hagent-sql-" + [guid]::NewGuid().ToString('N') + ".sql")
    # ★ 无 BOM：带 BOM 的 .sql 会让 mysql 报 Unknown command '\ï'
    [System.IO.File]::WriteAllText($sqlFile, $Sql, (New-Object System.Text.UTF8Encoding($false)))

    $inContainer = "/tmp/hagent-sql.sql"
    docker cp $sqlFile "${Container}:${inContainer}" | Out-Null
    Remove-Item $sqlFile -Force -ErrorAction SilentlyContinue

    if ($Client -eq 'mysql') {
        # --default-character-set=utf8mb4：让连接字符集与库一致（注释里有中文时尤其重要）
        docker exec $Container sh -c "MYSQL_PWD=`"`$MYSQL_ROOT_PASSWORD`" mysql --default-character-set=utf8mb4 -uroot $Database < $inContainer"
    } else {
        # PG 用容器的 POSTGRES_PASSWORD；-v ON_ERROR_STOP=1 让出错即返回非 0
        docker exec $Container sh -c "PGPASSWORD=`"`$POSTGRES_PASSWORD`" psql -U postgres -d $Database -v ON_ERROR_STOP=1 -f $inContainer"
    }
    $code = $LASTEXITCODE
    docker exec $Container rm -f $inContainer 2>$null | Out-Null
    if ($code -ne 0) { Write-Host "  （$Client 退出码 $code）" -ForegroundColor Yellow }
    return $code
}

Write-Host ""
Write-Host "SQL 工具已加载（UTF-8 与引号安全）。" -ForegroundColor Green
Write-Host "  可用命令："
Write-Host "    .\tools\sql.ps1 后调用："
Write-Host "      查依赖                                    三个容器是否都在跑"
Write-Host "      查MySQL `"SHOW TABLES`"                     在 hagent-mysql 里执行 SQL"
Write-Host "      查PG    `"SELECT 1`"                        在 pgvector 里执行 SQL"
Write-Host ""
