# rename-check.ps1 —— 改名批次的固定收尾：扫「旧词作为标识符」的残留
# 用法：点号加载后执行  检查残留
# 为什么需要它：改类型名只能命中「完整类型名」。变量名/方法名/注解/注释里的
#   旧词根（如 conversationCatalog、recordStore、@Service）不会跟着变，
#   而它们没有编译器告警。本项目已因此漏过 3 次。

$HagentOldWords = @(
    'ConversationRecordStore','ConversationCatalog','ConversationRepository',
    'HashingSupport','AtomicFiles','ChatHeaders','memory.session'
)

function 检查残留 {
    param([string]$Root = (Join-Path $PSScriptRoot '..\src'))
    $hits = @()
    foreach ($w in $HagentOldWords) {
        $found = Get-ChildItem -Recurse -Include *.java $Root |
                 Select-String -SimpleMatch $w
        foreach ($f in $found) {
            $hits += ("{0}:{1}: {2}  ← 旧词 [{3}]" -f (Split-Path $f.Path -Leaf), $f.LineNumber, $f.Line.Trim(), $w)
        }
    }
    if ($hits.Count -eq 0) {
        Write-Host '✅ 没有旧词残留' -ForegroundColor Green
    } else {
        Write-Host ("⚠️ 发现 {0} 处残留：" -f $hits.Count) -ForegroundColor Yellow
        $hits | ForEach-Object { Write-Host "  $_" }
    }
}