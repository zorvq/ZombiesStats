package com.zbmod;

import com.zbmod.command.ZbCommands;
import net.fabricmc.api.ClientModInitializer;

/**
 * Zombies Stats 模组入口（纯客户端模组，无 GUI，聊天栏输出）。
 * 通过客户端命令在游戏中查询 Hypixel Arcade Zombies 战绩：
 *   /api-key <key>   设置 Hypixel API Key（保存在 config/zbmod.json）
 *   /zombies [玩家]   查询 Zombies 数据（不填玩家名则查自己）
 *   /zombies all      批量查询当前在线玩家
 */
public class ZbModClient implements ClientModInitializer {

    public static final String MOD_ID = "zbmod";

    @Override
    public void onInitializeClient() {
        ZbCommands.register();
    }
}
