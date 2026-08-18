package com.zbmod.hypixel;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 ZombiesStats 格式化为聊天栏文本（无 GUI，纯文字）。
 * 精简版输出：胜场 / 最佳回合 / KD / 存活回合 / 命中率。
 */
public final class ZombiesPrinter {

    private ZombiesPrinter() {
    }

    public static List<String> print(ZombiesStats s, String playerName) {
        List<String> lines = new ArrayList<>();
        lines.add("§6§l[Zombies] §e" + playerName);

        if (s.isEmpty()) {
            lines.add("§7该玩家没有 Zombies 数据");
            return lines;
        }

        lines.add("§a胜场 §f" + n(s.get("wins"))
                + " §a| §b最佳回合 §f" + s.get("best_round") + "波"
                + " §a| §bKD §f" + kd(s)
                + " §a| §7存活回合 §f" + n(s.get("total_rounds_survived"))
                + " §a| §7命中率 §f" + accuracy(s) + "%");
        return lines;
    }

    /** 单行精简结果（GUI 行内展示用）：胜场/最佳回合/KD/存活回合/命中率 */
    public static String line(ZombiesStats s) {
        if (s.isEmpty()) {
            return "§7无 Zombies 数据";
        }
        return "§a胜 §f" + n(s.get("wins"))
                + " §7| §b回合 §f" + s.get("best_round") + "波"
                + " §7| §bKD §f" + kd(s)
                + " §7| §b存活 §f" + n(s.get("total_rounds_survived"))
                + " §7| §b命中 §f" + accuracy(s) + "%";
    }

    /** KD = 僵尸击杀 / 死亡次数（死亡为 0 时显示 ∞） */
    private static String kd(ZombiesStats s) {
        long kills = s.get("zombie_kills");
        long deaths = s.get("deaths");
        if (deaths <= 0) {
            return kills > 0 ? "∞" : "-";
        }
        return String.format("%.1f", (double) kills / deaths);
    }

    /** 命中率 = 命中弹 / 总射击弹（无弹药数据时显示 -） */
    private static String accuracy(ZombiesStats s) {
        long shot = s.getMisc("bullets_shot");
        long hit = s.getMisc("bullets_hit");
        if (shot <= 0) {
            return "-";
        }
        return String.format("%.1f", hit * 100.0 / shot);
    }

    private static String n(long v) {
        return String.format("%,d", v);
    }
}
