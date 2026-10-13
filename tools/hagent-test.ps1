# ⚠️ 本文件必须保存为【带 BOM 的 UTF-8】！
# 原因：PowerShell 5.1 读取无 BOM 的文件时会按【本地代码页】(GBK/936) 解释，
#       本文件里的中文（函数名、注释、输出）会全部乱码，直接导致语法错误无法加载。
#       ★ 注意这与 Java 源码的规则相反：.java 文件要无 BOM，.ps1 文件必须有 BOM。
# 若用编辑器保存后脚本报"意外的标记"之类语法错，先检查 BOM。
# ============================================================================
# Hagent 手工测试小工具（UTF-8 安全）
# ============================================================================
#
# 【为什么需要这个文件】
# PowerShell 5.1 的 Invoke-WebRequest 有一个真实陷阱：
#
#     Invoke-WebRequest -Body '{...中文...}'      # ❌ 中文会变成 ????
#
# 原因：PS 5.1 传字符串时不会自动按 UTF-8 编码。当 Content-Type 没带 charset 时，
# 它用 ISO-8859-1 编码 —— 而该编码表里没有中文字符，于是逐字符替换成 '?'
# （ASCII 63）。服务端收到的是 ???????，模型会回答"你的消息出现了乱码"。
#
# 这个坑的隐蔽性在于：服务端完全正常、探针全绿、HTTP 200/502 都对，
# 只有【请求内容】是错的 —— 排查时极易误判成"模型中文支持有问题"。
#
# 实测证据（服务端 PromptTraceAdvisor 记录）：
#     错误写法 -> 内容=????
#     正确写法 -> 内容=我叫张三
#
# 【怎么用】
#     开一个新的 PowerShell 窗口：
#         . "$HOME\Desktop\实习准备\Hagent\tools\hagent-test.ps1"
#     然后就可以调用下面这些函数了。
#
#     注意开头的那个「点 + 空格」——表示把本文件加载进当前会话。
#     不加点的话函数会在子作用域里定义，调用时找不到。
#
# ============================================================================
# 【★ 响应方向的同款陷阱（阶段 6 验收时发现并修掉）】
#
# 上面讲的是【发送】方向：没有 charset 时按 ISO-8859-1 编码。
# 【接收】方向有同一个默认值，而且长期没被发现：
#
#     服务端响应头是 application/json（★ 没有 charset，实测）
#     Invoke-WebRequest 在缺 charset 时按 ISO-8859-1 解码响应体
#     → $resp.Content 里的中文全是乱码
#
# 为什么长期没被发现：【原始字节是对的】，服务端日志也是对的，而验证时大家都是
# "看服务端日志"（下一节就是这么写的）—— 没人去读函数返回的「回复」字段。
# 这正是 WORKING-AGREEMENT 第 3 条记过的坑："把【发出去了】误当成【能看到】"。
#
# 实测证据（同一份 125 字节的响应体，两种读法）：
#     $resp.Content | ConvertFrom-Json              →  æ ¹æ®ãåå·¥æå...   ❌
#     UTF8.GetString($resp.RawContentStream 的字节)  →  根据《员工手册》第二章… ✅
#
# 所以本文件里【读响应体一律走 读响应体 / 读错误响应体 两个函数】：
# 拿原始字节、显式按 UTF-8 解码，绝不依赖框架的自动解码。
# 自己写测试代码时也请照这个来。
# ============================================================================
#
# 【关于控制台显示中文乱码】
# 你的控制台是 936 代码页，显示中文可能乱码 —— 那只是「显示」问题，
# 不影响实际发送的内容。要确认发送是否正确，看服务端日志的
# [PromptTraceAdvisor] → 请求 | 内容=... 那一行。
# ============================================================================

$ErrorActionPreference = 'Stop'

# 默认地址。若你的应用跑在别的端口，改这一行即可。
if (-not $Global:HagentBaseUrl) {
    $Global:HagentBaseUrl = 'http://127.0.0.1:8123/api'
}

# ============================================================================
# 读响应体的两个辅助函数 —— 本文件里【所有】读响应的地方都必须走它们
# ============================================================================
#
# 为什么不能直接用 $resp.Content：服务端响应头是 application/json（无 charset），
# 而 Invoke-WebRequest 缺 charset 时按 ISO-8859-1 解码响应体 → 中文全乱码。
# 详见文件顶部"响应方向的同款陷阱"。
#
# 做法：取原始字节 → 显式 UTF-8 解码。不依赖任何自动解码。
# ============================================================================

<#
.SYNOPSIS
    按 UTF-8 读【成功响应】的正文（绕开 Invoke-WebRequest 的错误解码）。
.PARAMETER Response
    Invoke-WebRequest 返回的响应对象（必须带 RawContentStream）。
#>
function 读响应体 {
    param([Parameter(Mandatory = $true)]$Response)

    if (-not $Response.RawContentStream) {
        # 拿不到原始流时明确告警再用 .Content 兜底 —— 乱码可见，不是静默降级
        Write-Host "⚠️ 拿不到 RawContentStream，退回 .Content（中文可能乱码）" -ForegroundColor Yellow
        return $Response.Content
    }
    $ms = New-Object System.IO.MemoryStream
    $Response.RawContentStream.Position = 0
    $Response.RawContentStream.CopyTo($ms)
    return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

<#
.SYNOPSIS
    按 UTF-8 读【失败响应（4xx/5xx）】的正文。
.DESCRIPTION
    走异常路径时拿不到响应对象，只有 $_.Exception.Response 上的流。
    同样按字节读、显式 UTF-8 解码 —— 否则中文错误信息（如"请求参数不合法"）也是乱码。
#>
function 读错误响应体 {
    param([Parameter(Mandatory = $true)]$Response)

    $stream = $Response.GetResponseStream()
    $ms = New-Object System.IO.MemoryStream
    $stream.CopyTo($ms)
    return [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
}

<#
.SYNOPSIS
    发一次同步对话请求（带会话记忆）。
.DESCRIPTION
    把请求体显式编码成 UTF-8 字节，绕开 PS 5.1 的编码陷阱。
.EXAMPLE
    问 -Message "我叫张三，在一家做企业服务的公司上班"
.EXAMPLE
    问 -Message "我叫什么名字？" -ConversationId $cid
#>
function 问 {
    param(
        [Parameter(Mandatory = $true)][string]$Message,
        [string]$ConversationId,
        [string]$BaseUrl = $Global:HagentBaseUrl
    )

    $payload = @{ message = $Message }
    if ($ConversationId) { $payload.conversationId = $ConversationId }

    # ★ 关键三步：先转 JSON → 再取 UTF-8 字节 → 传字节而不是字符串
    $json  = $payload | ConvertTo-Json -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)

    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/chat" -Method POST `
            -ContentType 'application/json; charset=utf-8' `
            -Body $bytes -UseBasicParsing -TimeoutSec 60

        # ★ 不用 $resp.Content（它会按 ISO-8859-1 解码 → 中文乱码），走按字节读
        $body = 读响应体 -Response $resp
        $parsed = $body | ConvertFrom-Json
        [PSCustomObject]@{
            状态码   = [int]$resp.StatusCode
            会话ID   = $resp.Headers['X-Conversation-Id']
            回复     = $parsed.data.reply
            原始响应 = $body
        }
    }
    catch {
        $r = $_.Exception.Response
        if ($r) {
            $body = 读错误响应体 -Response $r
            写日志 "出错：HTTP $([int]$r.StatusCode)  $body"
            [PSCustomObject]@{
                状态码   = [int]$r.StatusCode
                会话ID   = $r.Headers['X-Conversation-Id']
                回复     = $null
                原始响应 = $body
            }
        }
        else {
            写日志 "连接失败：$($_.Exception.Message)"
            $null
        }
    }
}

<#
.SYNOPSIS
    显示指定会话的记忆文件内容。
.EXAMPLE
    看记忆 -ConversationId $cid
#>
function 看记忆 {
    param(
        [Parameter(Mandatory = $true)][string]$ConversationId,
        [string]$Dir = "$PSScriptRoot\..\tmp\chat-memory"
    )

    $file = Join-Path $Dir "$ConversationId.json"
    if (-not (Test-Path $file)) {
        写日志 "找不到记忆文件：$file"
        return
    }
    Write-Host "文件：$file"
    Write-Host "大小：$((Get-Item $file).Length) 字节"
    # 用 UTF-8 读，避免中文变乱码
    Get-Content $file -Encoding UTF8
}

<#
.SYNOPSIS
    列出记忆目录里所有会话文件。
#>
function 列记忆 {
    param([string]$Dir = "$PSScriptRoot\..\tmp\chat-memory")

    if (-not (Test-Path $Dir)) { 写日志 "目录不存在：$Dir"; return }
    $items = Get-ChildItem $Dir -Filter '*.json'
    if (-not $items) { 写日志 "目录为空：$Dir"; return }
    $items | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
}

<#
.SYNOPSIS
    用错误的写法发一次请求 —— 用来亲眼看到编码陷阱。
.DESCRIPTION
    故意传字符串而不是字节。服务端日志里会显示 内容=????。
    这是本工具存在的理由：让你不需要相信我，自己就能验证。
#>
function 错误示范 {
    param(
        [string]$Message = '我叫张三',
        [string]$BaseUrl = $Global:HagentBaseUrl
    )
    Write-Host "⚠️ 这是【故意错误】的写法，用来对比。中文会变成 ????。" -ForegroundColor Yellow
    try {
        Invoke-WebRequest -Uri "$BaseUrl/chat" -Method POST `
            -ContentType 'application/json' `
            -Body "{`"message`":`"$Message`"}" -UseBasicParsing -TimeoutSec 30 | Out-Null
    }
    catch { Write-Host "已发出（HTTP $([int]$_.Exception.Response.StatusCode)）" }
}

<#
.SYNOPSIS
    发一个【不需要 API Key】的请求并打印状态码 / 响应头 / 响应体。
.DESCRIPTION
    用于验证协议级错误（404 / 405 / 415）—— 这些请求根本走不到模型，
    所以不需要真实 Key，改错路径或方法就能触发。

    ★ 必须是这个形式：出错时也要把【响应体】打出来。
    只打印异常消息（"远程服务器返回错误: (404) 未找到"）是没用的 ——
    我们要核对的是 {"code":40004,"message":"接口不存在"} 这个业务契约。
.PARAMETER Path
    相对 BaseUrl 的路径，例如 "/nope" 或 "/chat"
.PARAMETER Method
    HTTP 方法，默认 GET
.PARAMETER ContentType
    请求的 Content-Type，用于测 415
.EXAMPLE
    探测 -Path "/nope"                                   → 期望 404 / 40004
.EXAMPLE
    探测 -Path "/chat"                                   → 期望 405 / 40005（GET 打 POST 接口）
.EXAMPLE
    探测 -Path "/chat" -Method POST -ContentType "text/plain"   → 期望 415 / 40006
#>
function 探测 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Method = 'GET',
        [string]$ContentType,
        [string]$Body = 'hello',
        [string]$BaseUrl = $Global:HagentBaseUrl
    )

    $args = @{
        Uri             = "$BaseUrl$Path"
        Method          = $Method
        UseBasicParsing = $true
        TimeoutSec      = 20
    }
    if ($ContentType) { $args.ContentType = $ContentType }
    if ($Method -ne 'GET' -and $Method -ne 'HEAD') { $args.Body = $Body }

    try {
        $r = Invoke-WebRequest @args
        Write-Host "HTTP $([int]$r.StatusCode)" -ForegroundColor Green
        Write-Host "  Allow 头 : $($r.Headers['Allow'])"
        Write-Host "  响应体   : $(读响应体 -Response $r)"
    }
    catch {
        $resp = $_.Exception.Response
        if (-not $resp) { Write-Host "连接失败：$($_.Exception.Message)" -ForegroundColor Red; return }

        $body = 读错误响应体 -Response $resp
        Write-Host "HTTP $([int]$resp.StatusCode)" -ForegroundColor Yellow
        Write-Host "  Allow 头 : $($resp.Headers['Allow'])"
        Write-Host "  Accept 头: $($resp.Headers['Accept'])"
        Write-Host "  响应体   : $body"
    }
}

<#
.SYNOPSIS
    健康检查（不需要 API Key）。
.DESCRIPTION
    内部就是调用「探测 -Path /health」，所以失败时同样会打印响应体。
#>
function 健康 {
    param([string]$BaseUrl = $Global:HagentBaseUrl)
    探测 -Path '/health' -BaseUrl $BaseUrl
}

# 内部：统一的中文输出（避免各处重复 Write-Host 参数）
function 写日志 {
    param([string]$Text)
    Write-Host $Text
}

Write-Host ""
Write-Host "Hagent 测试工具已加载。" -ForegroundColor Green
Write-Host "  可用命令："
Write-Host "    问 -Message '...'                        发一次对话"
Write-Host "    问 -Message '...' -ConversationId <cid>  续用同一会话"
Write-Host "    看记忆 -ConversationId <cid>             查看该会话的记忆文件"
Write-Host "    列记忆                                   列出全部会话文件"
Write-Host "    健康                                     健康检查"
Write-Host "    探测 -Path '/nope'                       探测任意路径（404/405/415 用它）"
Write-Host "    错误示范                                 故意用错写法，看编码陷阱"
Write-Host ""
Write-Host "  当前地址：$Global:HagentBaseUrl"
Write-Host ""
