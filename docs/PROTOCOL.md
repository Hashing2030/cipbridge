# CIP Bridge 规范（SPEC，冻结 v1.1）

> 唯一权威文档。所有任务卡引用本规范，不得各自复述或篡改。
> 工程根：`cipbridge/mod/`　任务卡：`cipbridge/tasks/`　本文件：`cipbridge/SPEC.md`

## 1. 目标

一个客户端侧 NeoForge 模组，在游戏内开一个只监听 `127.0.0.1` 的本地 HTTP 端口，让 AI（DSH 里的 lead）能：
① 读玩家状态　② 执行命令并拿到回显　③ 拉取附近方块/实体快照（纯二进制）　④ 定向查询方块坐标　⑤ 批量下发命令（含读本地文件）。

## 2. 硬约束（违反即"重大不成立"，必须停下上报 lead，不得自行绕过）

1. 目标版本：**Minecraft 1.21.1 / NeoForge 21.1.248 / JDK 21**。
2. **零第三方库**：HTTP 用 JDK `com.sun.net.httpserver`；压缩用 `java.util.zip.Deflater`；JSON 用 MC 自带 Gson（`com.google.gson`）；PNG（第二阶段）用 `javax.imageio`。
3. **不加 mixin、不碰渲染、不注册方块/物品/实体**，只用公开 API——这是与 212 个 mod 共存的前提。
4. **线程模型**：HTTP 线程绝不直接访问 level；所有世界访问投递到服务端线程执行——单机用 `Minecraft.getInstance().getSingleplayerServer()` 的 `ServerLevel`，多人（无单机服务器时）退回客户端线程。用 `CompletableFuture` + 超时回传。
5. **只绑 `127.0.0.1`**，不监听 0.0.0.0。
6. **读/写分离**：读（/state /snapshot /find）默认开；写（/cmd /batch /batch-file）受 `allow_mutations` 控制，**默认关**。

## 3. 配置默认值

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `port` | 8642 | 冲突自动 +1 重试至多 10 次 |
| `allow_mutations` | **false** | 写权限，默认关 |
| `rate_limit` | 5 | req/s |
| `scan_budget` | 4 | 每 tick 扫描区块数 |
| `radius_max` | 128 | 快照半径上限 |
| `token` | 启动生成 | 32 字节随机 hex，写 `config/cipbridge/token.txt`（600） |

## 4. 端点规格

通用：鉴权 `X-CIP-Token: <token>`；限流超限 `429`；错误 `{"ok":false,"error":"...","code":"..."}`。

### 4.1 GET /state
玩家状态 JSON（坐标/朝向/血量/饥饿/经验等级/模式/潜行/脚下水岩浆/准星目标/手持/附近实体数/维度/世界名/tick）。

### 4.2 GET /cmd?c=<urlencoded>&wait=<ms>
- `c` 必须以 `/` 开头，否则 400。
- `wait` 默认 2000，上限 5000。
- denylist（首 token 匹配，命中 403）：`stop op deop ban kick whitelist pardon save-off`。
- 响应：`{"ok":true,"sent":"/...","took_ms":1430,"reply":["..."]}`；无输出时 `reply:[]` 且附 `"hint":"no chat output"`。
- 实现：发命令前挂临时 `ClientChatReceivedEvent` 监听，收集 wait 窗口内所有行；窗口无输出则兜底读 `logs/latest.log` 尾部含 `[CHAT]` 的行。

### 4.3 GET /snapshot?r=&y0=&y1=&channels=&delta=
- `r` 默认 32，上限 128，实际取整到 16 的倍数。
- `y0`/`y1` 默认玩家 y±32，钳制 -64..320。
- `channels` 逗号分隔：`blocks,height,light,flags,biome,entities`；**默认 `blocks,height`**（其余显式请求才带）。
- `delta=1`：相对上次同参数快照只回变化。
- 响应 `application/zip`，含：
  - `header.json`
  - `blocks.bin`：uint8[nx*ny*nz]，索引 `(yi*nz+zi)*nx+xi`（**x 最快**），值为调色板下标；方块种类 >255 时改 `blocks16.bin`（uint16 小端）+ header 里 `bytes_per_block:2`
  - `height.bin`：int16[nx*nz]（**小端**），每列最高非空气方块 y；无则 -32768
  - `light.bin`：uint8[nx*ny*nz]（可选）
  - `flags.bin`：uint8[nx*ny*nz]，位标志（可选）
  - `biome.bin`：uint8[ceil(nx/4)*ceil(nz/4)]（可选）
  - `blocks_delta.bin`：仅 delta=1，`varint(索引差)+uint8(新值)` 交替
- `header.json` 字段：`v,tick,ms,world,dim,origin[3],size[3],order:"x-fastest",palette[],flags_legend[],biome_palette[],player{},counts{},entities[],delta{base_tick,changed},unloaded[][2],truncated`
- `palette`：去重方块注册名表；`air` 固定下标 0。
- `unloaded`：未加载区块坐标，对应位置填 0 但不可信，客户端脚本必须排除。

### 4.4 GET /find?block=&r=&y0=&y1=&limit=
只回坐标：`{"ok":true,"block":"...","r":48,"found":[[x,y,z],...],"visited_chunks":36,"ms":12,"truncated":false}`。

### 4.5 POST /batch {"commands":[...],"spacing_ticks":1,"stop_on_error":true,"verbose":false}
- `commands` 上限 512；`spacing_ticks` 默认 1（=50ms），范围 0..20。
- 先**全量校验**：任一命令非法（缺 `/`、控制字符、denylist）→ 403 报首个（`{"code":..,"index":i,"command":".."}`），不执行任何一条。`allow_mutations=false` → 403 mutations_disabled。
- 校验通过才逐条下发（每 tick 1 条）；执行错误记 `failed:[{"i":..,"why":".."}]`，`stop_on_error=true` 遇错即停。
- `verbose=false` 只回 `{"ok":true,"sent":N,"failed":[...],"ms":..}`；`verbose=true` 才回逐条输出（走 CommandRunner 带回显）。

### 4.6 POST /batch-file {"path":"/tmp/cip/batch.txt","spacing_ticks":1}
- 模组读本地文件执行（每行一条命令）。
- **路径白名单**：只允许 `/tmp/cip/` 前缀；否则 403。

### 4.7 POST /chat-say
- body `{"text":"..."}`；需要 `X-CIP-Token`；**不受 `allow_mutations` 限制**（纯客户端显示，不写世界）。
- 处理：HTTP 线程只解析+校验 → `ThreadBridge.onClient` → `player.displayClientMessage(Component.literal(text), false)`。
- 文本上限 4000 字符，超出截断并补 `…(truncated)`。
- 游戏未就绪（`Minecraft.getInstance()`/player 为 null）→ 503 `not_ingame`；token 错 → 403；非法 JSON → 400。

### 4.8 聊天桥（inbox 契约）
- 模组捕获玩家在聊天框发出的 `#dsh <文本>`：**取消原消息**（不广播/不执行），追加一行到 `/tmp/cip/chat-inbox.jsonl`：
  `{"t":"YYYY-MM-DD HH:MM:SS","text":"<文本>"}`
- 外部桥脚本（`cipbridge/cip_chat_bridge.py`，LaunchAgent `com.cip.chatbridge`）增量读取该文件，对每条执行
  `DEEPSEEK_API_KEY=$(cat ~/.dsh/secrets/deepseek_api_key) dsh --profile headless --json "<文本>"`
  （key **只**从该 600 文件注入，绝不写入源码/配置/日志），取 `final.text`，再 `POST /chat-say` 显示回游戏。
- v1 为一消息一会话（不传 `--session-id`）；`--session-id` 续用会话列为后续可选。
- 失败必须可见：headless 报错/超时 → 把错误摘要回显到聊天框。

## 5. 工程结构

包 `dev.cip.bridge`，类清单（按卡实现顺序）：
`CipBridge`（@Mod 入口）、`Config`、`http/HttpApi`（路由/token/限流/错误）、`http/Json`、`exec/ThreadBridge`（onServer/onClient）、`exec/CommandRunner`、`world/Scanner`（分片）、`world/Palette`、`world/SnapshotCodec`、`world/EntityList`、`world/Find`。
`META-INF/neoforge.mods.toml`：依赖 `neoforge [21.1.247,)` 与 `minecraft [1.21.1,1.22)`，`side="CLIENT"`；**不写 `displayTest`**（NeoForge 1.21.1/FML 4 无此键，那是 Forge 时代键；本模组客户端专用、无网络，无需 DisplayTest）。

## 6. 线程与扫描模型

- `/snapshot` 在**服务端线程同步执行**（`ThreadBridge.onServer`，超时 10 s）：`ServerLevel.getChunk(cx,cz)` 加载区块 + 遍历取方块。默认 r=32、常见 r≤64 时区块基本已在渲染距离内（单机内嵌服已加载），扫描是遍历内存里的区块数据，耗时可忽略。
- **per-tick 分片（每 tick 4 区块）降级为可选优化**：若 CB-12 实测发现大半径（≥96）有可感知卡顿，再加 `ServerTickEvent` + 任务队列做异步切片。当前不实现，正确性优先。
- 不手动打 chunk ticket（`getChunk` 自然加载、随距离自然卸载）；未加载成功（异常/超界）记入 `header.unloaded`。
- 半径上限 128，默认 32。

## 7. 安全与写闸门

- token 必须校验；所有请求写 `logs/cipbridge.log`（时间/来源/参数/耗时）。
- 写操作（allow_mutations=true 时）再加两道闸：
  - 危险模式（`/fill ... air`、`/fill ... hollow`、`/kill @e`、`/clear`、覆盖箱子区）要求 `confirm=<token>`。
  - 写前对目标包围盒做 pre-image 记录（记录原方块，供反向还原）。

## 8. 执行者守则（所有 subagent 必须遵守）

- **可自行解决**：语法错误、路径/目录拼写、Gradle 配置细节、编译报错修复、本地环境小问题。
- **不得自行解决（必须停下，回报 lead 现象 + 已确认事实，等指示）**：架构冲突、破坏 §2 线程模型/零依赖/不 mixin 等硬约束、所依赖的 NeoForge/MC API 不存在或行为与预期矛盾导致"代码重大不成立"、需要引入第三方库或 mixin 才能继续。
- 交付前自检：`./gradlew build` 通过；报告里写清楚改了什么文件、验收命令的原始输出。
- **进度日志（强制）**：执行全程把每个问题、解决方案、关键决策**随时追加**写入 `cipbridge/tasks/logs/CB-NN-journal.md`（NN=卡号），不要收尾才补。lead 靠这份文件排查与了解进度。
