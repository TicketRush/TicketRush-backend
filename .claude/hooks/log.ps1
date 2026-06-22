# Claude Code 작업 로깅 훅
#   사용: powershell -NoProfile -ExecutionPolicy Bypass -File .claude/hooks/log.ps1 <kind>
#   <kind> = prompt (UserPromptSubmit) | tools (PreToolUse)
# stdin 으로 들어온 훅 JSON 을 .claude/logs/<kind>.jsonl 에 한 줄(JSONL)로 적재한다.
# 표준출력으로는 아무것도 내보내지 않는다(UserPromptSubmit 의 stdout 은 컨텍스트에 주입되므로).
param([string]$Kind = "event")

# stdin 을 콘솔 코드페이지와 무관하게 항상 UTF-8 로 디코딩한다(한글 깨짐 방지).
try {
    $stdin  = [Console]::OpenStandardInput()
    $reader = New-Object System.IO.StreamReader($stdin, [System.Text.Encoding]::UTF8)
    $raw    = $reader.ReadToEnd()
    $reader.Dispose()
} catch {
    $raw = ""
}

# 훅 페이로드(JSON)를 파싱. 실패하면 원문 문자열을 그대로 보존.
try { $event = $raw | ConvertFrom-Json } catch { $event = $raw }

$record = [ordered]@{
    ts    = (Get-Date).ToString("o")   # ISO-8601, 재현/추적용 타임스탬프
    kind  = $Kind
    event = $event
}

$logDir = Join-Path $PSScriptRoot "..\logs"
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
}

$line = $record | ConvertTo-Json -Compress -Depth 30
Add-Content -Path (Join-Path $logDir "$Kind.jsonl") -Value $line -Encoding utf8

exit 0   # 훅이 작업을 막지 않도록 항상 성공 반환
