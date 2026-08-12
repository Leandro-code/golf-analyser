param(
    [string]$Serial,
    [string]$Package = "com.golfanalyser.app",
    [string]$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$OutputDirectory = ".\device-profile"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $Adb)) {
    throw "adb was not found at $Adb"
}

$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $Serial) {
    $physical = foreach ($line in $devices) {
        $candidate = ($line -split "\s+")[0]
        $isEmulator = (& $Adb -s $candidate shell getprop ro.kernel.qemu).Trim()
        if ($isEmulator -ne "1") { $candidate }
    }
    if ($physical.Count -ne 1) {
        throw "Connect exactly one physical Android device or pass -Serial."
    }
    $Serial = $physical[0]
}

if ((& $Adb -s $Serial shell getprop ro.kernel.qemu).Trim() -eq "1") {
    throw "The selected device is an emulator; physical-device measurements are required."
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$batteryFile = Join-Path $OutputDirectory "battery-$timestamp.txt"
$summaryFile = Join-Path $OutputDirectory "summary-$timestamp.txt"

& $Adb -s $Serial logcat -c
& $Adb -s $Serial shell dumpsys batterystats --reset | Out-Null
& $Adb -s $Serial shell monkey -p $Package -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Start one pose analysis on the connected phone. Waiting for its profile marker..."

$started = $false
$peakObservedPssKb = 0
$endLine = $null
while (-not $endLine) {
    $profileLines = & $Adb -s $Serial logcat -d -s GolfAnalysisProfile:I *:S
    if (-not $started -and ($profileLines -match "START run_id=")) {
        $started = $true
        Write-Host "Analysis started; measuring until completion or cancellation."
    }
    if ($started) {
        $meminfo = & $Adb -s $Serial shell dumpsys meminfo $Package
        $totalLine = $meminfo | Where-Object { $_ -match "^\s*TOTAL\s+\d+" } | Select-Object -First 1
        if ($totalLine -match "^\s*TOTAL\s+(\d+)") {
            $peakObservedPssKb = [Math]::Max($peakObservedPssKb, [int]$Matches[1])
        }
        $endLine = $profileLines | Where-Object { $_ -match "END run_id=" } | Select-Object -Last 1
    }
    Start-Sleep -Seconds 1
}

& $Adb -s $Serial shell dumpsys batterystats --charged $Package | Set-Content -LiteralPath $batteryFile
$summary = @(
    "device_serial=$Serial"
    "captured_at=$(Get-Date -Format o)"
    "peak_observed_total_pss_kb=$peakObservedPssKb"
    "app_profile=$endLine"
    "battery_report=$batteryFile"
)
$summary | Set-Content -LiteralPath $summaryFile
$summary | ForEach-Object { Write-Host $_ }
