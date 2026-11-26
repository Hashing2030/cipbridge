#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""CIP 零 token 监测：轮询 CIP Bridge 的 /state，记录到本地日志，只在事件发生时发 macOS 通知。
不经过 LLM —— 用户在 DSH 里问"刚才发生了什么"时，leader 读这个日志即可。
"""
import json
import os
import subprocess
import time
import urllib.error
import urllib.request

INSTANCE = os.path.expanduser("~/Public/Minecraft/.minecraft/versions/CIP_Lite-1.0.0")
TOKEN_FILE = os.path.join(INSTANCE, "config", "cipbridge", "token.txt")
HOME_DIR = os.path.expanduser("~/Library/Application Support/cip-vigil")
LOG = os.path.join(HOME_DIR, "watch.jsonl")
POLL_SEC = 5.0
LOG_MAX_BYTES = 8 * 1024 * 1024

os.makedirs(HOME_DIR, exist_ok=True)


def read_token():
    try:
        with open(TOKEN_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def fetch_state(token):
    req = urllib.request.Request(
        "http://127.0.0.1:8642/state", headers={"X-CIP-Token": token}
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read().decode("utf-8")), r.status
    except urllib.error.HTTPError as e:
        try:
            body = json.loads(e.read().decode("utf-8"))
        except Exception:
            body = {"ok": False, "code": "http_%d" % e.code}
        return body, e.code
    except Exception as ex:  # 连接失败 / 超时
        return {"ok": False, "code": "unreachable", "error": str(ex)}, 0


def notify(msg):
    safe = msg.replace('"', "'").replace("\\", "/")
    try:
        subprocess.run(
            ["osascript", "-e", f'display notification "{safe}" with title "CIP 监测"'],
            timeout=5,
            capture_output=True,
        )
    except Exception:
        pass


def write_log(rec):
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        if os.path.getsize(LOG) > LOG_MAX_BYTES:
            os.replace(LOG, LOG + ".1")
    except OSError:
        pass


def main():
    token = read_token()
    prev = None
    online = None          # None=未知, True/False
    while True:
        st, code = fetch_state(token)
        now = time.strftime("%Y-%m-%d %H:%M:%S")

        if code == 403:  # token 变了（例如换了实例）
            token = read_token()

        if not st.get("ok"):
            c = st.get("code")
            if online is not False:
                online = False
                # 离线只记日志、不弹通知：开机或游戏没开时不该打扰
                write_log({"t": now, "ev": "offline", "code": c})
            prev = None
            time.sleep(POLL_SEC)
            continue

        if online is not True:
            online = True
            write_log({"t": now, "ev": "online"})
            notify("已连上游戏")

        p = st.get("player", {}) or {}
        hp = p.get("hp")
        food = p.get("food")
        pos = p.get("pos")
        dim = st.get("dim")
        near = st.get("nearby_entities")
        tick = st.get("tick")

        write_log(
            {
                "t": now,
                "ev": "state",
                "hp": hp,
                "food": food,
                "pos": pos,
                "gm": p.get("gm"),
                "dim": dim,
                "near": near,
                "tick": tick,
            }
        )

        if prev:
            hp0 = prev.get("hp")
            if hp0 is not None and hp is not None:
                if hp <= 0 < hp0:
                    notify("你死亡了")
                elif hp0 - hp >= 2:
                    notify("掉血：%d → %d" % (round(hp0), round(hp)))
                elif hp <= 8 < hp0:
                    notify("生命值偏低（%d）" % round(hp))
            f0 = prev.get("food")
            if f0 is not None and food is not None and food <= 6 < f0:
                notify("饥饿值偏低（%d）" % food)
            if prev.get("dim") != dim:
                notify("切换维度：%s" % dim)
            a = prev.get("pos")
            if a and pos:
                d = ((a[0] - pos[0]) ** 2 + (a[1] - pos[1]) ** 2 + (a[2] - pos[2]) ** 2) ** 0.5
                if d > 100:
                    notify("瞬移 %.0f 格" % d)
            n0 = prev.get("near")
            if n0 is not None and near is not None and near >= 10 > n0:
                notify("周围实体变多（%d）" % near)

        prev = {"hp": hp, "food": food, "pos": pos, "dim": dim, "near": near}
        time.sleep(POLL_SEC)


if __name__ == "__main__":
    main()
