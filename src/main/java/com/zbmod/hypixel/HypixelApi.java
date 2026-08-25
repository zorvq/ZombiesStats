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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hypixel API 客户端。
 * 所有网络请求都在后台守护线程执行，避免卡主线程（卡顿/超时踢出）。
 *
 * 批量查询：3 个 worker 线程并发 + 全局限速槽（请求起点间隔 ≥ slotMs），
 * 保证总请求速率 ≤ 120 次/分钟（Hypixel 普通 Key 上限），429 时自动退避。
 */
public final class HypixelApi {

    /** 批量查询并发 worker 数 */
    private static final int WORKERS = 3;

    /** 全局限速：两次请求起点之间至少间隔 BASE_SLOT_MS（默认 550ms ≈ 109 次/分钟）；429 后自动翻倍 */
    private static final long BASE_SLOT_MS = 550;
    private static final long MAX_SLOT_MS = 5000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(WORKERS, r -> {
        Thread t = new Thread(r, "zbmod-http");
        t.setDaemon(true);
        return t;
    });

    private static final Gson GSON = new Gson();
    private static final String USER_AGENT = "zbmod/1.1 (Fabric client mod)";

    /** 全局限速：下一个允许的请求起点时间戳 */
    private static final AtomicLong NEXT_SLOT = new AtomicLong();
    private static volatile long SLOT_MS = BASE_SLOT_MS;

    /** 玩家名 -> UUID 缓存（避免重复打 Mojang；无 ID 存 ""） */
    private static final Map<String, String> UUID_CACHE = new ConcurrentHashMap<>();

    private HypixelApi() {
    }

    /** 请求起点取一个限速槽（可能阻塞），保证全局速率不超限 */
    private static void acquireSlot() throws InterruptedException {
        while (true) {
            long now = System.currentTimeMillis();
            long next = NEXT_SLOT.get();
            long target = Math.max(now, next);
            if (NEXT_SLOT.compareAndSet(next, target + SLOT_MS)) {
                long wait = target - now;
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                return;
            }
        }
    }

    /** 命中 429 时放慢全局速率（翻倍，最大 5s） */
    private static void backOff() {
        long cur = SLOT_MS;
        long next = Math.min(cur * 2, MAX_SLOT_MS);
        if (next > cur) {
            SLOT_MS = next;
        }
    }

    public static long slotMillis() {
        return SLOT_MS;
    }

    /** 单玩家查询回调（在后台线程调用；UI 操作请自行切回主线程） */
    public interface Callback {
        void onSuccess(List<String> lines);

        void onError(String message);
    }

    /** 批量查询回调（在后台线程调用；index 为原始列表序号，用于按原顺序输出） */
    public interface ManyCallback {
        void onResult(int index, String playerName, String line);

        void onProgress(int done, int total);

        /** Key 无效/过期时触发并中止剩余查询 */
        void onKeyInvalid(String message);

        void onAllDone();
    }

    /** 异步查询玩家 Zombies 数据，结果通过回调返回 */
    public static void queryAsync(String playerName, String apiKey, Callback callback) {
        EXEC.submit(() -> {
            try {
                String uuid = resolveUuidCached(playerName);
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
     * 批量查询多个玩家：WORKERS 个线程并发 + 全局限速槽（请求起点间隔 ≥ SLOT_MS）。
     * 结果通过回调按原始顺序的 index 返回；Key 无效时通过 onKeyInvalid 中止。
     */
    public static void queryManyAsync(List<String> names, String apiKey, ManyCallback callback) {
        int total = names.size();
        if (total == 0) {
            EXEC.submit(callback::onAllDone);
            return;
        }
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicBoolean keyInvalid = new AtomicBoolean();
        CountDownLatch latch = new CountDownLatch(WORKERS);

        for (int w = 0; w < WORKERS; w++) {
            EXEC.submit(() -> {
                try {
                    while (!keyInvalid.get()) {
                        int idx = next.getAndIncrement();
                        if (idx >= total) {
                            break;
                        }
                        String name = names.get(idx);
                        String line;
                        try {
                            acquireSlot();
                            String uuid = resolveUuidCached(name);
                            if (uuid == null) {
                                line = "§7无此 ID";
                            } else {
                                JsonObject player = fetchPlayer(uuid, apiKey);
                                line = ZombiesPrinter.line(ZombiesParser.parse(player));
                            }
                        } catch (Exception e) {
                            String msg = e.getMessage() == null ? "查询失败" : e.getMessage();
                            if (msg.contains("Key")) {
                                keyInvalid.set(true);
                                callback.onKeyInvalid(msg);
                                break;
                            }
                            line = "§c" + msg;
                        }
                        callback.onResult(idx, name, line);
                        callback.onProgress(done.incrementAndGet(), total);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        // 所有 worker 退出后回调 onAllDone
        EXEC.submit(() -> {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
            }
            callback.onAllDone();
        });
    }

    /** 带缓存的 UUID 解析（无 ID 时缓存空串，不再重复请求） */
    private static String resolveUuidCached(String name) {
        String cached = UUID_CACHE.get(name);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        try {
            String uuid = resolveUuid(name);
            UUID_CACHE.put(name, uuid == null ? "" : uuid);
            return uuid;
        } catch (Exception e) {
            return null;
        }
    }

    /** 玩家名 -> UUID（Mojang API，无需 Hypixel Key；429 退避并重试一次） */
    private static String resolveUuid(String name) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            backOff();
            Thread.sleep(2000);
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString()); // 重试一次
        }
        if (resp.statusCode() == 204 || resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Mojang API 返回 HTTP " + resp.statusCode());
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        return obj.has("id") ? obj.get("id").getAsString() : null;
    }

    /** 拉取玩家数据（Hypixel API；429 退避并重试一次） */
    private static JsonObject fetchPlayer(String uuid, String apiKey) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create("https://api.hypixel.net/v2/player?uuid=" + uuid))
                .header("API-Key", apiKey)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            backOff();
            Thread.sleep(2000);
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString()); // 重试一次
        }
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 403) {
                throw new RuntimeException("API Key 无效或已过期");
            }
            if (resp.statusCode() == 429) {
                throw new RuntimeException("请求过于频繁（429），已自动降低频率");
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
