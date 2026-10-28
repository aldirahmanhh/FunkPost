$ErrorActionPreference = 'Continue'
$env:CI = 'true'
$env:GIT_TERMINAL_PROMPT = '0'
Set-Location 'D:\Project-Gabut\autopost-android'

$log = 'D:\Project-Gabut\autopost-android\.omo\evidence\task-1-android-local-autopost.log'
New-Item -ItemType Directory -Force -Path '.omo\evidence' | Out-Null

$script:lastCode = 999

function Run-Step($label, $argList) {
    "`n===== $label =====" | Out-File -FilePath $log -Append -Encoding utf8
    "CMD: .\gradlew.bat $($argList -join ' ')" | Out-File -FilePath $log -Append -Encoding utf8
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $out = & .\gradlew.bat @argList 2>&1 | ForEach-Object { "$_" }
    $script:lastCode = $LASTEXITCODE
    $sw.Stop()
    "----- stdout (tail 60) -----" | Out-File -FilePath $log -Append -Encoding utf8
    ($out | Select-Object -Last 60) | Out-File -FilePath $log -Append -Encoding utf8
    "EXIT_CODE: $($script:lastCode)" | Out-File -FilePath $log -Append -Encoding utf8
    "DURATION_S: $([math]::Round($sw.Elapsed.TotalSeconds,1))" | Out-File -FilePath $log -Append -Encoding utf8
    "===== $label ====="
    ($out | Select-Object -Last 40)
    "EXIT_CODE: $($script:lastCode)"
}

Run-Step 'gradlew --version' @('--version')

$apk = 'app\build\outputs\apk\debug\app-debug.apk'
if ($script:lastCode -eq 0) {
    Run-Step 'gradlew :app:assembleDebug' @(':app:assembleDebug', '--no-daemon', '--stacktrace')
} else {
    "SKIPPED: :app:assembleDebug because --version failed" | Out-File -FilePath $log -Append -Encoding utf8
}
$assembleCode = $script:lastCode

"`n===== APK ====="  | Out-File -FilePath $log -Append -Encoding utf8
if (Test-Path $apk) {
    $f = Get-Item $apk
    $sha = (Get-FileHash $apk -Algorithm SHA256).Hash
    "APK_PATH: $($f.FullName)" | Out-File -FilePath $log -Append -Encoding utf8
    "APK_SIZE_BYTES: $($f.Length)" | Out-File -FilePath $log -Append -Encoding utf8
    "APK_SHA256: $sha" | Out-File -FilePath $log -Append -Encoding utf8
    "APK_PATH: $($f.FullName)"
    "APK_SIZE_BYTES: $($f.Length)"
    "APK_SHA256: $sha"
} else {
    "APK_MISSING: $apk" | Out-File -FilePath $log -Append -Encoding utf8
    "APK_MISSING: $apk"
}

"`nSUMMARY: assembleDebug_exit=$assembleCode" | Out-File -FilePath $log -Append -Encoding utf8
"SUMMARY: assembleDebug_exit=$assembleCode  APK_present=$(Test-Path $apk)"
