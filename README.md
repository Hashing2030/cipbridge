# CIP Bridge

> **本地 HTTP 桥接模组：让 AI 读取你的 Minecraft 世界，并在你允许时替你执行命令。**
> A client-side NeoForge mod that exposes a **local-only** HTTP API so external AI agents can read the world and (optionally) act inside it.

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-3C8527?style=flat-square)](https://minecraft.net)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.248%2B-EE7C0A?style=flat-square)](https://neoforged.net)
[![Side](https://img.shields.io/badge/environment-client-blue?style=flat-square)](#)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/version-0.1.0-lightgrey?style=flat-square)](#)

---

## 这是什么

Minecraft 的客户端把"世界长什么样"封装在渲染和网络里，外部程序很难读。**CIP Bridge** 在客户端进程内开一个只监听 `127.0.0.1` 的 HTTP 端口，把玩家状态、附近方块/实体快照、方块坐标查询暴露成 JSON / 二进制接口；并可选地提供一个受控的命令通道。

它面向这些场景：

- 让本地 AI 代理（如 DeepSeek Harness）**读懂你正在玩的存档**：你在哪、血量、周围有什么矿、附近有什么实体；
- 让 AI **在你许可的时间与范围内动手**：给物品、调时间天气、清箱子、铺方块；
- 把"游戏内聊天框"变成与 AI 对话的入口（配套脚本，见「工具」）。

**它不做什么**：不联网、不收集遥测、不改你的存档文件、不做服务端。模组只在本机 `127.0.0.1` 上说话，且默认**只读**。

## 特性一览

| 能力 | 端点 | 说明 |
|---|---|---|
| 探活 | `GET /ping` | 返回模组版本、端口、运行时长、游戏 tick |
| 玩家状态 | `GET /state` | 坐标/朝向/血量/饥饿/模式/手持/准星目标/维度 |
| 执行命令 | `GET /cmd` | 经游戏自身执行并**捕获聊天回显**（写端点） |
| 方块快照 | `GET /snapshot` | 服务端线程扫描 → 调色板编码 → ZIP（极省空间） |
| 方块查询 | `GET /find` | 按注册名返回坐标列表（只回坐标，最省） |
| 批量命令 | `POST /batch` | 全量校验后按 tick 间隔逐条执行（写端点） |
| 文件批量 | `POST /batch-file` | 读本机文件逐行执行（路径白名单） |
| 聊天回显 | `POST /chat-say` | 把文本显示到玩家聊天框（客户端本地，不写世界） |

设计取舍（详见 [`docs/PROTOCOL.md`](docs/PROTOCOL.md)）：

- **HTTP 线程绝不碰游戏对象**：一切世界/客户端访问都投递到游戏线程执行（`onServer` / `onClient`），避免崩溃与数据竞争。
- **快照是二进制、不是图片**：`blocks.bin`（每方块 1 字节调色板下标，方块种类 >256 时自动切 16-bit 小端）、`height.bin`（int16 小端）、可选 `light/flags/biome`、`header.json`。64 半径（约 106 万方块）压缩后通常 **30–120 KB**。
- **增量**：`delta=1` 只回相对上次同参数快照的变化（LEB128 变体编码），配合对齐到 16 格的扫描窗口，同区块内小幅移动也能命中缓存。
- **零第三方依赖**：HTTP 用 JDK 自带 `com.sun.net.httpserver`，压缩用 `java.util.zip`，JSON 用 MC 自带 Gson，PNG（可选）用 `javax.imageio`。**不加 mixin**，只用公开 API。

## 环境要求

| 项 | 要求 |
|---|---|
| Minecraft | **1.21.1** |
| 加载器 | **NeoForge 21.1.247+**（开发/实测于 21.1.248） |
| 端 | **客户端**（`side = CLIENT`，不注册方块/物品/实体，可与大型整合包共存） |
| 构建 | JDK **21**（仅从源码构建时需要） |

## 安装

1. 把 `cipbridge-<version>.jar` 放进游戏实例的 `mods/` 目录；
2. 启动游戏、进入世界；
3. 日志出现 `[cipbridge] HTTP listening on 127.0.0.1:8642` 即就绪；
4. token 自动生成在 `<实例>/config/cipbridge/token.txt`（权限 600），配置在 `<实例>/config/cipbridge-client.toml`。

> 需要一次性的命令执行能力时，把 `cipbridge-client.toml` 里的 `allow_mutations` 改为 `true` 并**完全重启游戏**（配置只在启动时读取）。

## 快速开始

```bash
TOKEN=$(cat <实例>/config/cipbridge/token.txt)

# 探活
curl -s http://127.0.0.1:8642/ping

# 玩家状态
curl -s -H "X-CIP-Token: $TOKEN" http://127.0.0.1:8642/state

# 找附近钻石矿
curl -s -H "X-CIP-Token: $TOKEN" "http://127.0.0.1:8642/find?block=minecraft:diamond_ore&r=48"

# 快照（r=16，返回 ZIP）
curl -s -H "X-CIP-Token: $TOKEN" "http://127.0.0.1:8642/snapshot?r=16&channels=blocks,height" -o snap.zip

# 执行命令（需 allow_mutations=true）
curl -s -H "X-CIP-Token: $TOKEN" "http://127.0.0.1:8642/cmd?c=/time%20set%20day"
```

## HTTP API

### `GET /state`
返回玩家快照：`pos / block_pos / yaw / pitch / hp / food / xp_level / gm / sneaking / on_ground / in_water / in_lava / facing / look_at / main_hand`，以及 `dim / world / tick / nearby_entities`。

游戏未就绪（无客户端/无玩家）时返回 `503 {"code":"not_ingame"}`。

### `GET /cmd?c=<urlencoded>&confirm=<token>`
- `c` 必须以 `/` 开头，否则 `400`；
- 命令由游戏以**玩家身份**执行，并捕获 1–2 秒内的聊天回显（无输出时读日志兜底）；
- 危险命令（`clear`、`kill @e/@a`、`fill … air/hollow`、`setblock … air`、`clone … force`、所有 `execute`）必须带 `confirm=<token>`，用常量时间比较；
- denylist（`stop op deop ban kick whitelist pardon save-off`）直接 `403`；
- `allow_mutations=false` 时 `403 mutations_disabled`。

### `GET /snapshot?r=&y0=&y1=&channels=&delta=`
返回 `application/zip`：

```
header.json        元数据：tick/world/dim/origin/size/order/palette/counts/player/entities/unloaded…
blocks.bin         uint8[nx*ny*nz]，索引 (yi*nz+zi)*nx+xi（x 最快），值为 palette 下标
blocks16.bin       方块种类 >256 时的 uint16 小端替代
height.bin         int16[nx*nz] 小端，每列最高非空气方块 y（无则 -32768）
light.bin          uint8 方块光照（可选）
flags.bin          uint8 位标志：block_entity/fluid/solid/replaceable/light_source/signal_source/climbable
biome.bin          uint8，每 4×4 列一个（可选）
blocks_delta.bin   delta=1 时：LEB128(索引差) + 数值
```

- `r` 默认 32、上限 128，水平取整到 16 的倍数；`y0/y1` 默认玩家 y±32、钳制 -64..320；
- `channels` 默认 `blocks,height`，可加 `light,flags,biome,entities`；
- 扫描窗口的 `origin` 对齐到 16 格网格，使同区块内小幅移动时 `delta` 缓存仍可命中。

### `GET /find?block=&r=&y0=&y1=&limit=`
只回坐标：`{"found":[[x,y,z],…],"visited_chunks":…,"truncated":…}`，凑满 `limit` 提前退出。

### `POST /batch` / `POST /batch-file`
```json
// POST /batch
{"commands":["/time set day","/weather clear"],"spacing_ticks":1,"stop_on_error":true,"verbose":false}
// POST /batch-file
{"path":"/tmp/cip/batch.txt","spacing_ticks":1}
```
- 先**全量校验**：任一命令非法即 `403` 并报出首个行号，不执行任何一条；
- `commands` 上限 512，`spacing_ticks` 范围 0–20；`/batch-file` 只允许 `/tmp/cip/` 前缀（路径规范化 + 符号链接解析后校验）；
- 静默模式只回 `{"ok":true,"sent":N,"failed":[...],"ms":…}`。

### `POST /chat-say`
```json
{"text":"任意文本"}
```
把文本显示到玩家聊天框（客户端本地消息，不广播、不写世界）。需要 token，**不受 `allow_mutations` 限制**；文本上限 4000 字符，超出截断并标注。

## 配置

`<实例>/config/cipbridge-client.toml`

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关；false 时不启动 HTTP |
| `port` | `8642` | 端口（冲突自动 +1 重试至多 10 次） |
| `allow_mutations` | `false` | 写权限总闸（`/cmd` `/batch` `/batch-file`） |
| `rate_limit` | `5` | 每秒请求数（滑动窗口） |
| `scan_budget` | `4` | 预留（当前快照为服务端线程同步扫描） |
| `radius_max` | `128` | 快照半径上限（硬顶 128） |
| `token` | `""` | 留空则启动时生成并写入 `config/cipbridge/token.txt` |

## 安全模型

- 只绑定 **`127.0.0.1`**，不监听 `0.0.0.0`；
- 每个请求校验 `X-CIP-Token`（常量时间比较），token 落盘 600；
- 默认**只读**；写端点受 `allow_mutations` 与「危险命令 confirm」两道闸；
- 危险命令需要显式 `confirm=<token>`；命令 denylist 兜底；
- 速率限制 5 请求/秒；
- 所有请求写入 `<实例>/logs/cipbridge.log`（时间/路径/状态/耗时）。

**它不修改你的存档文件**：所有世界改动都通过游戏自己的命令执行，由游戏保证一致性。

## 工具（可选，`tools/` 目录）

| 脚本 | 用途 |
|---|---|
| `cipctl.py` | 命令行客户端：`ping / state / snap / find / cmd / batch`，`snap` 会把 ZIP 解码成 numpy 数组并打印摘要 |
| `cip_chat_bridge.py` | **游戏内聊天桥**：把聊天框里的 `#dsh <文本>` 交给 `dsh --profile headless` 处理，再经 `POST /chat-say` 显示回复。需要本机有 `dsh` 与可用模型 |
| `cip_vigil.py` | 零 token 监测：每 5 秒轮询 `/state`，记录 JSONL 并在事件（死亡/掉血/切维度等）时发系统通知 |

脚本都支持环境变量覆盖路径（如 `CIP_TOKEN_FILE`、`CIP_INSTANCE` 等），细节见脚本头部注释与 [`docs/USAGE.md`](docs/USAGE.md)。

## 从源码构建

```bash
cd mod
./gradlew build          # 产物：mod/build/libs/cipbridge-<version>.jar
```

首次构建会下载 Gradle 与 NeoForge 开发依赖。要求 JDK 21。

## 项目结构

```
.
├── mod/                       NeoForge 模组源码（ModDevGradle）
│   └── src/main/java/dev/cip/bridge/
│       ├── CipBridge.java     入口：注册配置、启动 HTTP、接线端点
│       ├── Config.java        客户端配置（ModConfigSpec）
│       ├── http/              HttpApi（路由/token/限流/错误）+ 各端点处理器
│       ├── exec/              线程桥接、命令执行/回显捕获、聊天监听
│       └── world/             扫描器、调色板、快照编码、增量缓存
├── tools/                     客户端脚本（cipctl.py / 聊天桥 / 监测）
├── docs/
│   ├── PROTOCOL.md            完整协议规范（线格式、字段、语义）
│   └── USAGE.md               使用说明
├── LICENSE                    MIT
└── CHANGELOG.md
```

## 隐私

- 模组**不发起任何外部网络请求**，不收集遥测；
- 数据只在 `127.0.0.1` 上被本机进程读取；
- 是否把数据交给某个 AI 模型，由你自己配置的客户端/脚本决定，与模组无关。

## 常见问题

**Q: 会不会和我装的整合包冲突？**
A: 它不注册方块/物品/实体、不加 mixin、不碰渲染，只订阅客户端公开事件并开一个本地端口。已在 200+ 模组的整合包上实测共存。

**Q: 为什么默认只读？**
A: 读世界几乎无害，写世界不是。写权限需要你显式改配置并重启，危险命令还要额外 `confirm`。

**Q: 端口被占用？**
A: 会自动 +1 重试（至多 10 次），实际端口以日志 `HTTP listening on 127.0.0.1:<port>` 为准，或查 `<实例>/config/cipbridge-client.toml`。

## License

[MIT](LICENSE) © 2026 Hashing2030

> 本项目与 Mojang Studios / Microsoft 无关；Minecraft 是 Mojang Studios 的商标。使用本模组造成的任何后果由使用者自行承担。

---

## English

**CIP Bridge** is a client-side NeoForge 1.21.1 mod that exposes a local-only HTTP API (`127.0.0.1:8642`) for external AI agents:

- `GET /state` — player state (position, HP, gamemode, look-at, held item…)
- `GET /snapshot` — compact binary block/entity snapshot (palette-coded, zlib-zipped, optional delta)
- `GET /find` — search a block by registry name and return coordinates
- `GET /cmd` — run a command as the player and capture the chat feedback (write; gated)
- `POST /batch`, `POST /batch-file` — validated batch execution
- `POST /chat-say` — display text in the player's chat box

Zero third-party dependencies, no mixins, loopback-only, token-authenticated, **read-only by default**. See [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the wire format.
