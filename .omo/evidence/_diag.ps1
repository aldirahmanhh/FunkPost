$ErrorActionPreference = 'Continue'
$env:CI = 'true'
Set-Location 'D:\Project-Gabut\autopost-android'

# Capture raw stderr+stdout without PowerShell wrapping objects
$tmp = 'D:\Project-Gabut\autopost-android\.omo\evidence\_assemble-raw.txt'
cmd /c ".\gradlew.bat :app:assembleDebug --no-daemon 1> `"$tmp`" 2>&1"
"EXIT_CODE: $LASTEXITCODE"

$lines = Get-Content -LiteralPath $tmp -Encoding utf8
"TOTAL_LINES: $($lines.Count)"
"--- root cause candidates ---"
$lines | Where-Object { $_ -match '(?i)fail|error|wrong|caused|unsupported|not found|requires|incompatible|sdk' } | Select-Object -First 40
