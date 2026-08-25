# Zombies Stats（zbmod）

Fabric 客户端模组：在游戏中通过聊天栏命令查询 **Hypixel Arcade Zombies** 战绩。**无 GUI**，支持批量查询当前在线玩家。

- **Minecraft**: 26.1.2
- **Fabric Loader**: 0.19.3
- **Fabric API**: 0.155.2+26.1.2
- **Java**: 25（必需）
- **映射**: Mojang 官方映射（26.1.2 无 Yarn）

## 构建

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.2
gradlew.bat build
```

> Gradle 发行版已配置为腾讯云镜像（`mirrors.cloud.tencent.com`），国内网络下载快。
> 如需换回官方源，修改 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl`。

产物：`build/libs/zbmod-1.0.0.jar`，放进 `.minecraft/mods/` 即可。

## 命令（全部为客户端命令，在 Hypixel 服务器上也能用）

| 命令 | 说明 |
|---|---|
| `/zombies all`（`/zb all`） | **批量查询当前在线玩家**（Tab 列表），串行请求防限速，结果分块输出到聊天栏 |
| `/zombies` | 查询**自己**的 Zombies 数据 |
| `/zombies <玩家名>` | 查询指定玩家的 Zombies 数据 |
| `/zombies help`（`/zb help`） | 列出命令帮助 |
| `/api-key <key>` | 设置 Hypixel API Key（校验格式，持久化到 `config/zbmod.json`，聊天只显示脱敏值） |
| `/api-key` | 查看当前 Key（脱敏） |

## 批量查询说明

- 玩家列表取自 **Tab 列表**（`ClientPacketListener.getListedOnlinePlayers()`，26.1.2 新 API）
- **多线程并发**：3 个 worker 线程并发执行网络请求，重叠网络延迟
- **全局限速器**：请求起点间隔 ≥550ms（约 109 次/分钟，低于 Hypixel 上限 120 次/分钟），避免频繁操作被限流
- **429 自动退避**：触发限速时全局间隔翻倍（最大 5s）并重试该玩家
- **UUID 缓存**：玩家名 → UUID 结果缓存，重复查询不再打 Mojang API
- Key 失效自动中止；结果按 Tab 列表**原顺序**输出，每 10 人报一次进度，全部完成后按 15 行一块输出
- 结果格式（精简版）：`玩家名: 胜 x | 回合 y波 | KD z | 存活 n | 命中 m%`

## 输出示例

```
[Zombies] jvav1145
胜场 36 | 最佳回合 30波 | KD 184.5 | 存活回合 2,983 | 命中率 63.1%

/zombies all →
jvav1145: 胜 36 | 回合 30波 | KD 184.5 | 存活 2,983 | 命中 63.1%
Technoblade: 胜 0 | 回合 43波 | KD 518.0 | 存活 43 | 命中 73.2%
...
批量查询完成: 成功 22 / 失败 2
```

## 实现说明

- **纯客户端命令**：通过 Fabric API `fabric-command-api-v2` 注册（26.1.2 用 `ClientCommands`，旧 `ClientCommandManager` 已移除）
- **异步请求**：HTTP（Mojang UUID 解析 + Hypixel `/v2/player`）在后台守护线程执行，结果切回主线程显示
- **解析规则**：`stats.Arcade` 扁平字段 `<统计项>_zombies[?_地图[?_难度]]`（如 `wins_zombies_badblood_normal`、`best_round_zombies`、`fastest_time_10_zombies`）
- **§ 代码兼容**：MC 26.1.2 移除了渲染层 § 解析，模组内置 `Styles` 转换器把 `§` 文本转成带样式 Component
- **Key 安全**：Key 只存本地 `config/zbmod.json`，聊天/日志从不输出完整 Key
- **Key 过期提示**：未设置或失效（403）时提示到 `https://developer.hypixel.net/dashboard` 获取（密钥通常 1-2 天后过期）

## 已知限制

- 命中率、最快波次时间依赖账号是否有对应字段（部分玩家数据缺失时显示 `-`）
- 聚合字段（如 `best_round_zombies_deadend`）偶尔不同步，优先展示难度细分数据
- 中文显示需要 CJK 字体资源包（原版字体不含中文字形）

## 开发声明

本模组由 **AI 助手（DeepSeek）** 在用户需求驱动下完成开发，包括：

- 项目骨架搭建与 Gradle/Loom 构建配置（含国内镜像源适配）
- Hypixel API 数据解析与批量查询逻辑
- MC 26.1.2 新 API 适配（通过字节码反编译逐一确认：`ClientCommands`、`getListedOnlinePlayers()`、`GameProfile.name()`、§ 代码渲染移除等）
- 编译验证、错误排查与版本迭代

仓库同步包含两个 PowerShell 查询脚本（`hypixel-arcade.ps1` / `hypixel-zombies.ps1`），同样由 AI 完成。
