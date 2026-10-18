# ★ 刻意【不写】#Requires -Version 7.0：本机实际跑的是 Windows PowerShell 5.1
#   （`pwsh -Command` 那条路在这里落到 5.1）。写 7.0 会让脚本直接拒跑，
#   而报错是"脚本包含用于 ... 7.0 的 #requires 语句"，看起来像脚本坏了。
#   ★ 也正因为跑在 5.1，本文件必须带 UTF-8 BOM（协议 §9.4），否则中文全乱。
#
# 用法（与 sql.ps1 / hagent-test.ps1 一致：先点号加载，再调函数）：
#     . "$PWD\tools\change-list.ps1"
#     改动清单                      # 刚提交的这一阶段
#     改动清单 -Commit dc926c0      # 指定提交
#     改动清单 -Range "HEAD~2..HEAD"

<#
.SYNOPSIS
    从 git 生成【本次改动文件】清单 —— 汇报格式协议第 2 条的第一段。

.DESCRIPTION
    为什么需要这个脚本（协议第 6 条：操作性错误要机制化）：

    "列全改了哪些文件"这件事，**靠回忆一定会漏**，尤其是四十多个文件、
    跨前后端、还夹着"建了又删"的中间产物时。而漏掉的往往是那些
    "只是改了一行"的文件 —— 恰恰是复查时最需要看到的那一类。

    所以清单永远从 git 里生成，人只负责补"改了什么、为什么"。

    另一个必要性：**工作区有未提交改动时，`git show` 给出的清单是不完整的**。
    脚本因此会在那种情况下报警，而不是安静地给一份看起来完整的清单
    （一份"看起来完整"的清单比没有清单更危险）。

    ★ 它只输出路径，不猜"改了什么" —— 猜出来的描述比没有描述更糟。
#>
function 改动清单 {
    [CmdletBinding()]
    param(
        [string]$Commit = "HEAD",
        [string]$Range
    )

    Set-StrictMode -Version Latest
    $ErrorActionPreference = "Stop"

    # -----------------------------------------------------------------------
    # 0. 工作区是否干净 —— 不干净就先说清楚，别给一份"看起来完整"的清单
    # -----------------------------------------------------------------------
    $dirty = @(git status --porcelain)
    if ($dirty.Count -gt 0) {
        Write-Host ""
        Write-Host "[警告] 工作区有 $($dirty.Count) 个未提交的改动 ——" -ForegroundColor Yellow
        Write-Host "       下面的清单【不包含】它们，汇报前请先提交（或明确说明排除了哪些）。" -ForegroundColor Yellow
        Write-Host ""
        foreach ($item in $dirty) {
            Write-Host "         $item" -ForegroundColor DarkGray
        }
        Write-Host ""
    }

    # -----------------------------------------------------------------------
    # 1. 取改动清单
    # -----------------------------------------------------------------------
    $target = if ($Range) { $Range } else { $Commit }
    $raw = @(git show --name-status --format="" $target)
    if ($raw.Count -eq 0 -or -not $raw[0]) {
        Write-Host "提交 $target 没有文件改动（或该提交不存在）。" -ForegroundColor Red
        return
    }

    # -----------------------------------------------------------------------
    # 2. 分组：新增 / 修改 / 删除 / 重命名
    #    ★ git 的字母：A 新增、M 修改、D 删除、R 重命名、C 复制、T 类型变化
    # -----------------------------------------------------------------------
    $added = @()
    $modified = @()
    $deleted = @()
    $renamed = @()

    foreach ($line in $raw) {
        if (-not $line) { continue }
        $parts = $line -split "`t"
        $code = $parts[0].Substring(0, 1)
        switch ($code) {
            "A" { $added += $parts[1] }
            "D" { $deleted += $parts[1] }
            "R" { $renamed += ("{0} -> {1}" -f $parts[1], $parts[2]) }
            "C" { $renamed += ("{0} （复制到）{1}" -f $parts[1], $parts[2]) }
            default { $modified += $parts[1] }
        }
    }

    # -----------------------------------------------------------------------
    # 3. 按汇报格式打印
    # -----------------------------------------------------------------------
    Write-Host ""
    Write-Host "【本次改动文件】$target" -ForegroundColor Cyan

    if ($added.Count -gt 0) {
        Write-Host ""
        Write-Host "  新增（$($added.Count)）" -ForegroundColor Green
        foreach ($item in ($added | Sort-Object)) { Write-Host "    - $item" }
    }

    if ($modified.Count -gt 0) {
        Write-Host ""
        Write-Host "  修改（$($modified.Count)）" -ForegroundColor Green
        foreach ($item in ($modified | Sort-Object)) { Write-Host "    - $item" }
    }

    if ($renamed.Count -gt 0) {
        Write-Host ""
        Write-Host "  重命名 / 复制（$($renamed.Count)）" -ForegroundColor Green
        foreach ($item in ($renamed | Sort-Object)) { Write-Host "    - $item" }
    }

    # 删除为 0 时也写出来："删除：无"本身就是一条信息，别让读者去猜
    Write-Host ""
    if ($deleted.Count -gt 0) {
        Write-Host "  删除（$($deleted.Count)）" -ForegroundColor Green
        foreach ($item in ($deleted | Sort-Object)) { Write-Host "    - $item" }
    }
    else {
        Write-Host "  删除：无"
    }

    $total = $added.Count + $modified.Count + $deleted.Count + $renamed.Count
    Write-Host ""
    Write-Host ("  合计 {0} 个文件（新增 {1} / 修改 {2} / 删除 {3}）" -f `
            $total, $added.Count, $modified.Count, $deleted.Count) -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  ★ 路径齐了，接着按协议第 2 条补：每个文件【改了什么、为什么】。" -ForegroundColor DarkGray
    Write-Host ""
}
