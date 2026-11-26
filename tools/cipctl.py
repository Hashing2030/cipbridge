#!/usr/bin/env python3
"""cipctl.py -- CIP Bridge 客户端 CLI（SPEC v1.1，任务卡 CB-11）。

用途：在 lead 这侧通过 HTTP 调用游戏内 bridge 的端点（/ping /state /cmd /snapshot /find
/batch /batch-file），重点是把 /snapshot 的 ZIP 严格按 SPEC §4.3 的线格式解码并给出
一行摘要。

依赖纪律
--------
* HTTP：stdlib ``urllib.request``；JSON：stdlib ``json``；ZIP：stdlib ``zipfile``。
* 解码：``numpy``（可选）。没有 numpy 时 ``snap`` **不假装解码**，只解包 + 打印
  header 摘要并明确提示。
* 任何第三方库都不引入。

线格式（SPEC §4.3，与 mod 源码交叉核对过）
------------------------------------------
* ``blocks.bin``   uint8 / ``blocks16.bin`` uint16 **小端**，长度 ``nx*ny*nz``，
  索引 ``(yi*nz + zi)*nx + xi``（**x 最快**），值为 ``header.palette`` 下标，``air`` 固定下标 0。
  解码：``np.frombuffer(b, '<u1'|'<u2').reshape((ny, nz, nx))``。
* ``height.bin``   int16 **小端**，长度 ``nx*nz``，索引 ``zi*nx + xi``，shape ``(nz, nx)``；
  某列没有非空气方块时是哨兵 ``-32768``。
* ``flags.bin`` / ``light.bin`` uint8，形状同 blocks（仅显式请求才在包里）。
* ``biome.bin``    uint8，``ceil(nx/4)*ceil(nz/4)``。
* ``blocks_delta.bin``（``delta=1`` 时**替代** blocks entry）：
  ``varint(index - previousIndex) + uint8|uint16LE(新值)`` 交替；``previousIndex`` 从 0 起、
  每命中一条后置为当前 index（索引因此严格递增）。client 没有基线时无法还原全量网格。
* ``header.unloaded`` 里的区块坐标对应位置填 0（air 下标）但**不可信**，摘要统计时排除。

安全纪律
--------
token 与 ``confirm`` 永不打印：只放在请求头 / query 里，错误信息里也不回显 URL。

退出码：0 成功；2 服务端返回 ``ok:false``（body 原样打印）；3 缺少 token / 参数组合不成立；
1 连接失败等其它错误。
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile

TOKEN_HEADER = "X-CIP-Token"

DEFAULT_PORT = 8642
DEFAULT_TIMEOUT = 20.0
PROBE_SPAN = 10
DEFAULT_OUT_DIR = "/tmp/cip"
AIR_INDEX = 0

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_HTTP_ERROR = 2
EXIT_CONFIG = 3


# --------------------------------------------------------------------------- #
# 错误
# --------------------------------------------------------------------------- #
class CipError(Exception):
    """带退出码的致命错误（消息里绝不出现 token/confirm）。"""

    def __init__(self, message: str, code: int = EXIT_ERROR) -> None:
        super().__init__(message)
        self.code = code


class DecodeError(CipError):
    """快照解码失败（线格式不符 SPEC）。"""

    def __init__(self, message: str) -> None:
        super().__init__("decode error: " + message, EXIT_ERROR)


# --------------------------------------------------------------------------- #
# 配置解析
# --------------------------------------------------------------------------- #
def _read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def read_token_file(path: str) -> str:
    """读 token 文本：首行即 token（去掉首尾空白；容忍末尾换行）。"""
    try:
        raw = _read_text(path)
    except OSError as exc:
        raise CipError("cannot read token file %s: %s" % (path, exc), EXIT_CONFIG) from None
    lines = [line.strip() for line in raw.splitlines() if line.strip()]
    if not lines:
        raise CipError("token file %s is empty" % path, EXIT_CONFIG)
    return lines[0]


def read_client_port(instance: str) -> int | None:
    """从 ``<instance>/config/cipbridge-client.toml`` 读 ``port = <int>``。

    只做最小 TOML 解析：允许 ``#`` 注释、任意节（``[client]`` 等）、键名 ``port``。
    """
    path = os.path.join(instance, "config", "cipbridge-client.toml")
    if not os.path.isfile(path):
        return None
    try:
        raw = _read_text(path)
    except OSError as exc:
        raise CipError("cannot read %s: %s" % (path, exc), EXIT_CONFIG) from None
    for line in raw.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or "=" not in line or line.startswith("["):
            continue
        key, value = line.split("=", 1)
        if key.strip() != "port":
            continue
        try:
            port = int(value.strip().strip('"').strip("'"))
        except ValueError:
            raise CipError("bad port in %s: %r" % (path, value.strip()), EXIT_CONFIG) from None
        return port
    return None


def resolve_token(args: argparse.Namespace) -> str | None:
    """token 优先级：--token > --token-file > --instance/.../token.txt。"""
    if getattr(args, "token", None):
        return args.token
    if getattr(args, "token_file", None):
        return read_token_file(args.token_file)
    if getattr(args, "instance", None):
        path = os.path.join(args.instance, "config", "cipbridge", "token.txt")
        if not os.path.isfile(path):
            raise CipError("no token file under --instance: %s" % path, EXIT_CONFIG)
        return read_token_file(path)
    return None


def resolve_port(args: argparse.Namespace) -> int:
    """端口优先级：显式 --port > --instance 的 cipbridge-client.toml > 8642。"""
    explicit = getattr(args, "port", None)
    if explicit is not None:
        return int(explicit)
    if getattr(args, "instance", None):
        from_instance = read_client_port(args.instance)
        if from_instance is not None:
            return from_instance
    return DEFAULT_PORT


def build_config(args: argparse.Namespace, need_token: bool = True) -> dict:
    token = resolve_token(args)
    if token is None and need_token:
        raise CipError(
            "no token: pass --token, --token-file, or --instance <path> "
            "(token lives in <instance>/config/cipbridge/token.txt)",
            EXIT_CONFIG,
        )
    return {
        "host": "127.0.0.1",
        "port": resolve_port(args),
        "token": token,
        "timeout": float(getattr(args, "timeout", DEFAULT_TIMEOUT)),
    }


# --------------------------------------------------------------------------- #
# HTTP
# --------------------------------------------------------------------------- #
class HttpResult:
    __slots__ = ("status", "body", "headers")

    def __init__(self, status: int, body: bytes, headers: dict) -> None:
        self.status = status
        self.body = body
        self.headers = headers

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    def text(self) -> str:
        return self.body.decode("utf-8", "replace")

    def json(self):
        try:
            return json.loads(self.body.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return None


def http_request(cfg: dict, method: str, path: str, query=None, body=None,
                 port: int | None = None) -> HttpResult:
    """发一个请求。``path`` 用于日志/错误信息，绝不含 token 或 confirm。"""
    use_port = cfg["port"] if port is None else port
    url = "http://%s:%d%s" % (cfg["host"], use_port, path)
    if query:
        pairs = []
        for key, value in query.items():
            if value is None:
                continue
            if isinstance(value, (list, tuple)):
                for item in value:
                    if item is not None:
                        pairs.append((key, str(item)))
            else:
                pairs.append((key, str(value)))
        if pairs:
            url += "?" + urllib.parse.urlencode(pairs)
    headers = {TOKEN_HEADER: cfg["token"] or "", "Accept": "*/*"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=cfg["timeout"]) as response:
            return HttpResult(response.status, response.read(), dict(response.headers))
    except urllib.error.HTTPError as exc:
        return HttpResult(exc.code, exc.read(), dict(exc.headers or {}))
    except urllib.error.URLError as exc:
        # 不回显 url（query 里可能有 confirm）
        raise CipError(
            "connect failed: %s:%d %s (%s)" % (cfg["host"], use_port, path, exc.reason),
            EXIT_ERROR,
        ) from None
    except TimeoutError:
        raise CipError("timeout after %.1fs: %s" % (cfg["timeout"], path), EXIT_ERROR) from None


def probe_port(cfg: dict) -> int:
    """``--probe``：从 cfg['port'] 起逐个 /ping，返回第一个 200 的端口。"""
    start = cfg["port"]
    tried = []
    for port in range(start, start + PROBE_SPAN + 1):
        try:
            result = http_request(cfg, "GET", "/ping", port=port)
        except CipError:
            tried.append(port)
            continue
        if result.ok:
            return port
        tried.append(port)
    raise CipError("no bridge found on ports %d..%d" % (start, start + PROBE_SPAN), EXIT_ERROR)


def require_token(cfg: dict) -> None:
    if not cfg["token"]:
        raise CipError("this request needs a token (--token/--token-file/--instance)", EXIT_CONFIG)


def emit_json_success(result: HttpResult, indent: int = 2) -> int:
    payload = result.json()
    if payload is None:
        # 200 但不是 JSON：原样打印（例如 zip 走别处）
        print(result.text())
        return EXIT_OK
    print(json.dumps(payload, ensure_ascii=False, indent=indent, sort_keys=False))
    return EXIT_OK


def fail_with_body(result: HttpResult) -> int:
    """服务端回 ok:false：body 原样打印（card 要求），非零退出。"""
    text = result.text()
    print(text if text.strip() else "(empty body)")
    return EXIT_HTTP_ERROR


def handle_result(result: HttpResult) -> int:
    if result.ok:
        return emit_json_success(result)
    return fail_with_body(result)


# --------------------------------------------------------------------------- #
# 子命令：ping / state / cmd / find / batch
# --------------------------------------------------------------------------- #
def cmd_ping(args: argparse.Namespace) -> int:
    cfg = build_config(args)
    cfg["port"] = probe_port(cfg) if args.probe else cfg["port"]
    return handle_result(http_request(cfg, "GET", "/ping"))


def cmd_state(args: argparse.Namespace) -> int:
    cfg = build_config(args)
    cfg["port"] = probe_port(cfg) if args.probe else cfg["port"]
    return handle_result(http_request(cfg, "GET", "/state"))


def cmd_cmd(args: argparse.Namespace) -> int:
    cfg = build_config(args)
    cfg["port"] = probe_port(cfg) if args.probe else cfg["port"]
    query = {"c": args.command}
    if args.wait is not None:
        query["wait"] = args.wait
    if args.confirm:
        query["confirm"] = cfg["token"]  # 只进 query，绝不打印
    result = http_request(cfg, "GET", "/cmd", query=query)
    if not result.ok:
        return fail_with_body(result)
    payload = result.json()
    if payload is None:
        print(result.text())
        return EXIT_OK
    print("sent: %s" % payload.get("sent"))
    print("took_ms: %s" % payload.get("took_ms"))
    reply = payload.get("reply") or []
    if reply:
        print("reply (%d):" % len(reply))
        for line in reply:
            print("  " + str(line))
    else:
        print("reply: []")
        hint = payload.get("hint")
        if hint:
            print("hint: %s" % hint)
    return EXIT_OK


def cmd_find(args: argparse.Namespace) -> int:
    cfg = build_config(args)
    cfg["port"] = probe_port(cfg) if args.probe else cfg["port"]
    query = {"block": args.block}
    for key in ("r", "y0", "y1", "limit"):
        value = getattr(args, key)
        if value is not None:
            query[key] = value
    result = http_request(cfg, "GET", "/find", query=query)
    if not result.ok:
        return fail_with_body(result)
    payload = result.json()
    if payload is None:
        print(result.text())
        return EXIT_OK
    found = payload.get("found") or []
    print("ok find block=%s r=%s found=%d visited_chunks=%s ms=%s truncated=%s" % (
        payload.get("block"), payload.get("r"), len(found),
        payload.get("visited_chunks"), payload.get("ms"), payload.get("truncated")))
    if found:
        print("found (x,y,z):")
        for position in found:
            print("  %s" % (position,))
    return EXIT_OK


def cmd_batch(args: argparse.Namespace) -> int:
    cfg = build_config(args)
    cfg["port"] = probe_port(cfg) if args.probe else cfg["port"]
    # 卡面路径白名单是模组侧校验的 /tmp/cip/ 前缀
    body = {"path": args.file}
    if args.spacing is not None:
        body["spacing_ticks"] = args.spacing
    if args.confirm:
        body["confirm"] = cfg["token"]  # 只进 body，绝不打印
    if args.verbose:
        body["verbose"] = True
    result = http_request(cfg, "POST", "/batch-file", body=body)
    if not result.ok:
        return fail_with_body(result)
    payload = result.json()
    if payload is None:
        print(result.text())
        return EXIT_OK
    failed = payload.get("failed") or []
    print("ok batch-file sent=%s failed=%d ms=%s path=%s" % (
        payload.get("sent"), len(failed), payload.get("ms"), args.file))
    if failed:
        print("failed:")
        for item in failed:
            print("  %s" % (json.dumps(item, ensure_ascii=False),))
    if args.verbose and payload.get("results"):
        print("results:")
        for item in payload["results"]:
            print("  %s" % (json.dumps(item, ensure_ascii=False),))
    return EXIT_OK


# --------------------------------------------------------------------------- #
# snapshot 解码（严格按 SPEC §4.3）
# --------------------------------------------------------------------------- #
def _require_numpy():
    try:
        import numpy  # noqa: F401
    except ImportError:
        return None
    return numpy


def _size(header: dict):
    """header.size → (nx, ny, nz)，任何不合规形态都当解码错误（不猜）。"""
    size = header.get("size")
    if not (isinstance(size, list) and len(size) == 3):
        raise DecodeError("header.size must be [nx,ny,nz], got %r" % (size,))
    try:
        nx, ny, nz = (int(size[0]), int(size[1]), int(size[2]))
    except (TypeError, ValueError):
        raise DecodeError("header.size has non-integer axis: %r" % (size,)) from None
    if min(nx, ny, nz) <= 0:
        raise DecodeError("header.size has non-positive axis: %r" % (size,))
    return nx, ny, nz


def _entry_size(zf: zipfile.ZipFile, names, entry: str, expected: int, item_bytes: int,
                what: str):
    if entry not in names:
        return None
    raw = zf.read(entry)
    if len(raw) != expected * item_bytes:
        raise DecodeError(
            "%s length mismatch: %d bytes, expected %d*%d=%d"
            % (entry, len(raw), expected, item_bytes, expected * item_bytes))
    return raw


def decode_blocks(zf: zipfile.ZipFile, header: dict, np):
    """解 blocks.bin / blocks16.bin → (blocks (ny,nz,nx) 数组, bytes_per_block)。"""
    names = zf.namelist()
    nx, ny, nz = _size(header)
    if header.get("order") != "x-fastest":
        raise DecodeError("header.order must be 'x-fastest' (SPEC), got %r" % (header.get("order"),))

    has8 = "blocks.bin" in names
    has16 = "blocks16.bin" in names
    if has8 == has16:
        raise DecodeError("expected exactly one of blocks.bin / blocks16.bin, saw %s"
                          % (sorted(n for n in names if n.startswith("blocks")),))
    bytes_per_block = 1 if has8 else 2
    declared = header.get("bytes_per_block")
    if declared is not None and int(declared) != bytes_per_block:
        raise DecodeError("header.bytes_per_block=%r but entry is %d byte(s)"
                          % (declared, bytes_per_block))

    total = nx * ny * nz
    entry = "blocks.bin" if has8 else "blocks16.bin"
    dtype = "<u1" if has8 else "<u2"
    raw = _entry_size(zf, names, entry, total, bytes_per_block, "blocks")
    blocks = np.frombuffer(raw, dtype=dtype)
    if blocks.size != total:
        raise DecodeError("%s has %d values, expected nx*ny*nz=%d" % (entry, blocks.size, total))
    # SPEC: 索引 (yi*nz + zi)*nx + xi（x 最快）→ reshape((ny, nz, nx))
    return blocks.reshape((ny, nz, nx)), bytes_per_block


def decode_height(zf: zipfile.ZipFile, header: dict, np):
    names = zf.namelist()
    if "height.bin" not in names:
        return None
    nx, _ny, nz = _size(header)
    raw = _entry_size(zf, names, "height.bin", nx * nz, 2, "height")
    height = np.frombuffer(raw, dtype="<i2")
    if height.size != nx * nz:
        raise DecodeError("height.bin has %d values, expected nx*nz=%d" % (height.size, nx * nz))
    return height.reshape((nz, nx))  # 索引 zi*nx + xi


def decode_blocks_delta(zf: zipfile.ZipFile, header: dict):
    """解 blocks_delta.bin → ([(index, value), ...], bytes_per_block)。

    格式（SnapshotCache.diff）：varint(index - previousIndex) + 值（uint8 或 uint16 小端），
    previousIndex 初值 0、命中后置为当前 index。
    """
    data = zf.read("blocks_delta.bin")
    width = int(header.get("bytes_per_block") or 1)
    if width not in (1, 2):
        raise DecodeError("header.bytes_per_block must be 1 or 2, got %r" % (width,))
    out = []
    offset = 0
    previous = 0
    nx, ny, nz = _size(header)  # 越界检查需要 nx*ny*nz
    total = nx * ny * nz
    while offset < len(data):
        shift = 0
        value = 0
        while True:
            if offset >= len(data):
                raise DecodeError("truncated varint in blocks_delta.bin")
            byte = data[offset]
            offset += 1
            value |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
            if shift > 63:
                raise DecodeError("varint too long in blocks_delta.bin")
        index = previous + value
        if index >= total:
            raise DecodeError("delta index %d out of range (nx*ny*nz=%d)" % (index, total))
        if offset + width > len(data):
            raise DecodeError("truncated value in blocks_delta.bin at byte %d" % offset)
        if width == 1:
            block = data[offset]
        else:
            block = data[offset] | (data[offset + 1] << 8)
        offset += width
        out.append((index, block))
        previous = index
    return out, width


def unloaded_chunk_set(header: dict) -> set:
    pairs = set()
    for item in header.get("unloaded") or []:
        if isinstance(item, list) and len(item) >= 2:
            pairs.add((int(item[0]), int(item[1])))
    return pairs


def loaded_column_mask(np, header: dict):
    """按 header.unloaded 排除未加载区块列，返回 (nz,nx) bool 掩码（全 True = 无 unloaded）。"""
    nx, _ny, nz = _size(header)
    origin = header.get("origin") or [0, 0, 0]
    unloaded = unloaded_chunk_set(header)
    mask = np.ones((nz, nx), dtype=bool)
    if not unloaded:
        return mask
    xs = np.arange(nx) + int(origin[0])
    zs = np.arange(nz) + int(origin[2])
    cx = np.floor_divide(xs, 16)  # 区块坐标 = floor(x/16)，x 可为负
    cz = np.floor_divide(zs, 16)
    for chunk_z in range(nz):
        for chunk_x in range(nx):
            if (int(cx[chunk_x]), int(cz[chunk_z])) in unloaded:
                mask[chunk_z, chunk_x] = False
    return mask


def summarize_snapshot(zf: zipfile.ZipFile, header: dict, np, file_size: int,
                       show_detail: bool, at: list | None = None) -> int:
    names = set(zf.namelist())
    size = header.get("size") or [0, 0, 0]
    nx, ny, nz = (int(v) for v in size)
    palette = header.get("palette") or []
    origin = header.get("origin") or [0, 0, 0]
    unloaded = header.get("unloaded") or []
    entities = header.get("entities") or []
    player = header.get("player") or {}
    player_pos = player.get("pos")

    lines = []
    if "blocks.bin" in names or "blocks16.bin" in names:
        blocks, bytes_per_block = decode_blocks(zf, header, np)
        mask = loaded_column_mask(np, header)
        loaded = blocks[:, mask]  # (ny, K)
        counts = np.bincount(loaded.astype(np.int64).ravel(), minlength=max(len(palette), 1))
        total_loaded = int(loaded.size)
        air_loaded = int(counts[AIR_INDEX]) if counts.size > AIR_INDEX else 0
        non_air = total_loaded - air_loaded
        order = sorted(range(1, counts.size), key=lambda i: (-int(counts[i]), i))
        top = ["%s=%d" % (palette[i] if i < len(palette) else "#%d" % i, int(counts[i]))
               for i in order if counts[i] > 0][:8]

        # header.counts 与解码结果交叉校验（只有无 unloaded 时才可比）
        header_counts = header.get("counts") or {}
        mismatch = None
        if not unloaded and header_counts:
            computed = {}
            for i in range(1, counts.size):
                if counts[i] > 0 and i < len(palette):
                    computed[palette[i]] = int(counts[i])
            if computed != {k: int(v) for k, v in header_counts.items()}:
                mismatch = "counts mismatch: header vs decoded"

        head = (
            "ok snap tick=%s size=[%d,%d,%d] palette=%d counts(top8)=%s air=%d/%d entities=%d "
            "player.pos=%s ms=%s unloaded=%d file=%dB"
            % (header.get("tick"), nx, ny, nz, len(palette),
               ",".join(top) if top else "(none)", air_loaded, total_loaded,
               len(entities), player_pos, header.get("ms"), len(unloaded), file_size)
        )
        lines.append(head)
        if bytes_per_block == 2:
            lines.append("note: blocks16.bin (uint16 LE, bytes_per_block=2)")
        if mismatch:
            lines.append("warn: %s (unloaded=0 so they should agree)" % mismatch)

        if show_detail:
            lines.append("detail: order=%s v=%s world=%s dim=%s origin=%s bytes_per_block=%d"
                         % (header.get("order"), header.get("v"), header.get("world"),
                            header.get("dim"), list(origin), bytes_per_block))
            lines.append("detail: palette=%s" % (json.dumps(palette, ensure_ascii=False),))
            if unloaded:
                lines.append("detail: unloaded chunks=%s" % (json.dumps(unloaded),))
            if "blocks_delta.bin" in names:
                changes, width = decode_blocks_delta(zf, header)
                lines.append("detail: blocks_delta.bin changes=%d value_width=%d" % (len(changes), width))
            if at:
                lines.append("detail: at %s -> %s" % (at, block_at(blocks, header, at)))
    elif "blocks_delta.bin" in names:
        changes, width = decode_blocks_delta(zf, header)
        top = {}
        for _index, value in changes:
            name = palette[value] if value < len(palette) else "#%d" % value
            top[name] = top.get(name, 0) + 1
        top_str = ",".join("%s=%d" % (k, v) for k, v in
                           sorted(top.items(), key=lambda kv: (-kv[1], kv[0]))[:8])
        lines.append(
            "ok snap tick=%s size=[%d,%d,%d] palette=%d counts=delta changes=%d top8=%s "
            "entities=%d player.pos=%s ms=%s unloaded=%d file=%dB"
            % (header.get("tick"), nx, ny, nz, len(palette), len(changes), top_str,
               len(entities), player_pos, header.get("ms"), len(unloaded), file_size))
        lines.append("note: delta snapshot (no full blocks grid; baseline tick=%s changed=%s)"
                     % ((header.get("delta") or {}).get("base_tick"),
                        (header.get("delta") or {}).get("changed")))
    else:
        lines.append(
            "ok snap tick=%s size=[%d,%d,%d] palette=%d (no blocks channel) entities=%d "
            "player.pos=%s ms=%s unloaded=%d file=%dB"
            % (header.get("tick"), nx, ny, nz, len(palette), len(entities), player_pos,
               header.get("ms"), len(unloaded), file_size))

    height = decode_height(zf, header, np)
    if height is not None:
        sentinel = int((height == -32768).sum())
        lines.append("height: min=%d max=%d empty_columns=%d (sentinel -32768)"
                     % (int(height.min()), int(height.max()), sentinel))
        if show_detail and at:
            ax, _ay, az = at
            xi = int(ax) - int(origin[0])
            zi = int(az) - int(origin[2])
            if 0 <= xi < nx and 0 <= zi < nz:
                lines.append("detail: height at %s,%s = %d" % (ax, az, int(height[zi, xi])))
    for extra, label in (("flags.bin", "flags"), ("light.bin", "light"), ("biome.bin", "biome")):
        if extra in names:
            lines.append("channel: %s (%d bytes)" % (label, len(zf.read(extra))))
    if show_detail and entities:
        lines.append("entities:")
        for entity in entities:
            lines.append("  " + json.dumps(entity, ensure_ascii=False))
    for line in lines:
        print(line)
    return EXIT_OK


def block_at(blocks, header: dict, at) -> str:
    """(x,y,z) → 方块名；越界返回说明。"""
    nx, ny, nz = _size(header)
    origin = header.get("origin") or [0, 0, 0]
    xi, yi, zi = (int(at[0]) - int(origin[0]), int(at[1]) - int(origin[1]),
                  int(at[2]) - int(origin[2]))
    if not (0 <= xi < nx and 0 <= yi < ny and 0 <= zi < nz):
        return "out-of-range (origin=%s size=%s)" % (origin, header["size"])
    index = int(blocks[yi, zi, xi])
    palette = header.get("palette") or []
    return "%s (palette index=%d, local=(%d,%d,%d))" % (
        palette[index] if index < len(palette) else "#%d" % index, index, xi, yi, zi)


def summarize_header_only(zf: zipfile.ZipFile, header: dict, file_size: int) -> int:
    """没有 numpy 时的退化路径：只解包 + 打印 header 摘要，绝不假装解码。"""
    nx, ny, nz = (int(v) for v in (header.get("size") or [0, 0, 0]))
    palette = header.get("palette") or []
    counts = header.get("counts") or {}
    top = ",".join("%s=%d" % (k, v) for k, v in
                   sorted(counts.items(), key=lambda kv: (-int(kv[1]), kv[0]))[:8])
    print("ok snap tick=%s size=[%d,%d,%d] palette=%d counts(top8)=%s entities=%d "
          "player.pos=%s ms=%s unloaded=%d file=%dB"
          % (header.get("tick"), nx, ny, nz, len(palette), top or "(none)",
             len(header.get("entities") or []), (header.get("player") or {}).get("pos"),
             header.get("ms"), len(header.get("unloaded") or []), file_size))
    print("note: numpy not available -> unpacked only, bins were NOT decoded "
          "(install numpy or use the DSH runtime python)")
    print("entries: %s" % ", ".join(sorted(zf.namelist())))
    return EXIT_OK


def cmd_snap(args: argparse.Namespace) -> int:
    np = _require_numpy()
    at = args.at

    if args.from_file:
        path = args.from_file
        try:
            with open(path, "rb") as handle:
                data = handle.read()
        except OSError as exc:
            raise CipError("cannot read %s: %s" % (path, exc), EXIT_ERROR) from None
        source = path
    else:
        cfg = build_config(args)
        cfg["port"] = probe_port(cfg) if args.probe else cfg["port"]
        query = {}
        for key in ("r", "y0", "y1"):
            value = getattr(args, key)
            if value is not None:
                query[key] = value
        if args.channels:
            query["channels"] = args.channels
        if args.delta:
            query["delta"] = "1"
        result = http_request(cfg, "GET", "/snapshot", query=query)
        if not result.ok:
            return fail_with_body(result)
        data = result.body
        source = "(http)"

    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        # 不是 zip：可能是 JSON 错误体（例如 503 被代理成 200）
        print(data.decode("utf-8", "replace"))
        return EXIT_HTTP_ERROR

    names = zf.namelist()
    if "header.json" not in names:
        raise CipError("zip has no header.json (entries: %s)" % ", ".join(sorted(names)), EXIT_ERROR)

    try:
        header = json.loads(zf.read("header.json").decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise CipError("bad header.json: %s" % exc, EXIT_ERROR) from None

    if not args.from_file:
        # 存 zip 到 /tmp/cip/snap_<tick>.zip（tick 来自 header）
        out_dir = args.out or DEFAULT_OUT_DIR
        try:
            os.makedirs(out_dir, exist_ok=True)
        except OSError as exc:
            raise CipError("cannot create %s: %s" % (out_dir, exc), EXIT_ERROR) from None
        target = os.path.join(out_dir, "snap_%s.zip" % header.get("tick", "unknown"))
        with open(target, "wb") as handle:
            handle.write(data)
        print("saved: %s" % target)

    if np is None:
        return summarize_header_only(zf, header, len(data))
    return summarize_snapshot(zf, header, np, len(data), args.detail, at)


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def add_common(parser: argparse.ArgumentParser, suppress: bool) -> None:
    """全局参数：既接受写在子命令前，也接受写在子命令后。

    ``suppress=True`` 的副本用于 subparser——这样 ``cipctl.py --port 1 ping`` 里主 parser
    解析出的值不会被 subparser 的默认值覆盖。
    """
    default_port = argparse.SUPPRESS if suppress else None
    default_text = argparse.SUPPRESS if suppress else None

    parser.add_argument("--port", type=int, default=default_port,
                        help="bridge 端口（默认 8642；--instance 时读 "
                             "<instance>/config/cipbridge-client.toml）")
    parser.add_argument("--token", default=default_text,
                        help="token（优先于 --token-file/--instance；不会被打进任何日志）")
    parser.add_argument("--token-file", default=default_text,
                        help="从文件读 token（取首个非空行）")
    parser.add_argument("--instance", default=default_text,
                        help="游戏实例目录：<instance>/config/cipbridge/token.txt 取 token，"
                             "<instance>/config/cipbridge-client.toml 取 port")
    parser.add_argument("--probe", action="store_true",
                        default=argparse.SUPPRESS if suppress else False,
                        help="在 port..port+10 上逐个 /ping，自动找真实端口")
    parser.add_argument("--timeout", type=float,
                        default=argparse.SUPPRESS if suppress else DEFAULT_TIMEOUT,
                        help="HTTP 超时秒数（默认 %.0f）" % DEFAULT_TIMEOUT)


def add_at_option(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--at", nargs=3, type=int, metavar=("X", "Y", "Z"),
                        help="与 --detail 同用：打印该世界坐标的方块名（配合 --from-file 验证解码）")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="cipctl.py",
        description="CIP Bridge 客户端 CLI（SPEC v1.1）：ping/state/cmd/snap/find/batch。",
        epilog=(
            "示例:\n"
            "  cipctl.py --instance ~/minecraft ping\n"
            "  cipctl.py --instance ~/minecraft state\n"
            "  cipctl.py --instance ~/minecraft cmd \"/say hi\" --wait 1500\n"
            "  cipctl.py --instance ~/minecraft cmd \"/fill ~ ~ ~ ~5 ~5 ~5 stone\" --confirm\n"
            "  cipctl.py --instance ~/minecraft snap --r 48 --channels blocks,height,entities\n"
            "  cipctl.py snap --from-file /tmp/cip/snap_1234.zip --detail --at 10 64 -20\n"
            "  cipctl.py --instance ~/minecraft find --block diamond_ore --r 64\n"
            "  cipctl.py --instance ~/minecraft batch --file /tmp/cip/batch.txt --spacing 1\n"
            "\n"
            "安全：token 与 --confirm 只进请求头/query/body，永不打印。\n"
            "退出码：0 成功；2 服务端 ok:false（body 原样打印）；3 缺 token/参数组合不成立；\n"
            "        1 连接失败等其它错误。"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    add_common(parser, suppress=False)

    sub = parser.add_subparsers(dest="subcommand", metavar="<command>")

    def make(name: str, help_text: str) -> argparse.ArgumentParser:
        child = sub.add_parser(name, help=help_text, description=help_text)
        add_common(child, suppress=True)
        return child

    ping = make("ping", "GET /ping：探活，打印 body")
    ping.set_defaults(func=cmd_ping)

    state = make("state", "GET /state：玩家状态 JSON")
    state.set_defaults(func=cmd_state)

    cmd = make("cmd", "GET /cmd：执行一条命令并拿回显（--confirm 附 confirm=<token>）")
    cmd.add_argument("command", metavar="\"<command>\"", help="以 / 开头的命令，如 \"/say hi\"")
    cmd.add_argument("--wait", type=int, default=None, help="等待回显毫秒（服务端默认 2000，上限 5000）")
    cmd.add_argument("--confirm", action="store_true", help="危险命令写闸门：附 confirm=<token>")
    cmd.set_defaults(func=cmd_cmd)

    snap = make("snap", "GET /snapshot：下载 zip + numpy 解码 + 一行摘要（--from-file 离线解码）")
    snap.add_argument("--r", type=int, default=None, help="半径（服务端默认 32，上限 128，取整到 16 的倍数）")
    snap.add_argument("--y0", type=int, default=None, help="y 下界（默认玩家 y-32，钳制 -64..320）")
    snap.add_argument("--y1", type=int, default=None, help="y 上界（默认玩家 y+32，钳制 -64..320）")
    snap.add_argument("--channels", default=None,
                      help="逗号分隔：blocks,height,light,flags,biome,entities（默认 blocks,height）")
    snap.add_argument("--delta", action="store_true", help="delta=1：相对上次同参数快照只回变化")
    snap.add_argument("--out", default=None, help="zip 保存目录（默认 %s）" % DEFAULT_OUT_DIR)
    snap.add_argument("--from-file", dest="from_file", default=None,
                      help="离线解码本地 zip（不请求 HTTP，不需要 token）")
    snap.add_argument("--detail", action="store_true", help="额外打印 palette/通道/实体明细")
    add_at_option(snap)
    snap.set_defaults(func=cmd_snap)

    find = make("find", "GET /find：按方块 id 找坐标")
    find.add_argument("--block", required=True, help="方块 id，如 diamond_ore / minecraft:stone")
    find.add_argument("--r", type=int, default=None, help="半径（服务端默认 48，上限 128）")
    find.add_argument("--y0", type=int, default=None, help="y 下界")
    find.add_argument("--y1", type=int, default=None, help="y 上界")
    find.add_argument("--limit", type=int, default=None, help="最多返回条数（上限 256）")
    find.set_defaults(func=cmd_find)

    batch = make("batch", "POST /batch-file：让模组读本地文件批量执行（路径须在 /tmp/cip/ 下）")
    batch.add_argument("--file", required=True, help="命令文件路径（每行一条，须 /tmp/cip/ 前缀）")
    batch.add_argument("--spacing", type=int, default=None, help="spacing_ticks（默认 1，范围 0..20）")
    batch.add_argument("--confirm", action="store_true", help="危险命令写闸门：body 里带 confirm=<token>")
    batch.add_argument("--verbose", action="store_true", help="要逐条回显（服务端 verbose=true）")
    batch.set_defaults(func=cmd_batch)

    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "subcommand", None):
        parser.print_help()
        return EXIT_CONFIG
    try:
        return int(args.func(args))
    except CipError as exc:
        print("cipctl: %s" % exc, file=sys.stderr)
        return exc.code
    except BrokenPipeError:
        try:
            sys.stdout.close()
        except Exception:
            pass
        return EXIT_OK
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
