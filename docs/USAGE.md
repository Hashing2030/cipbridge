# 使用说明

> 本文是面向使用者的操作指南；完整协议见 [`PROTOCOL.md`](PROTOCOL.md)。

## 1. 安装与首次启动

1. 把 `cipbridge-*.jar` 放进实例的 `mods/`；
2. 启动游戏并进入世界；
3. 日志应出现：
   ```
   [cipbridge] token from generated and mirrored to .../config/cipbridge/token.txt
   [cipbridge] http listening on 127.0.0.1:8642
   [cipbridge] request log -> .../logs/cipbridge.log
   ```
4. token 在 `<实例>/config/cipbridge/token.txt`，配置在 `<实例>/config/cipbridge-client.toml`。

> 默认只读。需要执行命令时把 `allow_mutations` 改为 `true` 并**完全重启游戏**。

## 2. 用 `cipctl.py`（推荐）

```bash
PY=python3                       # 需要 numpy 才能解码 snapshot
CT=./tools/cipctl.py
INSTANCE=~/minecraft/instances/my-pack     # 你的实例目录（含 config/ 的那层）

$PY $CT --instance "$INSTANCE" --probe ping
$PY $CT --instance "$INSTANCE" state
$PY $CT --instance "$INSTANCE" snap --r 16 --channels blocks,height,entities
$PY $CT --instance "$INSTANCE" find --block minecraft:diamond_ore --r 48
$PY $CT --instance "$INSTANCE" cmd "/time set day"
$PY $CT --instance "$INSTANCE" cmd "/kill @e[type=rabbit]" --confirm
$PY $CT --instance "$INSTANCE" batch --file /tmp/cip/batch.txt
```

`snap` 默认只打印一行摘要（方块统计 Top-8、实体、玩家位置、耗时、文件大小）；需要细节时用 `--detail`、`--at x y z`。

不带 `--instance` 时可用 `--port` / `--token` / `--token-file` 直接指定。

## 3. 游戏内聊天桥（可选）

把聊天框变成与 AI 对话的入口。链路：

```
聊天框 "#dsh 你好"
  → 模组拦截（不广播）→ /tmp/cip/chat-inbox.jsonl
  → cip_chat_bridge.py 调 `dsh --profile headless`（若你用 DSH）
  → 回复 POST /chat-say → 聊天框显示
```

需要本机有 `dsh` 与可用模型。基本配置（macOS 上以 LaunchAgent 常驻为例）：

- 运行副本放在 `~/Library/Application Support/cip-bridge/chat_bridge.py`；
- plist 里设 `CIP_TOKEN_FILE=<实例>/config/cipbridge/token.txt`，`PATH` 要包含 `dsh` 所在目录（`dsh` 的 shebang 是 `/usr/bin/env node`）；
- 模型 key 建议放在 600 权限文件里，运行时读入并作为环境变量注入，**不要写进源码**。

也可以手动跑：

```bash
CIP_TOKEN_FILE=<实例>/config/cipbridge/token.txt python3 tools/cip_chat_bridge.py --once
```

## 4. 零 token 监测（可选）

`tools/cip_vigil.py` 每 5 秒轮询 `/state`，写 JSONL 并在事件（死亡、掉血、低血、切维度、瞬移、周边实体激增）时发系统通知（macOS：`osascript`）。

```bash
python3 tools/cip_vigil.py          # 前台
# 或做成 launchd/cron 常驻
```

日志默认在 `~/Library/Application Support/cip-vigil/watch.jsonl`（可用环境变量改路径）。

## 5. 排错

| 现象 | 排查 |
|---|---|
| `curl` 连不上 | 游戏是否进了世界？端口是否被 +1 重试？看日志 `HTTP listening on 127.0.0.1:<port>` |
| `403 forbidden` | token 不对（`X-CIP-Token`），或实例换了而脚本仍指向旧实例 |
| `403 mutations_disabled` | `allow_mutations` 还是 false，改完要**重启游戏** |
| `429 rate_limited` | 超过 5 请求/秒，间隔 1 秒重试 |
| `503 not_ingame` | 客户端没进世界（标题界面/加载中） |
| 界面卡一下 | 快照是同步扫描，极大半径（≥96）可能有可感知停顿；可降 `r` 或分批 |
