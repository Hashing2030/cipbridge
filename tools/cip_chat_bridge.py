#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CIP Bridge 聊天桥（CB-15）

把游戏内聊天框里 `#dsh <文本>` 变成一次 DSH headless 对话，再把回复显示回游戏聊天框。

链路：
    Minecraft mod（CB-14）
      ├─ 捕获 `#dsh ...` → 取消原消息 → 追加 /tmp/cip/chat-inbox.jsonl  {"t","text"}
      └─ POST /chat-say {"text"} → displayClientMessage

    本脚本
      ├─ 增量读取 inbox（state.json 记字节偏移，重启不重复）
      ├─ 每条消息跑一次 `dsh --profile headless --json "<文本>"`（v1 一消息一会话，不传 --session-id）
      ├─ 从末行 final 事件取 text；reason.kind=="error" 或超时/退出码非 0 → 可读错误当回复
      └─ POST http://127.0.0.1:8642/chat-say（X-CIP-Token），失败重试 2 次后记日志放弃

安全：DeepSeek API key **只**在运行时从 600 文件读取，作为子进程环境变量 DEEPSEEK_API_KEY 注入；
      绝不写进源码/配置/命令行参数/日志。日志里只有路径、长度、状态码。

--------------------------------------------------------------------
安装（源码 → 运行副本 → plist → launchctl）
--------------------------------------------------------------------
  APP="$HOME/Library/Application Support/cip-bridge"   # 真实路径里就有空格，务必整体加引号
  mkdir -p "$APP"
  cp cipbridge/cip_chat_bridge.py "$APP/chat_bridge.py"
  chmod 600 "$APP/chat_bridge.py"

  # plist: ~/Library/LaunchAgents/com.cip.chatbridge.plist
  #   ProgramArguments = [python3 绝对路径, "…/Application Support/cip-bridge/chat_bridge.py"]
  #   RunAtLoad=true, KeepAlive=true, ProcessType=Background,
  #   EnvironmentVariables.PATH 必须含 /opt/homebrew/bin（dsh 是 #!/usr/bin/env node）

  launchctl bootout  gui/$(id -u)/com.cip.chatbridge 2>/dev/null   # 重装前先卸
  launchctl bootstrap gui/$(id -u) "$HOME/Library/LaunchAgents/com.cip.chatbridge.plist"
  launchctl print   gui/$(id -u)/com.cip.chatbridge | grep state   # state = running

--------------------------------------------------------------------
手动 / 停止 / 回滚
--------------------------------------------------------------------
  PY="$HOME/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3"
  "$PY" "$HOME/Library/Application Support/cip-bridge/chat_bridge.py" --help
  "$PY" "$HOME/Library/Application Support/cip-bridge/chat_bridge.py" --once   # 处理一轮就退出

  # 停止并移除（回滚）
  launchctl bootout gui/$(id -u)/com.cip.chatbridge
  rm -f "$HOME/Library/LaunchAgents/com.cip.chatbridge.plist"

--------------------------------------------------------------------
可选环境变量（都有默认值，正常不用设）
--------------------------------------------------------------------
  CIP_INBOX        默认 /tmp/cip/chat-inbox.jsonl
  CIP_CHATSAY_URL  默认 http://127.0.0.1:8642/chat-say
  CIP_TOKEN_FILE   默认 <实例>/config/cipbridge/token.txt
  CIP_KEY_FILE     默认 ~/.dsh/secrets/deepseek_api_key
  CIP_DSH          默认自动探测（/opt/homebrew/bin/dsh → PATH）
  CIP_STATE_DIR    默认 ~/Library/Application Support/cip-bridge（放 state.json / 日志）
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# ---------------------------------------------------------------- 常量
APP_DIR = Path(os.path.expanduser("~/Library/Application Support/cip-bridge"))

INBOX = Path(os.environ.get("CIP_INBOX", "/tmp/cip/chat-inbox.jsonl"))
CHATSAY_URL = os.environ.get("CIP_CHATSAY_URL", "http://127.0.0.1:8642/chat-say")

# 真实例：~/Public/Minecraft/.minecraft/versions/CIP_Lite-1.0.0
MINECRAFT_INSTANCE = Path.home() / "Public/Minecraft/.minecraft/versions/CIP_Lite-1.0.0"
TOKEN_FILE = Path(
    os.environ.get(
        "CIP_TOKEN_FILE", str(MINECRAFT_INSTANCE / "config/cipbridge/token.txt")
    )
)

# 600 文件，内容是纯 key；只在进程内读，绝不外泄
KEY_FILE = Path(os.environ.get("CIP_KEY_FILE", os.path.expanduser("~/.dsh/secrets/deepseek_api_key")))

STATE_DIR = Path(os.environ.get("CIP_STATE_DIR", str(APP_DIR)))
STATE_FILE = STATE_DIR / "state.json"
LOG_FILE = STATE_DIR / "chatbridge.log"

DSH_TIMEOUT_S = 180          # 单条消息的 dsh 子进程上限（卡面）
POST_TIMEOUT_S = 10
POST_RETRIES = 2             # 失败后再试 2 次
POST_RETRY_DELAY_S = 1.0
LOG_MAX_BYTES = 2 * 1024 * 1024   # >2MB 轮转成 chatbridge.log.1
POLL_INTERVAL_S = 1.0
ERROR_TEXT_MAX = 300         # 错误摘要长度上限（卡面）
PREVIEW_CHARS = 80           # 日志里正文预览上限

# launchd 的 PATH 极简，而 dsh 是 `#!/usr/bin/env node` → 必须保证能找到 node
EXTRA_PATH = ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"]


class BridgeError(Exception):
    """可回显给玩家的一句话错误（不含任何敏感内容）。"""


# ---------------------------------------------------------------- 日志
def _ensure_dir(path: Path) -> None:
    try:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError:
        pass


def _rotate_log() -> None:
    try:
        if LOG_FILE.exists() and LOG_FILE.stat().st_size > LOG_MAX_BYTES:
            os.replace(LOG_FILE, LOG_FILE.with_name(LOG_FILE.name + ".1"))
    except OSError:
        pass


def log(msg: str) -> None:
    """写 chatbridge.log（600）+ stderr。调用方保证 msg 里没有 key。"""
    line = "[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg.replace("\n", " ⏎ "))
    _ensure_dir(STATE_DIR)
    _rotate_log()
    try:
        fd = os.open(str(LOG_FILE), os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except OSError as exc:  # 日志写不进去也不能让桥挂掉
        line = "[%s] (log write failed: %s) %s" % (
            time.strftime("%Y-%m-%d %H:%M:%S"),
            exc.__class__.__name__,
            msg.replace("\n", " ⏎ "),
        )
    try:
        sys.stderr.write(line + "\n")
        sys.stderr.flush()
    except Exception:
        pass


def preview(text: str, limit: int = PREVIEW_CHARS) -> str:
    flat = " ".join(text.split())
    if len(flat) <= limit:
        return flat
    return flat[:limit] + "…"


# ---------------------------------------------------------------- 状态（字节偏移）
def load_state() -> dict:
    try:
        with STATE_FILE.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            return {}
        return data
    except (OSError, ValueError):
        return {}


def save_state(offset: int, inode: int | None) -> None:
    _ensure_dir(STATE_DIR)
    payload = {"offset": int(offset), "inode": inode, "updated": time.strftime("%Y-%m-%d %H:%M:%S")}
    tmp = STATE_FILE.with_name(STATE_FILE.name + ".tmp")
    try:
        fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False)
        os.replace(tmp, STATE_FILE)
    except OSError as exc:
        log("state.json 写入失败：%s" % exc.__class__.__name__)


def read_new_messages() -> list[tuple[int, str]]:
    """返回 [(该行结束后的字节偏移, 原始 text)]；只消费完整行（以 \\n 结尾）。"""
    try:
        st = INBOX.stat()
    except OSError:
        return []

    state = load_state()
    offset = state.get("offset", 0)
    if not isinstance(offset, int) or offset < 0:
        offset = 0
    if state.get("inode") not in (None, st.st_ino):
        log("inbox 被替换（inode 变化），从头读")
        offset = 0
    if offset > st.st_size:      # 被截断/清空
        log("inbox 变小（%d > %d），从头读" % (offset, st.st_size))
        offset = 0

    try:
        with INBOX.open("rb") as fh:
            fh.seek(offset)
            chunk = fh.read()
    except OSError as exc:
        log("inbox 读取失败：%s" % exc.__class__.__name__)
        return []

    last_nl = chunk.rfind(b"\n")
    if last_nl < 0:
        return []                # 没有完整行，等下次

    complete = chunk[: last_nl + 1]
    out: list[tuple[int, str]] = []
    cursor = offset
    for raw in complete.split(b"\n"):
        if not raw:
            continue
        cursor += len(raw) + 1
        try:
            obj = json.loads(raw.decode("utf-8", "replace"))
        except ValueError:
            log("跳过非法 JSON 行（offset=%d，%d B）" % (cursor, len(raw)))
            out.append((cursor, ""))
            continue
        text = obj.get("text") if isinstance(obj, dict) else None
        if not isinstance(text, str):
            log("跳过无 text 字段的行（offset=%d）" % cursor)
            out.append((cursor, ""))
            continue
        out.append((cursor, text))
    return out


# ---------------------------------------------------------------- 依赖读取
def read_token() -> str:
    try:
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise BridgeError(
            "读不到模组 token（%s：%s）" % (TOKEN_FILE, exc.__class__.__name__)
        ) from exc
    if not token:
        raise BridgeError("模组 token 文件为空（%s）" % TOKEN_FILE)
    return token


def read_api_key() -> str:
    """从 600 文件读 key。错误信息只提路径，绝不带内容。"""
    try:
        key = KEY_FILE.read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise BridgeError(
            "缺少 API key：读不到 %s（%s）。请确认该 600 文件存在且非空。"
            % (KEY_FILE, exc.__class__.__name__)
        ) from exc
    if not key:
        raise BridgeError("缺少 API key：%s 是空文件。" % KEY_FILE)
    return key


def find_dsh() -> str | None:
    explicit = os.environ.get("CIP_DSH")
    candidates = [explicit] if explicit else []
    candidates += [
        "/opt/homebrew/bin/dsh",
        "/usr/local/bin/dsh",
        str(Path.home() / ".local/bin/dsh"),
        str(Path.home() / ".dsh/bin/dsh"),
    ]
    for cand in candidates:
        if cand and os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
    return shutil.which("dsh")


def child_env(key: str) -> dict:
    env = dict(os.environ)
    env["DEEPSEEK_API_KEY"] = key          # ← 唯一的 key 传递点：环境变量，不进 argv
    parts = env.get("PATH", "").split(os.pathsep) if env.get("PATH") else []
    for p in EXTRA_PATH:
        if p not in parts:
            parts.append(p)
    env["PATH"] = os.pathsep.join([p for p in parts if p])
    env.setdefault("HOME", str(Path.home()))
    return env


# ---------------------------------------------------------------- DSH 调用
def ask_dsh(text: str) -> str:
    """跑一次 headless，返回要回显的字符串（正常回复或可读错误）。"""
    dsh = find_dsh()
    if not dsh:
        raise BridgeError("找不到 dsh 可执行文件（/opt/homebrew/bin/dsh 与 PATH 均无）")

    key = read_api_key()  # 缺 key 时在这里抛 BridgeError（可回显）

    cmd = [dsh, "--profile", "headless", "--json", text]
    log("DSH 调用开始：%s --profile headless --json <%d 字符>" % (dsh, len(text)))
    started = time.time()
    try:
        proc = subprocess.run(
            cmd,
            env=child_env(key),          # key 只在 env 里
            stdin=subprocess.DEVNULL,    # 绝不让 headless 等 stdin
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=DSH_TIMEOUT_S,
            cwd=str(STATE_DIR) if STATE_DIR.is_dir() else str(Path.home()),
        )
    except subprocess.TimeoutExpired:
        log("DSH 超时（>%ds）" % DSH_TIMEOUT_S)
        raise BridgeError("处理超时（%d 秒）" % DSH_TIMEOUT_S) from None
    except OSError as exc:
        log("DSH 启动失败：%s" % exc.__class__.__name__)
        raise BridgeError("dsh 启动失败（%s）" % exc.__class__.__name__) from exc

    elapsed = time.time() - started
    log(
        "DSH 调用结束：rc=%d 用时=%.1fs stdout=%dB stderr=%dB"
        % (proc.returncode, elapsed, len(proc.stdout or ""), len(proc.stderr or ""))
    )

    final_text = None
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if not isinstance(ev, dict) or ev.get("type") != "final":
            continue
        reason = ev.get("reason") if isinstance(ev.get("reason"), dict) else {}
        kind = reason.get("kind")
        text_out = ev.get("text") if isinstance(ev.get("text"), str) else ""
        if kind == "error":
            summary = extract_error(text_out, reason)
            log("DSH final.reason=error：%s" % preview(summary, ERROR_TEXT_MAX))
            return "[DSH 出错] " + summary
        final_text = text_out
        log("DSH final：kind=%s text=%d 字符" % (kind, len(text_out)))

    if final_text is None:
        tail = stderr_tail(proc.stderr)
        log("未收到 final 事件（rc=%d）：%s" % (proc.returncode, tail))
        raise BridgeError("未收到 final 事件（rc=%d）%s" % (proc.returncode, ("，" + tail) if tail else ""))

    if proc.returncode != 0:
        tail = stderr_tail(proc.stderr)
        log("dsh 退出码非 0（%d）：%s" % (proc.returncode, tail))
        return "[DSH 出错] rc=%d %s" % (proc.returncode, tail)

    if not final_text.strip():
        log("DSH 回复为空，改回可读错误")
        return "[DSH] 没有产生任何回复文本。"
    return final_text


def extract_error(text_out: str, reason: dict) -> str:
    msg = (text_out or "").strip()
    if not msg:
        for key_name in ("message", "detail", "error"):
            val = reason.get(key_name)
            if isinstance(val, str) and val.strip():
                msg = val.strip()
                break
    if not msg:
        msg = "reason.kind=error"
    msg = " ".join(msg.split())
    return msg[:ERROR_TEXT_MAX]


def stderr_tail(stderr: str | None, limit: int = 200) -> str:
    """stderr 末行摘要（≤limit 字符）。极端情况下也不外泄 key：我们只取最后一行且截断。"""
    if not stderr:
        return ""
    lines = [ln.strip() for ln in stderr.strip().splitlines() if ln.strip()]
    if not lines:
        return ""
    return lines[-1][:limit]


# ---------------------------------------------------------------- 回显到游戏
def post_chat_say(text: str) -> bool:
    try:
        token = read_token()
    except BridgeError as exc:
        log("POST 跳过：%s" % exc)
        return False

    body = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
    delay = POST_RETRY_DELAY_S
    for attempt in range(POST_RETRIES + 1):
        if attempt:
            time.sleep(delay)
        try:
            req = urllib.request.Request(
                CHATSAY_URL,
                data=body,
                method="POST",
                headers={"Content-Type": "application/json", "X-CIP-Token": token},
            )
            with urllib.request.urlopen(req, timeout=POST_TIMEOUT_S) as resp:
                payload = resp.read(400).decode("utf-8", "replace")
                log(
                    "POST /chat-say HTTP %d（%d 字符，第 %d 次尝试）%s"
                    % (resp.status, len(text), attempt + 1, payload)
                )
                return True
        except urllib.error.HTTPError as exc:
            payload = ""
            try:
                payload = exc.read(400).decode("utf-8", "replace")
            except Exception:
                pass
            log("POST /chat-say HTTP %d（第 %d 次尝试）%s" % (exc.code, attempt + 1, payload))
            if 400 <= exc.code < 500 and exc.code not in (408, 429):
                return False   # 4xx 不会因重试变好，直接记日志
        except urllib.error.URLError as exc:
            log(
                "POST /chat-say 连接失败 %s（第 %d 次尝试）：%s"
                % (CHATSAY_URL, attempt + 1, exc.reason)
            )
        except (TimeoutError, OSError) as exc:
            log(
                "POST /chat-say 异常 %s（第 %d 次尝试）：%s"
                % (type(exc).__name__, attempt + 1, exc)
            )
    log("POST /chat-say 放弃（共 %d 次尝试）：回复未显示，游戏可能未开" % (POST_RETRIES + 1))
    return False


# ---------------------------------------------------------------- 主流程
def process_pending() -> int:
    """处理所有新完整行，返回处理条数（含跳过的空行）。"""
    handled = 0
    for end_offset, raw in read_new_messages():
        text = raw.strip()
        save_state(end_offset, inbox_inode())
        if not text:
            log("跳过空文本（offset=%d）" % end_offset)
            handled += 1
            continue
        log("收到消息：%d 字符 %s" % (len(text), preview(text)))
        try:
            reply = ask_dsh(text)
        except BridgeError as exc:
            reply = "[DSH] " + str(exc)
            log("桥接到错误：%s" % exc)
        except Exception as exc:  # 兜底：桥内部异常也必须可见，不能静默
            reply = "[DSH] 桥内部错误：%s" % type(exc).__name__
            log("未预期异常：%s: %s" % (type(exc).__name__, exc))
        log("准备回显：%d 字符 %s" % (len(reply), preview(reply)))
        post_chat_say(reply)
        handled += 1
    return handled


def inbox_inode() -> int | None:
    try:
        return INBOX.stat().st_ino
    except OSError:
        return None


def run_daemon(interval: float) -> int:
    log("启动：inbox=%s url=%s state=%s（pid=%d）" % (INBOX, CHATSAY_URL, STATE_DIR, os.getpid()))
    if not INBOX.exists():
        log("inbox 尚不存在（游戏里发第一条 #dsh 后会由模组创建）")
    stop = {"flag": False}

    def _on_signal(signum, _frame):
        stop["flag"] = True
        log("收到信号 %d，准备退出" % signum)

    signal.signal(signal.SIGTERM, _on_signal)
    signal.signal(signal.SIGINT, _on_signal)

    while not stop["flag"]:
        try:
            process_pending()
        except Exception as exc:  # 永不因单轮异常退出（launchd KeepAlive 会抖）
            log("轮询异常：%s: %s" % (type(exc).__name__, exc))
        slept = 0.0
        while slept < interval and not stop["flag"]:
            time.sleep(0.1)
            slept += 0.1
    log("已退出")
    return 0


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="cip_chat_bridge.py",
        description="CIP Bridge 聊天桥：/tmp/cip/chat-inbox.jsonl → dsh headless → POST /chat-say",
        epilog=(
            "示例：\n"
            "  --once     处理当前所有新消息后退出（手动验收用）\n"
            "  无参数     守护模式，每 1 秒轮询（LaunchAgent com.cip.chatbridge 用）\n"
            "停止：launchctl bootout gui/$(id -u)/com.cip.chatbridge"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--once", action="store_true", help="只处理一轮新消息，然后退出")
    parser.add_argument(
        "--interval", type=float, default=POLL_INTERVAL_S, help="守护模式轮询间隔秒数（默认 1.0）"
    )
    parser.add_argument("--show-config", action="store_true", help="打印生效路径配置后退出")
    parser.add_argument("--version", action="version", version="cip_chat_bridge 1.0 (CB-15)")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    if args.show_config:
        print("inbox      = %s" % INBOX)
        print("chat-say   = %s" % CHATSAY_URL)
        print("token file = %s" % TOKEN_FILE)
        print("key file   = %s（只读，仅注入子进程 env）" % KEY_FILE)
        print("state dir  = %s" % STATE_DIR)
        print("log file   = %s" % LOG_FILE)
        print("dsh        = %s" % (find_dsh() or "<未找到>"))
        return 0

    if args.once:
        log("--once：处理一轮（inbox=%s）" % INBOX)
        if not INBOX.exists():
            log("inbox 不存在：%s" % INBOX)
        n = process_pending()
        log("--once 完成：处理 %d 条" % n)
        return 0

    return run_daemon(max(0.2, args.interval))


if __name__ == "__main__":
    sys.exit(main())
