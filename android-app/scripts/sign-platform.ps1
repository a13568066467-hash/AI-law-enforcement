# Platform 签名：适配文档 platform.pk8 + platform.x509.pem
# 用法见 docs/hardware/ZE69刷机与预装.md

param(
    [Parameter(Mandatory = $true)]
    [string]$UnsignedApk,
    [Parameter(Mandatory = $true)]
    [string]$OutApk,
    [string]$KeyDir = "$env:USERPROFILE\Desktop\适配文档",
    [string]$SignApkJar = ""
)

$ErrorActionPreference = "Stop"
$pem = Join-Path $KeyDir "platform.x509.pem"
$pk8 = Join-Path $KeyDir "platform.pk8"

if (-not (Test-Path $UnsignedApk)) { throw "未找到 APK: $UnsignedApk" }
if (-not (Test-Path $pem)) { throw "未找到 $pem" }
if (-not (Test-Path $pk8)) { throw "未找到 $pk8" }

$outDir = Split-Path $OutApk -Parent
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

function Find-SignApkJar {
    if ($SignApkJar -and (Test-Path $SignApkJar)) { return $SignApkJar }
    $candidates = @(
        (Join-Path $PSScriptRoot "signapk.jar"),
        "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\lib\apksigner.jar"
    )
    foreach ($pattern in $candidates) {
        $hit = Get-Item $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

$jar = Find-SignApkJar
if (-not $jar) {
    throw @"
找不到 signapk.jar。请任选其一：
  1. 将 AOSP build/tools/signapk.jar 放到 android-app/scripts/signapk.jar
  2. 参数 -SignApkJar 指定路径
"@
}

if ($jar -like "*apksigner.jar") {
    Write-Host "使用 apksigner（需 PKCS#8 转 keystore 时仍推荐 signapk.jar）"
    throw "当前脚本需 signapk.jar（pem+pk8）。请从 AOSP 复制 signapk.jar 到 scripts/ 目录。"
}

Write-Host "签名: $UnsignedApk"
Write-Host "密钥: $pem"
java -jar $jar $pem $pk8 $UnsignedApk $OutApk
if ($LASTEXITCODE -ne 0) { throw "signapk 失败 exit=$LASTEXITCODE" }
Write-Host "完成: $OutApk"
