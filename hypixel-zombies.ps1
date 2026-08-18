# ============================================================
# Hypixel Arcade Zombies（僵尸模式）详细统计脚本
# 用法:
#   .\hypixel-zombies.ps1 -Username "玩家名" -ApiKey "你的key"
#   或设置环境变量 HYPIXEL_API_KEY 后省略 -ApiKey
# ============================================================
param(
    [Parameter(Mandatory = $true)][string]$Username,
    [string]$ApiKey
)

$ErrorActionPreference = 'Stop'

if (-not $ApiKey) { $ApiKey = $env:HYPIXEL_API_KEY }
if (-not $ApiKey) { throw "需要 -ApiKey 参数，或先设置环境变量 HYPIXEL_API_KEY" }

$headers = @{ "API-Key" = $ApiKey }

# ---------- 1. 玩家名 -> UUID ----------
try {
    $mojang = Invoke-RestMethod -Uri "https://api.mojang.com/users/profiles/minecraft/$Username"
} catch {
    Write-Host "错误: 无法解析玩家名 '$Username'" -ForegroundColor Red
    exit 1
}
$uuid = $mojang.id
Write-Host "玩家: $($mojang.name)   UUID: $uuid"

# ---------- 2. 拉取数据 ----------
$resp = Invoke-RestMethod -Uri "https://api.hypixel.net/v2/player?uuid=$uuid" -Headers $headers
if (-not $resp.success) { Write-Host "错误: $($resp.cause)" -ForegroundColor Red; exit 1 }
$arcade = $resp.player.stats.Arcade
if (-not $arcade) { Write-Host "该玩家没有 Arcade 数据" -ForegroundColor Yellow; exit 1 }

# 兼容少数玩家数据中的嵌套 Zombies 对象: 合并其属性为扁平字段
if ($arcade.Zombies -is [PSCustomObject]) {
    $arcade.Zombies.PSObject.Properties | ForEach-Object {
        if (-not $arcade.PSObject.Properties.Name -contains $_.Name) {
            $arcade | Add-Member -NotePropertyName $_.Name -NotePropertyValue $_.Value
        }
    }
}

# ---------- 3. 按规则分类字段 ----------
$mapNames = @{ deadend = '死胡同 Dead End'; badblood = '坏血 Bad Blood'; alienarcadium = '外星街机 Alien Arcadium'; prison = '监狱 Prison' }
$diffNames = @{ normal = '普通'; hard = '困难'; rip = 'RIP 梦魇' }

$overall   = @{}   # 总体核心统计
$misc      = @{}   # 总体杂项(爆头/弹药等)
$specials  = @{}   # 特殊僵尸击杀
$fastest   = @{}   # 最快到达波次时间, key: "wave" 或 "map|diff|wave"
$perMap    = @{}   # 地图统计: [mapKey] -> @{ normal=@{...}; hard=...; rip=...; agg=聚合 }

foreach ($prop in $arcade.PSObject.Properties) {
    $key = [string]$prop.Name
    $val = $prop.Value
    if ($key -eq 'zombies_hideTutorials') { continue }
    if ($key -notmatch 'zombies') { continue }

    # 核心统计: <stat>_zombies[?_地图[?_难度]]
    if ($key -match '^(wins|deaths|best_round|total_rounds_survived|players_revived|times_knocked_down|windows_repaired|doors_opened|zombie_kills)_zombies(?:_(.+))?$') {
        $stat = $Matches[1]; $suffix = $Matches[2]
        if (-not $suffix) { $overall[$stat] = $val; continue }
        $map = ''; $diff = ''
        foreach ($m in $mapNames.Keys) {
            if ($suffix -eq $m) { $map = $m; break }
            if ($suffix -like "$($m)_*") {
                $map = $m
                $rest = $suffix.Substring($m.Length + 1)
                if ($diffNames.ContainsKey($rest)) { $diff = $rest }
                break
            }
        }
        if (-not $map) { continue }
        if (-not $perMap.ContainsKey($map)) { $perMap[$map] = @{} }
        $dkey = if ($diff) { $diff } else { 'agg' }
        if (-not $perMap[$map].ContainsKey($dkey)) { $perMap[$map][$dkey] = @{} }
        $perMap[$map][$dkey][$stat] = $val
        continue
    }
    # 最快时间: fastest_time_<n>_zombies[?_地图[?_难度]]
    if ($key -match '^fastest_time_(\d+)_zombies(?:_(.+))?$') {
        $wave = $Matches[1]; $suffix = $Matches[2]
        if (-not $suffix) { $fastest[$wave] = $val; continue }
        $map = ''; $diff = ''
        foreach ($m in $mapNames.Keys) {
            if ($suffix -eq $m) { $map = $m; break }
            if ($suffix -like "$($m)_*") {
                $map = $m
                $rest = $suffix.Substring($m.Length + 1)
                if ($diffNames.ContainsKey($rest)) { $diff = $rest }
                break
            }
        }
        if ($map) { $fastest["$map|$diff|$wave"] = $val }
        continue
    }
    # 杂项（先于特殊怪判断，避免 basic_zombie_kills 被误归类）
    if ($key -match '^(headshots|basic_zombie_kills|tnt_baby_zombie_kills|bullets_shot|bullets_hit)_zombies$') {
        $misc[$Matches[1]] = $val
        continue
    }
    # 特殊僵尸击杀: <怪名>_zombie_kills_zombies
    if ($key -match '^(.+)_zombie_kills_zombies$') {
        $specials[$Matches[1]] = $val
        continue
    }
}

function Format-Sec($sec) {
    if ($null -eq $sec -or $sec -eq '') { return '-' }
    $t = [TimeSpan]::FromSeconds([long]$sec)
    return ("{0}:{1:D2}" -f [math]::Floor($t.TotalMinutes), $t.Seconds)
}

# ---------- 4. 输出: 总体 ----------
Write-Host ""
Write-Host "=================== Zombies 总体 ==================="
if ($overall.Count -eq 0) { Write-Host "(无总体数据)" } else {
    Write-Host ("胜场        : {0:N0}" -f $overall['wins'])
    Write-Host ("最佳回合    : {0} 波" -f $overall['best_round'])
    Write-Host ("僵尸击杀    : {0:N0}" -f $overall['zombie_kills'])
    Write-Host ("死亡次数    : {0:N0}" -f $overall['deaths'])
    Write-Host ("存活总回合  : {0:N0}" -f $overall['total_rounds_survived'])
    Write-Host ("救人次数    : {0:N0}" -f $overall['players_revived'])
    Write-Host ("倒地次数    : {0:N0}" -f $overall['times_knocked_down'])
    Write-Host ("修理窗户    : {0:N0}" -f $overall['windows_repaired'])
    Write-Host ("开启大门    : {0:N0}" -f $overall['doors_opened'])
    Write-Host ("最快 10 波  : {0}   20 波: {1}   30 波: {2}" -f (Format-Sec $fastest['10']), (Format-Sec $fastest['20']), (Format-Sec $fastest['30']))
    Write-Host ("爆头        : {0:N0}" -f $misc['headshots'])
    if ($misc['bullets_shot']) { Write-Host ("弹药        : 射击 {0:N0} / 命中 {1:N0}  (命中率 {2:P1})" -f $misc['bullets_shot'], $misc['bullets_hit'], $(if ($misc['bullets_shot']) { $misc['bullets_hit'] / $misc['bullets_shot'] } else { 0 })) }
}

# ---------- 5. 输出: 各地图 x 难度 ----------
Write-Host ""
Write-Host "================= 各地图 × 难度 ================="
foreach ($map in ($perMap.Keys | Sort-Object)) {
    $mapLabel = $mapNames[$map]
    Write-Host ""
    Write-Host "--- $mapLabel ---"
    $dkeys = $perMap[$map].Keys | Sort-Object
    foreach ($dkey in $dkeys) {
        $s = $perMap[$map][$dkey]
        $dLabel = if ($dkey -eq 'agg') { '全部难度合计' } else { $diffNames[$dkey] }
        $f10 = Format-Sec $fastest["$map|$dkey|10"]
        $f20 = Format-Sec $fastest["$map|$dkey|20"]
        $f30 = Format-Sec $fastest["$map|$dkey|30"]
        Write-Host ("  [{0,-8}] 胜场:{1,7}  最高波:{2,4}  击杀:{3,10:N0}  死亡:{4,6}  存活回合:{5,9:N0}  救人:{6,6}  修窗:{7,7}  开门:{8,6}" -f `
            $dLabel, $s['wins'], $s['best_round'], $s['zombie_kills'], $s['deaths'], $s['total_rounds_survived'], $s['players_revived'], $s['windows_repaired'], $s['doors_opened'])
        if ($f10 -ne '-') { Write-Host ("             最快 10 波: {0}   20 波: {1}   30 波: {2}" -f $f10, $f20, $f30) }
    }
}

# ---------- 6. 输出: 特殊僵尸击杀 Top 15 ----------
Write-Host ""
Write-Host "============ 特殊僵尸/精英怪击杀 TOP 15 ============"
$specials.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 15 | ForEach-Object {
    $name = $_.Key -replace '_', ' '
    Write-Host ("  {0,-40} {1,10:N0}" -f $name, $_.Value)
}
if ($specials.Count -eq 0) { Write-Host "(无)" }

Write-Host ""
Write-Host "数据来源: https://api.hypixel.net/v2/player?uuid=$uuid"
