package com.zbmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * API Key 本地持久化（config/zbmod.json）。
 * Key 只存本地文件，绝不输出完整 Key 到聊天栏/日志。
 */
public final class ApiKeyStore {

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("zbmod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static String cachedKey;

    private ApiKeyStore() {
    }

    /** 读取已保存的 Key（首次读取后缓存） */
    public static String get() {
        if (cachedKey != null) {
            return cachedKey;
        }
        try {
            if (Files.exists(FILE)) {
                JsonObject obj = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                if (obj.has("apiKey")) {
                    cachedKey = obj.get("apiKey").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 配置文件损坏时当作未设置
        }
        return cachedKey;
    }

    /** 保存 Key 到磁盘 */
    public static void set(String key) {
        cachedKey = key;
        JsonObject obj = new JsonObject();
        obj.addProperty("apiKey", key);
        try {
            Files.writeString(FILE, GSON.toJson(obj));
        } catch (java.io.IOException e) {
            throw new RuntimeException("无法写入配置文件 " + FILE + ": " + e.getMessage(), e);
        }
    }

    /** 校验 Key 格式：标准 UUID 分段格式 或 纯 32 位十六进制 */
    public static boolean isValidKey(String key) {
        if (key == null) {
            return false;
        }
        return key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || key.matches("[0-9a-fA-F]{32}");
    }

    /** 脱敏显示：只露出前 4 位和后 4 位 */
    public static String mask(String key) {
        if (key == null || key.length() < 12) {
            return "(未设置)";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
