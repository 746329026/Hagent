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

        $parsed = $resp.Content | ConvertFrom-Json
        [PSCustomObject]@{
            状态码   = [int]$resp.StatusCode
            会话ID   = $resp.Headers['X-Conversation-Id']
            回复     = $parsed.data.reply
            原始响应 = $resp.Content
        }
    }
    catch {
        $r = $_.Exception.Response
        if ($r) {
            $sr = New-Object System.IO.StreamReader($r.GetResponseStream())
            $body = $sr.ReadToEnd()
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
        Write-Host "  响应体   : $($r.Content)"
    }
    catch {
        $resp = $_.Exception.Response
        if (-not $resp) { Write-Host "连接失败：$($_.Exception.Message)" -ForegroundColor Red; return }

        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $body = $sr.ReadToEnd()
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
