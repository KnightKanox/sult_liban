$ErrorActionPreference = 'Stop'

$driveName = 'R'
$driveRoot = "${driveName}:\"
$createdMapping = $false
$existing = Get-PSDrive -Name $driveName -ErrorAction SilentlyContinue

if ($null -eq $existing) {
    subst "${driveName}:" $PSScriptRoot
    $createdMapping = $true
} elseif ((Resolve-Path $driveRoot).Path -ne (Resolve-Path $PSScriptRoot).Path) {
    throw "Drive $driveName is already in use. Choose or release another drive."
}

try {
    Push-Location $driveRoot
    try {
        & .\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
} finally {
    if ($createdMapping) { subst "${driveName}:" /d }
}
