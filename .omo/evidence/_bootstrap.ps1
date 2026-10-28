$ErrorActionPreference = 'Stop'
$env:CI = 'true'
$env:GIT_TERMINAL_PROMPT = '0'
Set-Location 'D:\Project-Gabut\autopost-android'

# 1. git init if absent
if (-not (Test-Path '.git')) {
    git init -q
    "git-init: created"
} else {
    "git-init: already present"
}

# 2. download gradle-wrapper.jar (GitHub tag is v8.9, not v8.9.0)
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$urls = @(
    'https://raw.githubusercontent.com/gradle/gradle/v8.9/gradle/wrapper/gradle-wrapper.jar',
    'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar'
)
$dest = 'gradle\wrapper\gradle-wrapper.jar'
New-Item -ItemType Directory -Force -Path 'gradle\wrapper' | Out-Null

foreach ($u in $urls) {
    try {
        Invoke-WebRequest -Uri $u -OutFile $dest -UseBasicParsing -TimeoutSec 120
        "wrapper-jar-url: $u"
        break
    } catch {
        "wrapper-jar-fail: $u -> $($_.Exception.Message)"
    }
}

if (-not (Test-Path $dest)) { throw "wrapper jar download failed" }
$len = (Get-Item $dest).Length
"wrapper-jar-bytes: $len"
if ($len -lt 10000) { throw "wrapper jar too small ($len bytes) - probably an HTML error page" }
"wrapper-jar-sha256: $((Get-FileHash $dest -Algorithm SHA256).Hash)"
"gitignore: " + (Test-Path '.gitignore')
"local.properties: " + (Test-Path 'local.properties')
