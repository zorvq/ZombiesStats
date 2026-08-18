package com.zbmod.hypixel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zombies 统计数据模型。
 * 与 PowerShell 脚本相同的解析规则：
 *   stats.Arcade 下扁平字段 <统计项>_zombies[?_地图[?_难度]]
 */
public class ZombiesStats {

    /** 总体核心统计：wins / best_round / zombie_kills / deaths / ... */
    public final Map<String, Long> overall = new HashMap<>();

    /** 总体杂项：headshots / basic_zombie_kills / tnt_baby_zombie_kills / bullets_shot / bullets_hit */
    public final Map<String, Long> misc = new HashMap<>();

    /** 特殊僵尸击杀：<怪名> -> 数量 */
    public final Map<String, Long> specials = new LinkedHashMap<>();

    /** 最快波次时间（秒）：key = "10"/"20"/"30" 或 "地图|难度|波次" */
    public final Map<String, Long> fastest = new HashMap<>();

    /** 地图统计：地图 -> 难度(agg/normal/hard/rip) -> 统计项 -> 数值 */
    public final Map<String, Map<String, Map<String, Long>>> byMap = new HashMap<>();

    public long get(String stat) {
        return overall.getOrDefault(stat, 0L);
    }

    public long getMisc(String stat) {
        return misc.getOrDefault(stat, 0L);
    }

    public long getFastest(String key) {
        return fastest.getOrDefault(key, -1L);
    }

    public boolean isEmpty() {
        return overall.isEmpty() && byMap.isEmpty();
    }
}
