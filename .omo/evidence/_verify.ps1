$ErrorActionPreference = 'Continue'
$env:CI = 'true'
Set-Location 'D:\Project-Gabut\autopost-android'

$log = 'D:\Project-Gabut\autopost-android\.omo\evidence\task-1-android-local-autopost.log'
New-Item -ItemType Directory -Force -Path '.omo\evidence' | Out-Null
Set-Content -LiteralPath $log -Value '' -Encoding utf8

"# task-1 android-local-autopost : scaffold evidence" | Out-File $log -Append -Encoding utf8
"DATE: $(Get-Date -Format o)" | Out-File $log -Append -Encoding utf8
"JAVA_HOME: $env:JAVA_HOME" | Out-File $log -Append -Encoding utf8

function Step($label, $cmdLine) {
    "`n===== $label =====" | Out-File $log -Append -Encoding utf8
    "CMD: $cmdLine" | Out-File $log -Append -Encoding utf8
    $tmp = ".omo\evidence\_step.log"
    cmd /c "$cmdLine 1> `"$tmp`" 2>&1"
    $code = $LASTEXITCODE
    $lines = @(Get-Content -LiteralPath $tmp -Encoding utf8)
    "----- stdout (tail 25 of $($lines.Count)) -----" | Out-File $log -Append -Encoding utf8
    ($lines | Select-Object -Last 25) | Out-File $log -Append -Encoding utf8
    "EXIT_CODE: $code" | Out-File $log -Append -Encoding utf8
    "===== $label -> EXIT_CODE: $code ====="
    $lines | Select-Object -Last 12
    $script:rc = $code
}

Step 'gradlew --version' '.\gradlew.bat --version'
$v = $script:rc

Step 'gradlew :app:assembleDebug' '.\gradlew.bat :app:assembleDebug'
$a = $script:rc

"`n===== config assertions =====" | Out-File $log -Append -Encoding utf8
$b = Get-Content app\build.gradle.kts -Raw
foreach ($pat in @('applicationId = "com.gabut.autopost"', 'minSdk = 26', 'targetSdk = 34', 'compileSdk = 35', 'compose = true', 'room.schemaLocation', 'isIncludeAndroidResources = true', 'jvmTarget = "17"')) {
    $hit = $b.Contains($pat)
    "ASSERT_CONTAINS[$pat]: $hit" | Out-File $log -Append -Encoding utf8
    "ASSERT_CONTAINS[$pat]: $hit"
}

$apk = 'app\build\outputs\apk\debug\app-debug.apk'
"`n===== APK =====" | Out-File $log -Append -Encoding utf8
if (Test-Path $apk) {
    $f = Get-Item $apk
    $sha = (Get-FileHash $apk -Algorithm SHA256).Hash
    "APK_PATH: $($f.FullName)" | Out-File $log -Append -Encoding utf8
    "APK_SIZE_BYTES: $($f.Length)" | Out-File $log -Append -Encoding utf8
    "APK_SHA256: $sha" | Out-File $log -Append -Encoding utf8
    "APK_PATH: $($f.FullName) ($($f.Length) bytes) sha256=$sha"
} else {
    "APK_MISSING: $apk" | Out-File $log -Append -Encoding utf8
    "APK_MISSING"
}

"`n===== adb devices (manual QA) =====" | Out-File $log -Append -Encoding utf8
$adb = 'C:\Users\Anriz\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$tmp = ".omo\evidence\_adb.log"
cmd /c "`"$adb`" devices 1> `"$tmp`" 2>&1"
$adbCode = $LASTEXITCODE
$adbOut = @(Get-Content -LiteralPath $tmp -Encoding utf8)
"CMD: adb devices" | Out-File $log -Append -Encoding utf8
$adbOut | Out-File $log -Append -Encoding utf8
"EXIT_CODE: $adbCode" | Out-File $log -Append -Encoding utf8

$online = @($adbOut | Where-Object { $_ -match '\tdevice$' })
if ($online.Count -gt 0) {
    $dev = ($online[0] -split '\t')[0]
    "DEVICE_FOUND: $dev" | Out-File $log -Append -Encoding utf8
    cmd /c "`"$adb`" -s $dev install -r $apk 1> `"$tmp`" 2>&1"
    "CMD: adb -s $dev install -r $apk" | Out-File $log -Append -Encoding utf8
    (Get-Content -LiteralPath $tmp -Encoding utf8) | Out-File $log -Append -Encoding utf8
    "EXIT_CODE: $LASTEXITCODE" | Out-File $log -Append -Encoding utf8
    "INSTALL: exit $LASTEXITCODE"

    cmd /c "`"$adb`" -s $dev shell am start -n com.gabut.autopost/.MainActivity 1> `"$tmp`" 2>&1"
    "CMD: adb -s $dev shell am start -n com.gabut.autopost/.MainActivity" | Out-File $log -Append -Encoding utf8
    (Get-Content -LiteralPath $tmp -Encoding utf8) | Out-File $log -Append -Encoding utf8
    "EXIT_CODE: $LASTEXITCODE" | Out-File $log -Append -Encoding utf8

    Start-Sleep -Seconds 3
    cmd /c "`"$adb`" -s $dev shell dumpsys activity activities 1> `"$tmp`" 2>&1"
    $resumed = Get-Content -LiteralPath $tmp -Encoding utf8 | Select-String -Pattern 'mResumedActivity|topResumedActivity' | Select-Object -First 3
    "----- resumed activity -----" | Out-File $log -Append -Encoding utf8
    $resumed | ForEach-Object { $_.Line } | Out-File $log -Append -Encoding utf8
    "RESUMED_MATCH: " + [bool]($resumed | Where-Object { $_.Line -match 'com\.gabut\.autopost' }) | Out-File $log -Append -Encoding utf8
    $resumed | ForEach-Object { $_.Line }
} else {
    "INSTALL: SKIPPED-DEVICE-ABSENT" | Out-File $log -Append -Encoding utf8
    "LAUNCH: SKIPPED-DEVICE-ABSENT" | Out-File $log -Append -Encoding utf8
    "DUMPSYS: SKIPPED-DEVICE-ABSENT" | Out-File $log -Append -Encoding utf8
    "INSTALL: SKIPPED-DEVICE-ABSENT (no adb device online)"
}

"`nSUMMARY: version_exit=$v assembleDebug_exit=$a apk_present=$(Test-Path $apk)" | Out-File $log -Append -Encoding utf8
"SUMMARY: version_exit=$v assembleDebug_exit=$a apk_present=$(Test-Path $apk)"
