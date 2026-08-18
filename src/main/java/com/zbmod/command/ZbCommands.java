package com.zbmod.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.zbmod.config.ApiKeyStore;
import com.zbmod.hypixel.HypixelApi;
import com.zbmod.util.Styles;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端命令注册（无 GUI，纯聊天栏输出）：
 *   /api-key <key>        设置 Hypixel API Key（持久化到 config/zbmod.json）
 *   /api-key              查看当前 Key（脱敏显示）
 *   /zombies [玩家名]     查询 Zombies 数据（默认查自己）
 *   /zombies all          批量查询当前在线玩家（Tab 列表），结果输出到聊天栏
 *   /zombies help         命令帮助
 *   /zb ...               /zombies 的别名（含 all/help）
 */
public final class ZbCommands {

    public static final String KEY_HINT =
            "§7提示: 请到 §ehttps://developer.hypixel.net/dashboard §7获取 API Key"
                    + "（密钥通常 1-2 天后过期，过期后需重新获取）";

    private static final String[] HELP = {
            "§6===== Zombies Stats 帮助 =====",
            "§e/zombies §7- 查询自己的 Zombies 数据",
            "§e/zombies <玩家名> §7- 查询指定玩家",
            "§e/zombies all §7- 批量查询当前在线玩家（Tab 列表），结果输出到聊天栏",
            "§e/api-key <key> §7- 设置 Hypixel API Key",
            "§e/api-key §7- 查看当前 Key（脱敏）",
            "§7Key 获取: §ehttps://developer.hypixel.net/dashboard§7（密钥通常 1-2 天后过期）"
    };

    private ZbCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            LiteralCommandNode<FabricClientCommandSource> zombies = dispatcher.register(
                    ClientCommands.literal("zombies")
                            .executes(ctx -> querySelf(ctx.getSource()))
                            .then(ClientCommands.literal("help")
                                    .executes(ctx -> help(ctx.getSource())))
                            .then(ClientCommands.literal("all")
                                    .executes(ctx -> queryAll(ctx.getSource())))
                            .then(ClientCommands.argument("player", StringArgumentType.word())
                                    .executes(ctx -> queryPlayer(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "player")))));

            // /zb 作为 /zombies 的别名（共享子命令树：all/help/<玩家> 都可用）
            dispatcher.register(ClientCommands.literal("zb").redirect(zombies));

            dispatcher.register(ClientCommands.literal("api-key")
                    .executes(ctx -> showKey(ctx.getSource()))
                    .then(ClientCommands.argument("key", StringArgumentType.string())
                            .executes(ctx -> setKey(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "key")))));
        });
    }

    /** /zombies help 或 /zb help：列出命令帮助 */
    private static int help(FabricClientCommandSource source) {
        for (String line : HELP) {
            feedback(source, line);
        }
        return 1;
    }

    /** /api-key <key>：校验并保存 Key */
    private static int setKey(FabricClientCommandSource source, String key) {
        if (!ApiKeyStore.isValidKey(key)) {
            feedback(source, "§cKey 格式不正确，应为 32 位十六进制（形如 8-4-4-4-12 的分段格式）");
            return 0;
        }
        try {
            ApiKeyStore.set(key);
            feedback(source, "§aAPI Key 已保存: §7" + ApiKeyStore.mask(key) + "§a（文件: config/zbmod.json）");
        } catch (Exception e) {
            feedback(source, "§c保存失败: " + e.getMessage());
        }
        return 1;
    }

    /** /api-key：查看当前 Key（脱敏） */
    private static int showKey(FabricClientCommandSource source) {
        String key = ApiKeyStore.get();
        if (key == null) {
            feedback(source, "§c未设置 API Key，请使用 §e/api-key <key> §c设置");
            feedback(source, KEY_HINT);
        } else {
            feedback(source, "§7当前 API Key: §e" + ApiKeyStore.mask(key));
        }
        return 1;
    }

    /** /zombies：查自己 */
    private static int querySelf(FabricClientCommandSource source) {
        String name = source.getClient().getUser().getName();
        return queryPlayer(source, name);
    }

    /** /zombies <玩家>：异步查询（HTTP 在后台线程，回包切回主线程） */
    private static int queryPlayer(FabricClientCommandSource source, String playerName) {
        String key = ApiKeyStore.get();
        if (key == null) {
            feedback(source, "§c未设置 API Key，请先执行 §e/api-key <key>");
            feedback(source, KEY_HINT);
            return 0;
        }
        feedback(source, "§7正在查询 §e" + playerName + " §7的 Zombies 数据...");

        HypixelApi.queryAsync(playerName, key, new HypixelApi.Callback() {
            @Override
            public void onSuccess(List<String> lines) {
                for (String line : lines) {
                    feedback(source, line);
                }
            }

            @Override
            public void onError(String message) {
                feedback(source, "§c查询失败: " + message);
                if (message.contains("Key")) {
                    feedback(source, KEY_HINT);
                }
            }
        });
        return 1;
    }

    /**
     * /zombies all 或 /zb all：批量查询当前在线玩家（Tab 列表）。
     * 串行请求（600ms 间隔防限速），结果按 15 行一块输出到聊天栏。
     */
    private static int queryAll(FabricClientCommandSource source) {
        String key = ApiKeyStore.get();
        if (key == null) {
            feedback(source, "§c未设置 API Key，请先执行 §e/api-key <key>");
            feedback(source, KEY_HINT);
            return 0;
        }

        // 26.1.2: Tab 列表 = ClientPacketListener.getListedOnlinePlayers()
        List<String> names = new ArrayList<>();
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            for (PlayerInfo info : conn.getListedOnlinePlayers()) {
                String n = info.getProfile().name();
                if (n != null && !n.isBlank() && !names.contains(n)) {
                    names.add(n);
                }
            }
        }
        if (names.isEmpty()) {
            feedback(source, "§c当前 Tab 列表为空（未连接服务器或大厅无玩家）");
            return 0;
        }

        feedback(source, "§7正在批量查询 §e" + names.size()
                + " §7名在线玩家（约 " + Math.max(1, names.size() * 600 / 1000) + " 秒）...");

        List<String> results = new CopyOnWriteArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        HypixelApi.queryManyAsync(names, key, new HypixelApi.ManyCallback() {
            @Override
            public void onResult(String playerName, String line) {
                results.add("§e" + playerName + "§7: " + line);
                if (line.startsWith("§a")) {
                    success.incrementAndGet();
                } else {
                    fail.incrementAndGet();
                }
            }

            @Override
            public void onProgress(int done, int total) {
                if (done % 10 == 0 || done == total) {
                    feedback(source, "§7批量查询进度: " + done + "/" + total);
                }
            }

            @Override
            public void onKeyInvalid(String message) {
                feedback(source, "§c" + message);
                feedback(source, KEY_HINT);
            }

            @Override
            public void onAllDone() {
                // 每 15 行一块发送，避免单条消息过长
                for (int i = 0; i < results.size(); i += 15) {
                    feedback(source, String.join("\n",
                            results.subList(i, Math.min(i + 15, results.size()))));
                }
                feedback(source, "§a批量查询完成: 成功 " + success.get() + " / 失败 " + fail.get());
            }
        });
        return 1;
    }

    /** 线程安全地把消息送回主线程显示（聊天栏），§ 代码经 Styles 转换 */
    private static void feedback(FabricClientCommandSource source, String message) {
        Minecraft.getInstance().execute(() ->
                source.sendFeedback(Styles.styled(message)));
    }
}
