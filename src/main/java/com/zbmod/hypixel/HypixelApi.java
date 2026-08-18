package com.zbmod.hypixel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hypixel API 客户端。
 * 所有网络请求都在后台守护线程执行，避免卡主线程（卡顿/超时踢出）。
 */
public final class HypixelApi {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zbmod-http");
        t.setDaemon(true);
        return t;
    });

    private static final Gson GSON = new Gson();
    private static final String USER_AGENT = "zbmod/1.0 (Fabric client mod)";

    private HypixelApi() {
    }

    /** 单玩家查询回调（在后台线程调用；UI 操作请自行切回主线程） */
    public interface Callback {
        void onSuccess(List<String> lines);

        void onError(String message);
    }

    /** 批量查询回调（在后台线程调用） */
    public interface ManyCallback {
        void onResult(String playerName, String line);

        void onProgress(int done, int total);

        /** Key 无效/过期时触发并中止剩余查询 */
        void onKeyInvalid(String message);

        void onAllDone();
    }

    /** 异步查询玩家 Zombies 数据，结果通过回调返回 */
    public static void queryAsync(String playerName, String apiKey, Callback callback) {
        EXEC.submit(() -> {
            try {
                String uuid = resolveUuid(playerName);
                if (uuid == null) {
                    callback.onError("未找到玩家 " + playerName + "（Mojang 无此 ID）");
                    return;
                }
                JsonObject player = fetchPlayer(uuid, apiKey);
                ZombiesStats stats = ZombiesParser.parse(player);
                callback.onSuccess(ZombiesPrinter.print(stats, playerName));
            } catch (Exception e) {
                callback.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    /**
     * 批量查询多个玩家：串行执行，每请求间隔约 600ms（约 100 次/分钟），
     * 避免触发 Hypixel 限速。Key 无效时通过 onKeyInvalid 中止后续查询。
     */
    public static void queryManyAsync(List<String> names, String apiKey, ManyCallback callback) {
        EXEC.submit(() -> {
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String line;
                try {
                    String uuid = resolveUuid(name);
                    if (uuid == null) {
                        line = "§7无此 ID";
                    } else {
                        JsonObject player = fetchPlayer(uuid, apiKey);
                        line = ZombiesPrinter.line(ZombiesParser.parse(player));
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "查询失败" : e.getMessage();
                    if (msg.contains("Key")) {
                        callback.onKeyInvalid(msg);
                        return;
                    }
                    line = "§c" + msg;
                }
                callback.onResult(name, line);
                callback.onProgress(i + 1, names.size());
                if (i < names.size() - 1) {
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            callback.onAllDone();
        });
    }

    /** 玩家名 -> UUID（Mojang API，无需 Hypixel Key） */
    private static String resolveUuid(String name) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 204 || resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Mojang API 返回 HTTP " + resp.statusCode());
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        return obj.has("id") ? obj.get("id").getAsString() : null;
    }

    /** 拉取玩家数据（Hypixel API） */
    private static JsonObject fetchPlayer(String uuid, String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://api.hypixel.net/v2/player?uuid=" + uuid))
                .header("API-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 403) {
                throw new RuntimeException("API Key 无效或已过期");
            }
            if (resp.statusCode() == 429) {
                throw new RuntimeException("请求过于频繁（429），请稍后再试");
            }
            throw new RuntimeException("Hypixel API 返回 HTTP " + resp.statusCode());
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!obj.has("success") || !obj.get("success").getAsBoolean()) {
            throw new RuntimeException(obj.has("cause") ? obj.get("cause").getAsString() : "未知错误");
        }
        return obj;
    }
}
