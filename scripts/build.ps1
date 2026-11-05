param(
    [string]$JdkHome,
    [string]$SdkRoot,
    [string]$NdkRoot,
    [string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'assembleDebug')
)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $JdkHome) {
    $taskCandidates = @(
        $env:JAVA_HOME,
        'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
    )
    $taskJdkBase = 'C:\Program Files\Eclipse Adoptium'
    if (Test-Path -LiteralPath $taskJdkBase) {
        $taskCandidates += Get-ChildItem -LiteralPath $taskJdkBase -Directory |
            Where-Object Name -Like 'jdk-21*' | Select-Object -ExpandProperty FullName
    }
    foreach ($taskCandidate in $taskCandidates) {
        if ($taskCandidate -and (Test-Path -LiteralPath (Join-Path $taskCandidate 'release'))) {
            $taskRelease = Get-Content -LiteralPath (Join-Path $taskCandidate 'release') -Raw
            if ($taskRelease -match 'JAVA_VERSION="(17|21)[.\"]') {
                $JdkHome = $taskCandidate
                break
            }
        }
    }
}
if (-not $JdkHome -or -not (Test-Path -LiteralPath (Join-Path $JdkHome 'bin\java.exe'))) {
    throw 'An existing JDK 17 or 21 is required. Pass -JdkHome; this script does not install Java.'
}
$taskSdk = if ($SdkRoot) { [IO.Path]::GetFullPath($SdkRoot) } else { Join-Path $taskRoot '.tools\android-sdk' }
if (-not (Test-Path -LiteralPath (Join-Path $taskSdk 'platforms\android-35\android.jar'))) {
    throw 'Android SDK 35 is missing. Pass -SdkRoot for an existing SDK.'
}
$taskPreviousJava = $env:JAVA_HOME
$taskPreviousSdk = $env:ANDROID_HOME
$taskPreviousSdkRoot = $env:ANDROID_SDK_ROOT
$taskPreviousPath = $env:PATH
try {
    $env:JAVA_HOME = $JdkHome
    $env:ANDROID_HOME = $taskSdk
    $env:ANDROID_SDK_ROOT = $taskSdk
    $env:PATH = (Join-Path $JdkHome 'bin') + ';' + $taskPreviousPath
    Push-Location $taskRoot
    try {
        $taskOptions = @('--no-daemon', '--console=plain')
        if ($NdkRoot) { $taskOptions += '-PbridgeNdkPath=' + [IO.Path]::GetFullPath($NdkRoot) }
        & (Join-Path $taskRoot 'gradlew.bat') @taskOptions @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $taskPreviousJava
    $env:ANDROID_HOME = $taskPreviousSdk
    $env:ANDROID_SDK_ROOT = $taskPreviousSdkRoot
    $env:PATH = $taskPreviousPath
}
