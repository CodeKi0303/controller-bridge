param(
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$PasswordFile,
    [string]$Alias = 'controller-bridge',
    [string]$InputApk,
    [string]$OutputApk
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $InputApk) { $InputApk = Join-Path $taskRoot 'app\build\outputs\apk\release\app-release-unsigned.apk' }
if (-not $OutputApk) {
    $taskBuildConfig = Get-Content -LiteralPath (Join-Path $taskRoot 'app\build.gradle') -Raw
    $taskVersionMatch = [regex]::Match($taskBuildConfig, "(?m)^\s*versionName '([0-9]+\.[0-9]+\.[0-9]+)'\s*$")
    if (-not $taskVersionMatch.Success) { throw 'Cannot determine the release version; provide -OutputApk.' }
    $OutputApk = Join-Path $taskRoot ('dist\controller-bridge-' + $taskVersionMatch.Groups[1].Value + '-release.apk')
}
foreach ($taskInput in @($InputApk, $Keystore, $PasswordFile)) {
    if (-not (Test-Path -LiteralPath $taskInput -PathType Leaf)) { throw "Missing signing input: $taskInput" }
}
if (Test-Path -LiteralPath $OutputApk) { throw 'Choose a new output file; existing APKs are preserved.' }
$taskBuildTools = Join-Path $SdkRoot 'build-tools\35.0.0'
$taskJavaBefore = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkHome
    & (Join-Path $taskBuildTools 'zipalign.exe') -c -P 16 4 $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'Unsigned APK alignment check failed.' }
    $taskOutputParent = Split-Path -Parent ([IO.Path]::GetFullPath($OutputApk))
    New-Item -ItemType Directory -Force -Path $taskOutputParent | Out-Null
    # The private key uses the keystore password. Reusing one password file for
    # both options consumes two lines in apksigner; let it reuse the store value.
    & (Join-Path $taskBuildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias $Alias --ks-pass ('file:' + [IO.Path]::GetFullPath($PasswordFile)) --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & (Join-Path $taskBuildTools 'apksigner.bat') verify --verbose --print-certs $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
    Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
} finally { $env:JAVA_HOME = $taskJavaBefore }
