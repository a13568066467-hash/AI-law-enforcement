# 规避用户目录含中文时 Gradle Test Worker 找不到 GradleWorkerMain 的问题：
# 使用纯 ASCII 的 GRADLE_USER_HOME（默认 D:\gradle-cache）。
param(
    [string]$Tests = "com.aifieldcam.app.platform.commandcall.CommandCallTaskRoomTest",
    [string]$GradleUserHome = "D:\gradle-cache"
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
$env:GRADLE_USER_HOME = $GradleUserHome
New-Item -ItemType Directory -Force -Path $GradleUserHome | Out-Null

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "Running: :app:testDebugUnitTest --tests $Tests"

& .\gradlew.bat :app:testDebugUnitTest --tests $Tests --no-daemon --max-workers=1
exit $LASTEXITCODE
