# Changelog

## 0.1.0 — 2026-11

首个公开版本。

### 新增

- `GET /ping` — 探活（版本/端口/运行时长/tick）
- `GET /state` — 玩家状态（坐标/朝向/血量/饥饿/模式/手持/准星/维度）
- `GET /snapshot` — 二进制方块快照 ZIP
  - 调色板编码（`air` 固定下标 0；方块种类 >256 自动切 uint16 小端）
  - `height` / `light` / `flags` / `biome` / `entities` 可选通道
  - `delta=1` 增量（LEB128 变体），扫描窗口对齐 16 格网格
- `GET /find` — 按方块注册名返回坐标（凑满 `limit` 提前退出）
- `GET /cmd` — 以玩家身份执行命令并捕获聊天回显（1–2 秒窗口 + 日志兜底）
- `POST /batch` — 全量校验后按 tick 间隔逐条执行
- `POST /batch-file` — 读本机文件执行（`/tmp/cip/` 白名单 + 符号链接解析）
- `POST /chat-say` — 在玩家聊天框本地显示文本
- 客户端聊天监听：`#dsh <文本>` 拦截（取消原消息，不广播）+ 配套桥脚本

### 安全

- 仅绑定 `127.0.0.1`
- `X-CIP-Token` 鉴权（常量时间比较）
- 默认只读；`allow_mutations` 写总闸
- 危险命令需 `confirm=<token>`；命令 denylist
- 速率限制 5 req/s
- 请求写入 `<实例>/logs/cipbridge.log`

### 工程

- 零第三方依赖（JDK HttpServer + Zip + MC 自带 Gson）
- 无 mixin、不注册方块/物品/实体
- HTTP 线程不直接触碰游戏对象（全部投递到游戏线程）
