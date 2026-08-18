# ============================================================
# Hypixel Arcade 统计查询脚本
# 用法:
#   .\hypixel-arcade.ps1 -Username "玩家名" -ApiKey "你的key"
#   或设置环境变量后省略 -ApiKey:
#     $env:HYPIXEL_API_KEY = "你的key"
#     .\hypixel-arcade.ps1 -Username "玩家名"
# ============================================================
param(
    [Parameter(Mandatory = $true)][string]$Username,
    [string]$ApiKey
)

$ErrorActionPreference = 'Stop'

if (-not $ApiKey) { $ApiKey = $env:HYPIXEL_API_KEY }
if (-not $ApiKey) { throw "需要 -ApiKey 参数，或先设置环境变量 HYPIXEL_API_KEY" }

$headers = @{ "API-Key" = $ApiKey }

# ---------- 1. 玩家名 -> UUID（Mojang API，无需 key） ----------
try {
    $mojang = Invoke-RestMethod -Uri "https://api.mojang.com/users/profiles/minecraft/$Username"
} catch {
    Write-Host "错误: 无法解析玩家名 '$Username'（Mojang API 失败，注意玩家名区分大小写? 不需要，但必须准确）" -ForegroundColor Red
    exit 1
}
$uuid = $mojang.id
Write-Host "玩家: $($mojang.name)   UUID: $uuid"

# ---------- 2. 拉取 Hypixel 玩家数据 ----------
$resp = Invoke-RestMethod -Uri "https://api.hypixel.net/v2/player?uuid=$uuid" -Headers $headers
if (-not $resp.success) {
    Write-Host "错误: $($resp.cause)" -ForegroundColor Red
    exit 1
}
$player = $resp.player
if (-not $player) {
    Write-Host "该玩家从未登录过 Hypixel 或数据不可见" -ForegroundColor Yellow
    exit 1
}
$arcade = $player.stats.Arcade
if (-not $arcade) {
    Write-Host "该玩家没有 Arcade 数据" -ForegroundColor Yellow
    exit 1
}

# ---------- 3. 总览 ----------
Write-Host ""
Write-Host "================= Arcade 总览 ================="
Write-Host ("coins     : {0:N0}" -f $arcade.coins)
if ($null -ne $arcade.max_wave) { Write-Host "max_wave  : $($arcade.max_wave)   (Zombies 历史最高波次)" }
if ($null -ne $arcade.hitw_record_q) {
    Write-Host "HITW 纪录 : qualifiers=$($arcade.hitw_record_q)  finals=$($arcade.hitw_record_f)"
}
Write-Host ""

# ---------- 4. 解析扁平字段: <统计项>_<游戏代码> ----------
$statNames = @(
    'wins', 'rounds', 'kills', 'deaths', 'headshots', 'final_kills',
    'goals', 'powerkicks', 'best_round', 'zombie_kills',
    'total_rounds_survived', 'players_revived', 'times_knocked_down',
    'windows_repaired', 'doors_opened', 'bullets_shot', 'bullets_hit'
)
$games = @{}
foreach ($prop in $arcade.PSObject.Properties) {
    $key = $prop.Name
    if ($key -isnot [string]) { continue }
    foreach ($s in $statNames) {
        $prefix = $s + '_'
        if ($key.StartsWith($prefix) -and $key.Length -gt $prefix.Length) {
            $game = $key.Substring($prefix.Length)
            if (-not $games.ContainsKey($game)) { $games[$game] = @{} }
            $games[$game][$s] = $prop.Value
            break
        }
    }
}

$gameNames = @{
    'zombies'                 = '僵尸模式 Zombies';
    'hole_in_the_wall'        = '穿墙洞 HITW';
    'simon_says'              = 'Hypixel Says';
    'santa_says'              = '圣诞 Says';
    'party'                   = 'Party Games';
    'mini_walls'              = '迷你墙 Mini Walls';
    'soccer'                  = '足球';
    'dayone'                  = 'Day One';
    'dragonwars'              = '龙之战';
    'farm_hunt'               = '农场狩猎';
    'pixel_party'             = '像素派对';
    'dropper'                 = '跳楼机';
    'zombies_deadend'         = 'Zombies-死胡同';
    'zombies_badblood'        = 'Zombies-坏血';
    'zombies_alienarcadium'   = 'Zombies-外星街机';
    'zombies_deadend_normal'  = 'Zombies-死胡同(普通)';
    'zombies_badblood_normal' = 'Zombies-坏血(普通)'
}

Write-Host "================= 各小游戏统计 ================="
$keys = $games.Keys | Sort-Object
if ($keys.Count -eq 0) { Write-Host "(未找到扁平统计字段)" }
foreach ($g in $keys) {
    $stats = $games[$g]
    $parts = @()
    foreach ($s in @('wins', 'rounds', 'kills', 'deaths', 'best_round',
                    'zombie_kills', 'total_rounds_survived', 'headshots',
                    'final_kills', 'goals')) {
        if ($null -ne $stats[$s]) { $parts += "$s=$($stats[$s])" }
    }
    $label = if ($gameNames.ContainsKey($g)) { $gameNames[$g] } else { $g }
    Write-Host ("{0,-26} | {1}" -f $label, ($parts -join '   '))
}

# ---------- 5. 嵌套对象型游戏（少数） ----------
Write-Host ""
Write-Host "================= 嵌套对象型统计 ================="
$arcade.PSObject.Properties | Where-Object {
    $_.Value -is [PSCustomObject] -and
    $_.Name -notin @('leaderboardSettings', 'pixelparty', 'disasters')
} | ForEach-Object {
    $label = if ($gameNames.ContainsKey($_.Name)) { $gameNames[$_.Name] } else { $_.Name }
    $inner = @()
    foreach ($k in @('wins', 'games_played', 'best_round', 'fastest_time', 'highest_score')) {
        if ($null -ne $_.Value.$k) { $inner += "$k=$($_.Value.$k)" }
    }
    Write-Host ("{0,-26} | {1}" -f $label, ($inner -join '   '))
}

Write-Host ""
Write-Host "数据来源: https://api.hypixel.net/v2/player?uuid=$uuid"
