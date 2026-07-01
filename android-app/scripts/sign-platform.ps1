# Platform sign: platform.pk8 + platform.x509.pem
# See docs/hardware/ZE69刷机与预装.md

param(
    [Parameter(Mandatory = $true)]
    [string]$UnsignedApk,
    [Parameter(Mandatory = $true)]
    [string]$OutApk,
    [string]$KeyDir = "",
    [string]$SignApkJar = "",
    [string]$OpenSsl = "",
    [string]$ApkSigner = ""
)

$ErrorActionPreference = "Stop"

function Resolve-KeyDir {
    param([string]$Explicit)

    if ($Explicit -and (Test-Path (Join-Path $Explicit "platform.x509.pem"))) {
        return (Resolve-Path $Explicit).Path
    }

    if ($env:ADAPT_KEY_DIR -and (Test-Path (Join-Path $env:ADAPT_KEY_DIR "platform.x509.pem"))) {
        return (Resolve-Path $env:ADAPT_KEY_DIR).Path
    }

    $localProps = Join-Path $PSScriptRoot "..\local.properties"
    if (Test-Path $localProps) {
        foreach ($line in Get-Content $localProps -Encoding UTF8) {
            if ($line -match '^\s*platform\.sign\.pem\s*=\s*(.+)\s*$') {
                $pemPath = $Matches[1].Trim() -replace '\\:', ':'
                if (Test-Path $pemPath) {
                    return (Resolve-Path (Split-Path $pemPath -Parent)).Path
                }
            }
        }
    }

    $desktop = Join-Path $env:USERPROFILE "Desktop"
    $asciiKeyDir = Join-Path $desktop "aizhifa"
    if (Test-Path (Join-Path $asciiKeyDir "platform.x509.pem")) {
        return (Resolve-Path $asciiKeyDir).Path
    }

    if (Test-Path $desktop) {
        $hit = Get-ChildItem -Path $desktop -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $pem = Join-Path $_.FullName "platform.x509.pem"
                if (Test-Path $pem) { $_.FullName }
            } |
            Select-Object -First 1
        if ($hit) { return $hit }
    }

    return $null
}

function Find-OpenSsl {
    if ($OpenSsl -and (Test-Path $OpenSsl)) { return (Resolve-Path $OpenSsl).Path }
    $candidates = @(
        (Get-Command openssl -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source),
        "$env:ProgramFiles\Git\usr\bin\openssl.exe",
        "D:\gitpath\Git\usr\bin\openssl.exe",
        "$env:USERPROFILE\anaconda3\Library\bin\openssl.exe",
        "$env:USERPROFILE\Downloads\anaconda\Library\bin\openssl.exe"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

function Find-ApkSigner {
    if ($ApkSigner -and (Test-Path $ApkSigner)) { return (Resolve-Path $ApkSigner).Path }

    $localProps = Join-Path $PSScriptRoot "..\local.properties"
    $sdk = $null
    if (Test-Path $localProps) {
        foreach ($line in Get-Content $localProps -Encoding UTF8) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)\s*$') {
                $sdk = $Matches[1].Trim() -replace '\\:', ':'
                break
            }
        }
    }
    if (-not $sdk) {
        $sdk = $env:ANDROID_HOME
    }
    if ($sdk) {
        $hit = Get-Item (Join-Path $sdk "build-tools\*\apksigner.bat") -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

function Find-SignApkJar {
    if ($SignApkJar -and (Test-Path $SignApkJar)) { return (Resolve-Path $SignApkJar).Path }
    $jar = Join-Path $PSScriptRoot "signapk.jar"
    if (Test-Path $jar) {
        try {
            $null = [System.IO.File]::ReadAllBytes($jar)[0..1]
            if ([System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($jar)[0..1]) -eq "PK") {
                return $jar
            }
        } catch { }
    }
    return $null
}

function Sign-WithApkSigner {
    param(
        [string]$Pem,
        [string]$Pk8,
        [string]$Unsigned,
        [string]$Out,
        [string]$OpenSslExe,
        [string]$ApkSignerExe
    )

    $tmp = Join-Path $env:TEMP "platform-sign"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $key = Join-Path $tmp "platform.key"
    $p12 = Join-Path $tmp "platform.p12"

    & $OpenSslExe pkcs8 -inform DER -nocrypt -in $Pk8 -out $key | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "openssl pkcs8 failed" }

    & $OpenSslExe pkcs12 -export -in $Pem -inkey $key -out $p12 -password pass:android -name platform `
        -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "openssl pkcs12 failed (try Anaconda/Git openssl if Git openssl lacks legacy ciphers)" }

    & $ApkSignerExe sign --ks $p12 --ks-type PKCS12 --ks-pass pass:android --ks-key-alias platform `
        --out $Out $Unsigned
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed exit=$LASTEXITCODE" }
}

$keyRoot = Resolve-KeyDir -Explicit $KeyDir
if (-not $keyRoot) {
    throw "platform.x509.pem not found. Use -KeyDir, ADAPT_KEY_DIR, local.properties platform.sign.pem, or place keys under Desktop/*/"
}

$pem = Join-Path $keyRoot "platform.x509.pem"
$pk8 = Join-Path $keyRoot "platform.pk8"

if (-not (Test-Path $UnsignedApk)) { throw "APK not found: $UnsignedApk" }
if (-not (Test-Path $pem)) { throw "PEM not found: $pem" }
if (-not (Test-Path $pk8)) { throw "PK8 not found: $pk8" }

$outDir = Split-Path $OutApk -Parent
if ($outDir -and -not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

Write-Host "Signing: $UnsignedApk"
Write-Host "Keys: $pem"

$jar = Find-SignApkJar
if ($jar) {
    Write-Host "Tool: signapk.jar"
    java -jar $jar $pem $pk8 $UnsignedApk $OutApk
    if ($LASTEXITCODE -ne 0) { throw "signapk failed exit=$LASTEXITCODE" }
} else {
    $opensslExe = Find-OpenSsl
    $apksignerExe = Find-ApkSigner
    if (-not $opensslExe -or -not $apksignerExe) {
        throw "Need signapk.jar in scripts/, or openssl + Android SDK apksigner (sdk.dir in local.properties)"
    }
    Write-Host "Tool: apksigner ($apksignerExe)"
    Sign-WithApkSigner -Pem $pem -Pk8 $pk8 -Unsigned $UnsignedApk -Out $OutApk `
        -OpenSslExe $opensslExe -ApkSignerExe $apksignerExe
}

Write-Host "Done: $OutApk"
