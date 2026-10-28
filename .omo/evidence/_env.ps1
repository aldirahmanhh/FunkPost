$ErrorActionPreference = 'Continue'
$env:CI = 'true'
$env:GIT_TERMINAL_PROMPT = '0'
Set-Location 'D:\Project-Gabut\autopost-android'
"git: " + (git --version)
"java: " + ((java -version 2>&1 | Select-Object -First 1) -join '')
"repo: " + (git rev-parse --is-inside-work-tree 2>&1)
"--- platforms"
Get-ChildItem "C:\Users\Anriz\AppData\Local\Android\Sdk\platforms" | Select-Object -ExpandProperty Name
"--- build-tools"
Get-ChildItem "C:\Users\Anriz\AppData\Local\Android\Sdk\build-tools" | Select-Object -ExpandProperty Name
"--- adb devices"
& "C:\Users\Anriz\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices 2>&1
"adb-exit: $LASTEXITCODE"
"--- root files"
Get-ChildItem -Force | Select-Object -ExpandProperty Name
