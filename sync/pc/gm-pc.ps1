# 게임 관리 PC 기록 프로그램
# 게임 프로세스가 켜져 있는 시간을 재서 서버에 올린다. 같은 폴더의 config.json을 읽는다.
# 실행: powershell -ExecutionPolicy Bypass -File gm-pc.ps1

$ErrorActionPreference = "Continue"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cfg = Get-Content (Join-Path $dir "config.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$statePath = Join-Path $dir "state.json"
$tick = [int]$cfg.intervalSec; if ($tick -lt 10) { $tick = 30 }
$server = ([string]$cfg.server).TrimEnd("/")

# 오늘 쌓은 시간(게임 이름 + 날짜 단위)을 파일에 저장해 두어, 프로그램을 다시 켜도 이어진다.
$state = @{}
if (Test-Path $statePath) {
    try {
        (Get-Content $statePath -Raw -Encoding UTF8 | ConvertFrom-Json).PSObject.Properties | ForEach-Object {
            $state[$_.Name] = [long]$_.Value
        }
    } catch { $state = @{} }
}

function DayKey($hour) {
    return (Get-Date).AddHours(-$hour).ToString("yyyy-MM-dd")
}

function Upload($entries) {
    if ($entries.Count -eq 0) { return }
    $body = @{ device = "pc"; entries = $entries } | ConvertTo-Json -Depth 5 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    try {
        Invoke-RestMethod -Uri "$server/api/play" -Method Post -Headers @{ "x-gm-key" = [string]$cfg.key } `
            -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 15 | Out-Null
    } catch {
        Write-Host ("업로드 실패: " + $_.Exception.Message)
    }
}

Write-Host "게임 관리 PC 기록 시작 (서버 $server, $tick 초마다 확인)"
$sinceUpload = 999
while ($true) {
    $running = @{}
    foreach ($g in $cfg.games) {
        if (Get-Process -Name ([string]$g.process) -ErrorAction SilentlyContinue) { $running[[string]$g.name] = $true }
    }
    $hourDefault = 4
    if ($null -ne $cfg.resetHour) { $hourDefault = [int]$cfg.resetHour }
    $entries = @()
    $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    foreach ($g in $cfg.games) {
        $name = [string]$g.name
        $hour = $hourDefault
        if ($null -ne $g.dayStart) { $hour = [int]$g.dayStart }
        $day = DayKey $hour
        $k = "$day|$name"
        if ($running.ContainsKey($name)) {
            if (-not $state.ContainsKey($k)) { $state[$k] = 0 }
            $state[$k] = [long]$state[$k] + ($tick * 1000)
            $entries += @{ day = $day; game = $name; ms = [long]$state[$k]; lastEnd = $nowMs }
        }
    }
    # 지난 날짜 기록은 정리 (최근 3일만 보관)
    $keep = @{}
    foreach ($k in @($state.Keys)) {
        $d = ($k -split "\|")[0]
        if ($d -ge (Get-Date).AddDays(-3).ToString("yyyy-MM-dd")) { $keep[$k] = $state[$k] }
    }
    $state = $keep
    ($state | ConvertTo-Json -Compress) | Set-Content $statePath -Encoding UTF8
    $sinceUpload += $tick
    if ($entries.Count -gt 0 -and $sinceUpload -ge 60) { Upload $entries; $sinceUpload = 0 }
    Start-Sleep -Seconds $tick
}
