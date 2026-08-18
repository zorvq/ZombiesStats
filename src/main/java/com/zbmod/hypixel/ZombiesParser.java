package com.zbmod.hypixel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Hypixel /v2/player 响应中解析 Arcade Zombies 统计。
 * 字段命名规则（实测 26.1.2 时代 API）：
 *   <统计项>_zombies                      -> 总体
 *   <统计项>_zombies_<地图>               -> 地图聚合（如 best_round_zombies_deadend）
 *   <统计项>_zombies_<地图>_<难度>        -> 地图+难度（难度: normal/hard/rip）
 *   <怪名>_zombie_kills_zombies           -> 特殊僵尸击杀
 *   fastest_time_<10|20|30>_zombies[...]  -> 最快到达波次（秒）
 */
public final class ZombiesParser {

    private static final Pattern CORE = Pattern.compile(
            "^(wins|deaths|best_round|total_rounds_survived|players_revived|times_knocked_down|"
                    + "windows_repaired|doors_opened|zombie_kills)_zombies(?:_(.+))?$");
    private static final Pattern FASTEST = Pattern.compile("^fastest_time_(\\d+)_zombies(?:_(.+))?$");
    private static final Pattern MISC = Pattern.compile(
            "^(headshots|basic_zombie_kills|tnt_baby_zombie_kills|bullets_shot|bullets_hit)_zombies$");
    private static final Pattern SPECIAL = Pattern.compile("^(.+)_zombie_kills_zombies$");

    private static final String[] MAPS = {"deadend", "badblood", "alienarcadium", "prison"};
    private static final Set<String> DIFFS = Set.of("normal", "hard", "rip");

    private ZombiesParser() {
    }

    /** 解析玩家响应，返回 Zombies 统计（无数据时返回空对象） */
    public static ZombiesStats parse(JsonObject playerResponse) {
        ZombiesStats stats = new ZombiesStats();

        JsonObject arcade = findArcade(playerResponse);
        if (arcade == null) {
            return stats;
        }

        // 兼容少数账号的嵌套 Zombies 对象（合并为扁平字段处理）
        if (arcade.has("Zombies") && arcade.get("Zombies").isJsonObject()) {
            JsonObject nested = arcade.getAsJsonObject("Zombies");
            for (Map.Entry<String, JsonElement> e : nested.entrySet()) {
                if (!arcade.has(e.getKey())) {
                    arcade.add(e.getKey(), e.getValue());
                }
            }
        }

        for (Map.Entry<String, JsonElement> entry : arcade.entrySet()) {
            String key = entry.getKey();
            if (!key.contains("zombies")) {
                continue;
            }
            JsonElement value = entry.getValue();
            // 只处理数值字段（zombies_hideTutorials 等布尔值跳过）
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            long v = value.getAsLong();

            Matcher m;
            if ((m = CORE.matcher(key)).matches()) {
                String stat = m.group(1);
                String suffix = m.group(2);
                if (suffix == null) {
                    stats.overall.put(stat, v);
                } else {
                    String[] md = parseMapDiff(suffix);
                    if (md != null) {
                        stats.byMap.computeIfAbsent(md[0], k -> new HashMap<>())
                                .computeIfAbsent(md[1], k -> new HashMap<>())
                                .put(stat, v);
                    }
                }
            } else if ((m = FASTEST.matcher(key)).matches()) {
                String wave = m.group(1);
                String suffix = m.group(2);
                if (suffix == null) {
                    stats.fastest.put(wave, v);
                } else {
                    String[] md = parseMapDiff(suffix);
                    if (md != null) {
                        stats.fastest.put(md[0] + "|" + md[1] + "|" + wave, v);
                    }
                }
            } else if (MISC.matcher(key).matches()) {
                stats.misc.put(key.substring(0, key.length() - "_zombies".length()), v);
            } else if ((m = SPECIAL.matcher(key)).matches()) {
                stats.specials.put(m.group(1), v);
            }
        }
        return stats;
    }

    /** 取出 stats.Arcade 对象 */
    private static JsonObject findArcade(JsonObject playerResponse) {
        try {
            JsonObject player = playerResponse.getAsJsonObject("player");
            if (player == null) {
                return null;
            }
            JsonObject stats = player.getAsJsonObject("stats");
            if (stats == null) {
                return null;
            }
            return stats.has("Arcade") ? stats.getAsJsonObject("Arcade") : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析后缀为 [地图, 难度或agg]；无法识别返回 null */
    private static String[] parseMapDiff(String suffix) {
        for (String map : MAPS) {
            if (suffix.equals(map)) {
                return new String[]{map, "agg"};
            }
            if (suffix.startsWith(map + "_")) {
                String rest = suffix.substring(map.length() + 1);
                if (DIFFS.contains(rest)) {
                    return new String[]{map, rest};
                }
            }
        }
        return null;
    }
}
