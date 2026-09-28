#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
异地之约 · TCP 服务端

只依赖标准库 + cryptography，跑在 Orange Pi（Ubuntu / ARM64）上毫无压力。

## 线程模型
```
main thread ── accept() ──▶ 每个连接一个 daemon 线程
                              ├─ 读循环：解析帧 → 解密 → 存库 → 转发
                              └─ 监听线程退出时清理连接并通知对方离线
cleanup thread ── 每 6 小时清理过期数据
```

## 协议
完整定义见 ../docs/PROTOCOL.md，与 Android 端 `data/network/Protocol.kt` 一一对应。

帧格式：`struct.pack(">I", len) + utf-8 JSON`

## auth 的例外说明（重要）
除 `auth` 外，所有消息的业务体都在 `payload` 里用 AES-GCM 加密。
但 `auth` **必须**把 `pair_code` 放在信封明文字段里 —— 因为密钥正是由配对码派生的，
服务端如果不知道配对码，就没有密钥去解密 `payload`，这是鸡生蛋问题。

安全性权衡：
  * `auth` 的密文 payload 里也带一份 `pair_code`/`device_id`，解密成功即证明
    客户端确实持有该配对码派生的密钥（相当于一次 challenge-response）。
  * 配对码只在建立连接时传输一次，且服务端**不落库**（只存 sha256 摘要）。
  * 公网那一段（手机 → frps）建议开启 frp 自带的 TLS，见 README。

用法：
    python3 server.py                  # 默认监听 0.0.0.0:9527
    python3 server.py --port 9600
    python3 server.py --debug          # 打开 DEBUG 日志
    python3 server.py --stats          # 打印各表行数后退出
"""

import argparse
import base64
import errno
import json
import logging
import logging.handlers
import os
import signal
import socket
import struct
import sys
import threading
import time
import traceback
from typing import Any, Dict, List, Optional

import config
import crypto
from database import Database

logger = logging.getLogger("baobao.server")

# 4 字节大端序长度头
_HEADER = struct.Struct(">I")

# 消息类型常量（与 Protocol.kt 保持一致）
TYPE_AUTH = "auth"
TYPE_AUTH_OK = "auth_ok"
TYPE_BATTERY = "battery"
TYPE_LOCATION = "location"
TYPE_USAGE = "usage"
TYPE_AVATAR = "avatar"
TYPE_PING = "ping"
TYPE_PONG = "pong"
TYPE_STATUS = "status"
TYPE_HISTORY_REQUEST = "history_request"
TYPE_HISTORY_RESPONSE = "history_response"
TYPE_BYE = "bye"
TYPE_ERROR = "error"

# 会落库并转发给伙伴的上报类型
DATA_TYPES = (TYPE_BATTERY, TYPE_LOCATION, TYPE_USAGE)

# history_request 允许查询的类型（头像额外复用这条通道）
VALID_HISTORY_TYPES = DATA_TYPES + (TYPE_AVATAR,)

# 允许的头像 MIME 类型
AVATAR_MIMES = ("image/jpeg", "image/png", "image/webp")


# ======================================================================
# 帧读写
# ======================================================================

def recv_exact(sock: socket.socket, size: int) -> Optional[bytes]:
    """
    读满 size 字节。TCP 是字节流，一次 recv 可能只拿到半个帧，必须循环。

    :return: 读到的字节；对端正常关闭返回 None
    """
    chunks = bytearray()
    while len(chunks) < size:
        try:
            chunk = sock.recv(size - len(chunks))
        except socket.timeout:
            raise
        except (ConnectionResetError, OSError):
            return None
        if not chunk:
            return None
        chunks.extend(chunk)
    return bytes(chunks)


def read_envelope(sock: socket.socket) -> Optional[dict]:
    """
    读取一个完整帧并解析成 dict。

    :return: 信封 dict；连接关闭返回 None
    :raises ValueError: 帧超长或 JSON 非法
    """
    header = recv_exact(sock, _HEADER.size)
    if header is None:
        return None

    (length,) = _HEADER.unpack(header)
    if length <= 0 or length > config.MAX_PAYLOAD:
        raise ValueError(f"非法帧长度 {length}")

    body = recv_exact(sock, length)
    if body is None:
        return None

    try:
        envelope = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(f"JSON 解析失败：{exc}") from exc

    if not isinstance(envelope, dict):
        raise ValueError("信封不是 JSON 对象")
    return envelope


def encode_frame(obj: dict) -> bytes:
    """把 dict 打包成 `长度头 + UTF-8 JSON`"""
    body = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(body) > config.MAX_PAYLOAD:
        raise ValueError(f"要发送的帧过大：{len(body)} 字节")
    return _HEADER.pack(len(body)) + body


# ======================================================================
# 连接对象
# ======================================================================

class ClientConnection:
    """一条已建立的 TCP 连接。"""

    def __init__(self, sock: socket.socket, addr):
        self.sock = sock
        self.addr = addr

        self.device_id: Optional[str] = None
        self.pair_code: Optional[str] = None      # 仅保存在内存中，用于派生密钥与转发
        self.key: Optional[bytes] = None
        self.partner_id: Optional[str] = None
        self.model: str = ""

        self.authenticated = False
        self.closed = False
        self.connected_at = time.time()

        # 是否已经从 _pending_connections 配额里归还过（保证只还一次）
        self.pending_released = False

        self._send_lock = threading.Lock()

    # ---------------- 发送 ----------------

    def send_raw(self, envelope: dict) -> bool:
        """直接发送一个信封（不加密）。"""
        if self.closed:
            return False
        try:
            with self._send_lock:
                self.sock.sendall(encode_frame(envelope))
            return True
        except (BrokenPipeError, ConnectionResetError, OSError) as exc:
            logger.debug("发送失败 %s：%s", self.device_id, exc)
            self.closed = True
            return False

    def send_encrypted(
        self,
        msg_type: str,
        payload: Optional[dict] = None,
        plain_extra: Optional[dict] = None,
        from_device: Optional[str] = None,
    ) -> bool:
        """
        发送一条业务消息：payload 加密后放入 `payload` 字段。

        :param plain_extra: 额外放在信封明文里的字段（如 status.online）
        :param from_device: 转发场景下标记数据来源设备
        """
        envelope: Dict[str, Any] = {
            "type": msg_type,
            "ts": int(time.time() * 1000),
        }
        if from_device:
            envelope["from"] = from_device
        if plain_extra:
            envelope.update(plain_extra)

        if payload is not None:
            if self.key is None:
                logger.error("尚未认证，无法加密发送 %s", msg_type)
                return False
            try:
                envelope["payload"] = crypto.encrypt_json(payload, self.key)
            except Exception as exc:              # noqa: BLE001
                logger.exception("加密失败：%s", exc)
                return False

        return self.send_raw(envelope)

    def send_error(self, message: str, code: str = "internal") -> bool:
        """
        发送错误消息。

        尽量把 message 也加密一份（客户端能解），同时在信封里放明文副本，
        这样即使客户端解密失败，用户也能看到原因。
        """
        payload = {"message": message, "code": code}
        if self.key is not None:
            return self.send_encrypted(TYPE_ERROR, payload, plain_extra={"message": message})
        # 认证前没有密钥，只能发明文；把 code 也一并带上，方便客户端区分错误类型
        return self.send_raw(
            {
                "type": TYPE_ERROR,
                "ts": int(time.time() * 1000),
                "message": message,
                "code": code,
            }
        )

    def close(self) -> None:
        self.closed = True
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass

    def __repr__(self) -> str:
        who = self.device_id[:8] if self.device_id else "未认证"
        return f"<ClientConnection {who} {self.addr}>"


# ======================================================================
# 服务端主体
# ======================================================================

class BaobaoServer:

    def __init__(self, host: str = config.HOST, port: int = config.PORT):
        self.host = host
        self.port = port

        self.db = Database()
        self.connections: Dict[str, ClientConnection] = {}
        self._lock = threading.RLock()

        self._server_sock: Optional[socket.socket] = None
        self._running = False
        self._threads: List[threading.Thread] = []

        # 已 accept 但还没认证完成的连接数（用于限制总并发，防 fd 耗尽）
        self._pending_connections = 0

    # ------------------------------------------------------------------
    # 启动 / 停止
    # ------------------------------------------------------------------

    def serve_forever(self) -> None:
        server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # 允许端口复用，重启服务时不会因为 TIME_WAIT 而 bind 失败
        server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_sock.bind((self.host, self.port))
        server_sock.listen(64)
        server_sock.settimeout(1.0)               # 便于定期检查 _running
        self._server_sock = server_sock
        self._running = True

        logger.info("=" * 56)
        logger.info("异地之约 服务端已启动，监听 %s:%d", self.host, self.port)
        logger.info("数据库：%s", config.DB_PATH)
        logger.info("历史保留：%s 天", config.HISTORY_RETENTION_DAYS or "永久")
        logger.info("=" * 56)

        cleanup_thread = threading.Thread(
            target=self._cleanup_loop, name="cleanup", daemon=True
        )
        cleanup_thread.start()
        self._threads.append(cleanup_thread)

        while self._running:
            try:
                client_sock, addr = server_sock.accept()
            except socket.timeout:
                continue
            except OSError as exc:
                # 只有「我们自己关掉了监听套接字」才是致命错误。
                # ECONNABORTED / EMFILE / EINTR 都是瞬时故障，
                # 直接 break 会让一次 fd 耗尽就把整个服务永久停掉（廉价 DoS）。
                if not self._running:
                    break
                if exc.errno in (
                    errno.ECONNABORTED,
                    errno.EINTR,
                    errno.EMFILE,
                    errno.ENFILE,
                    errno.ENOBUFS,
                    errno.ENOMEM,
                ):
                    logger.warning("accept 暂时失败（%s），稍后重试", exc)
                    time.sleep(0.2)
                    continue
                logger.error("accept 致命错误：%s", exc)
                break

            with self._lock:
                # 未认证的连接也要计入上限，否则可以开一堆空连接把 fd 表撑爆
                pending = self._pending_connections
                if len(self.connections) + pending >= config.MAX_CONNECTIONS:
                    logger.warning(
                        "连接数已达上限 %d（在线 %d / 待认证 %d），拒绝 %s",
                        config.MAX_CONNECTIONS, len(self.connections), pending, addr,
                    )
                    try:
                        client_sock.close()
                    except OSError:
                        pass
                    continue
                self._pending_connections += 1

            client_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            thread = threading.Thread(
                target=self._client_thread,
                args=(client_sock, addr),
                name=f"client-{addr[0]}:{addr[1]}",
                daemon=True,
            )
            # 顺手清理已结束的线程，避免 _threads 随着连接数无限增长
            self._threads = [t for t in self._threads if t.is_alive()]
            self._threads.append(thread)
            thread.start()

    def shutdown(self) -> None:
        logger.info("正在关闭服务端……")
        self._running = False

        if self._server_sock is not None:
            try:
                self._server_sock.close()
            except OSError:
                pass

        with self._lock:
            connections = list(self.connections.values())
        for conn in connections:
            try:
                conn.send_error("服务端正在关闭", "internal")
            except Exception:                     # noqa: BLE001
                pass
            conn.close()

        # 等客户端线程收尾，再关数据库。
        # 否则某个线程可能正卡在 save_xxx() 上，直接 db.close() 会抛
        # "Cannot operate on a closed database"，那条上报会静默丢失。
        for thread in list(self._threads):
            if thread is threading.current_thread():
                continue
            thread.join(timeout=2.0)

        self.db.close()
        logger.info("已关闭")

    # ------------------------------------------------------------------
    # 单连接处理
    # ------------------------------------------------------------------

    def _client_thread(self, client_sock: socket.socket, addr) -> None:
        conn = ClientConnection(client_sock, addr)
        logger.info("新连接 %s", addr)

        try:
            # 1) 认证（有独立超时，防止空连接长期占用）
            client_sock.settimeout(config.AUTH_TIMEOUT)
            authenticated = self._authenticate(conn)

            # 认证阶段一结束就归还「待认证」配额：
            # 成功的话它已经进入 self.connections，由那边的上限单独管。
            self._release_pending(conn)

            if not authenticated:
                return

            # 2) 进入正常收发循环
            client_sock.settimeout(config.SOCKET_TIMEOUT)
            self._register_connection(conn)
            self._message_loop(conn)

        except socket.timeout:
            logger.info("%s 读超时，断开", conn)
        except ValueError as exc:
            logger.warning("%s 协议错误：%s", addr, exc)
            try:
                conn.send_error(str(exc), "bad_frame")
            except Exception:                     # noqa: BLE001
                pass
        except Exception as exc:                  # noqa: BLE001
            logger.error("%s 处理异常：%s\n%s", addr, exc, traceback.format_exc())
        finally:
            # 认证中途抛异常（超时/坏帧）也要归还配额，否则配额会被慢慢吃光
            self._release_pending(conn)
            self._unregister_connection(conn)
            conn.close()
            logger.info("连接结束 %s", conn)

    def _release_pending(self, conn: ClientConnection) -> None:
        """归还「已 accept 未认证」配额。幂等，可以重复调用。"""
        with self._lock:
            if conn.pending_released:
                return
            conn.pending_released = True
            self._pending_connections = max(0, self._pending_connections - 1)

    # ---------------- 认证 ----------------

    def _authenticate(self, conn: ClientConnection) -> bool:
        envelope = read_envelope(conn.sock)
        if envelope is None:
            logger.info("%s 未发送任何数据就断开", conn.addr)
            return False

        msg_type = envelope.get("type")
        if msg_type != TYPE_AUTH:
            conn.send_error("第一条消息必须是 auth", "auth_required")
            logger.warning("%s 首帧类型是 %s，拒绝", conn.addr, msg_type)
            return False

        device_id = envelope.get("device_id")
        pair_code = envelope.get("pair_code")          # 见文件头“auth 的例外说明”
        payload_b64 = envelope.get("payload")

        if not device_id or not isinstance(device_id, str):
            conn.send_error("auth 缺少 device_id", "auth_failed")
            return False
        if not pair_code or not isinstance(pair_code, str):
            conn.send_error("auth 缺少 pair_code", "auth_failed")
            return False
        if not payload_b64:
            conn.send_error("auth 缺少 payload", "auth_failed")
            return False

        # 由配对码派生密钥（内部有缓存）
        key = crypto.derive_key(pair_code)

        # 解密 payload —— 成功即证明客户端确实持有同一配对码派生出的密钥
        try:
            proof = crypto.decrypt_json(payload_b64, key)
        except ValueError as exc:
            conn.send_error(f"认证失败：{exc}", "auth_failed")
            logger.warning("%s 认证失败：%s", conn.addr, exc)
            return False

        proof_device = proof.get("device_id")
        if proof_device and proof_device != device_id:
            conn.send_error("auth 的 device_id 与密文不一致", "auth_failed")
            return False
        if proof.get("pair_code") not in (None, pair_code):
            conn.send_error("auth 的 pair_code 与密文不一致", "auth_failed")
            return False

        conn.device_id = device_id
        conn.pair_code = pair_code
        conn.key = key
        conn.model = str(proof.get("model", ""))[:64]
        conn.authenticated = True

        # 注册设备并拿到伙伴 ID
        partner_id = self.db.register_device(
            device_id=device_id,
            pair_code=pair_code,
            app_version=str(proof.get("app_version", ""))[:32],
            model=conn.model,
        )
        conn.partner_id = partner_id

        partner_online = False
        if partner_id:
            with self._lock:
                partner_conn = self.connections.get(partner_id)
                partner_online = partner_conn is not None and not partner_conn.closed

        # 回复 auth_ok
        ok = conn.send_encrypted(
            TYPE_AUTH_OK,
            payload={
                "partner_device_id": partner_id,
                "online": partner_online,
                "server_time": int(time.time() * 1000),
                "protocol": 1,
            },
            # 信封里冗余一份明文，方便抓包排查（客户端以密文为准）
            plain_extra={"partner_device_id": partner_id},
        )
        if not ok:
            return False

        logger.info(
            "认证成功 device=%s model=%s partner=%s",
            device_id[:8], conn.model or "未知", (partner_id or "无")[:8],
        )
        return True

    # ---------------- 连接注册表 ----------------

    def _register_connection(self, conn: ClientConnection) -> None:
        assert conn.device_id is not None

        with self._lock:
            old = self.connections.get(conn.device_id)
            self.connections[conn.device_id] = conn

        # 同一设备重复登录：踢掉旧连接（换手机重装后常见）
        if old is not None and old is not conn:
            logger.info("设备 %s 重复登录，关闭旧连接 %s", conn.device_id[:8], old.addr)
            old.send_error("该设备已在别处登录", "internal")
            old.close()

        self.db.touch_device(conn.device_id)

        # ★ 关键：刷新所有在线连接的 partner_id
        # conn.partner_id 是「认证那一刻」的快照。如果本机是第一台连上来的设备，
        # 那时还没有伙伴，partner_id 就是 None —— 之后对方上线也不会自动补上，
        # 结果就是「先打开 APP 的那台手机，数据永远发不出去」。
        self._refresh_pairings()

        # 通知伙伴：我上线了
        if conn.partner_id:
            self._send_status_to(conn.partner_id, online=True, partner_device_id=conn.device_id)

    def _refresh_pairings(self) -> None:
        """
        把数据库里的配对关系同步到内存中的连接对象上。

        设备数量极少（个人使用就两台），直接遍历在线连接查一次库，开销可以忽略。
        """
        with self._lock:
            conns = list(self.connections.values())

        for existing in conns:
            if existing.device_id is None:
                continue
            latest = self.db.get_partner_id(existing.device_id)
            if latest != existing.partner_id:
                logger.info(
                    "刷新配对关系 %s: %s -> %s",
                    existing.device_id[:8],
                    (existing.partner_id or "无")[:8],
                    (latest or "无")[:8],
                )
                existing.partner_id = latest

    def _unregister_connection(self, conn: ClientConnection) -> None:
        if conn.device_id is None:
            return

        with self._lock:
            # 只有当注册表里还是自己时才移除（避免把新连接踢掉）
            if self.connections.get(conn.device_id) is conn:
                self.connections.pop(conn.device_id, None)
                still_online = False
            else:
                still_online = True

        if not still_online and conn.partner_id:
            self._send_status_to(
                conn.partner_id, online=False, partner_device_id=conn.device_id
            )

    def _get_connection(self, device_id: Optional[str]) -> Optional[ClientConnection]:
        if not device_id:
            return None
        with self._lock:
            conn = self.connections.get(device_id)
        if conn is None or conn.closed:
            return None
        return conn

    def _send_status_to(self, target_device_id: str, online: bool, partner_device_id: str) -> None:
        target = self._get_connection(target_device_id)
        if target is None:
            return
        target.send_encrypted(
            TYPE_STATUS,
            payload={
                "partner_device_id": partner_device_id,
                "online": online,
            },
            plain_extra={"online": online},
        )
        logger.debug("通知 %s：对方 %s", target_device_id[:8], "上线" if online else "离线")

    # ---------------- 消息循环 ----------------

    def _message_loop(self, conn: ClientConnection) -> None:
        while self._running and not conn.closed:
            envelope = read_envelope(conn.sock)
            if envelope is None:
                logger.info("%s 关闭了连接", conn)
                return

            msg_type = envelope.get("type")
            if not isinstance(msg_type, str) or not msg_type:
                conn.send_error("消息缺少 type", "bad_frame")
                continue

            try:
                self._dispatch(conn, msg_type, envelope)
            except ValueError as exc:
                logger.warning("处理 %s 失败：%s", msg_type, exc)
                conn.send_error(str(exc), "bad_request")
            except Exception as exc:              # noqa: BLE001
                logger.error("处理 %s 异常：%s\n%s", msg_type, exc, traceback.format_exc())
                conn.send_error("服务端内部错误", "internal")

    def _dispatch(self, conn: ClientConnection, msg_type: str, envelope: dict) -> None:
        if msg_type == TYPE_PING:
            # 心跳：回 pong，客户端用它算 RTT 并确认链路存活
            conn.send_raw({"type": TYPE_PONG, "ts": int(time.time() * 1000)})
            return

        if msg_type == TYPE_BYE:
            logger.info("%s 主动断开", conn)
            conn.closed = True
            return

        if msg_type in (TYPE_BATTERY, TYPE_LOCATION, TYPE_USAGE):
            self._handle_report(conn, msg_type, envelope)
            return

        if msg_type == TYPE_AVATAR:
            self._handle_avatar(conn, envelope)
            return

        if msg_type == TYPE_HISTORY_REQUEST:
            self._handle_history_request(conn, envelope)
            return

        if msg_type == TYPE_AUTH:
            # 已经认证过还发 auth，重新走一遍认证流程没有意义
            conn.send_error("已经认证过了", "bad_request")
            return

        logger.warning("未知消息类型：%s", msg_type)
        conn.send_error(f"不支持的消息类型：{msg_type}", "unknown_type")

    # ---------------- 上报与转发 ----------------

    def _handle_report(self, conn: ClientConnection, msg_type: str, envelope: dict) -> None:
        assert conn.key is not None and conn.device_id is not None

        payload_b64 = envelope.get("payload")
        if not payload_b64:
            conn.send_error(f"{msg_type} 缺少 payload", "bad_request")
            return

        # 1) 解密。解不开通常是配对码变了或有人在篡改 —— 按协议直接断开，
        #    否则攻击者可以靠不断发坏帧让连接一直挂着刷解密失败
        try:
            data = crypto.decrypt_json(payload_b64, conn.key)
        except ValueError as exc:
            logger.warning("%s 解密 %s 失败：%s", conn.device_id[:8], msg_type, exc)
            conn.send_error(f"解密失败：{exc}", "auth_failed")
            conn.closed = True
            return

        now_ms = int(time.time() * 1000)

        # 2) 存库
        if msg_type == TYPE_BATTERY:
            level = int(data.get("level", 0))
            if not 0 <= level <= 100:
                raise ValueError(f"电量越界：{level}")
            charging = bool(data.get("charging", False))
            timestamp = int(data.get("timestamp") or now_ms)
            self.db.save_battery(conn.device_id, level, charging, timestamp)
            clean = {
                "level": level,
                "charging": charging,
                "timestamp": timestamp,
            }

        elif msg_type == TYPE_LOCATION:
            lat = float(data.get("lat", 0.0))
            lng = float(data.get("lng", 0.0))
            if not (-90.0 <= lat <= 90.0) or not (-180.0 <= lng <= 180.0):
                raise ValueError(f"坐标越界：{lat},{lng}")
            accuracy = float(data.get("accuracy", 0.0) or 0.0)
            # accuracy 也要校验：Infinity/NaN 会被 json.dumps 写成非法的 JSON 字面量
            # （Infinity / NaN），Android 的 JSONObject 会解析失败并丢掉整帧
            if not (0.0 <= accuracy <= 1e7):
                accuracy = 0.0
            # 行进方向角：0~360 有效，其余一律记为 -1（未知）
            try:
                bearing = float(data.get("bearing", -1.0))
            except (TypeError, ValueError):
                bearing = -1.0
            if not (0.0 <= bearing < 360.0):
                bearing = -1.0
            provider = str(data.get("provider", ""))[:32]
            timestamp = int(data.get("timestamp") or now_ms)
            self.db.save_location(
                conn.device_id, lat, lng, accuracy, provider, timestamp, bearing
            )
            clean = {
                "lat": lat,
                "lng": lng,
                "accuracy": accuracy,
                "provider": provider,
                "bearing": bearing,
                "timestamp": timestamp,
            }

        else:  # TYPE_USAGE
            package_name = str(data.get("package_name", ""))[:128]
            if not package_name:
                raise ValueError("usage 缺少 package_name")
            app_label = str(data.get("app_label", ""))[:64]
            event_type = str(data.get("event_type", "start"))
            if event_type not in ("start", "end"):
                raise ValueError(f"非法 event_type：{event_type}")
            timestamp = int(data.get("timestamp") or now_ms)
            self.db.save_usage(
                conn.device_id, package_name, app_label, event_type, timestamp
            )
            clean = {
                "package_name": package_name,
                "app_label": app_label,
                "event_type": event_type,
                "timestamp": timestamp,
            }

        logger.debug("存入 %s <- %s", msg_type, conn.device_id[:8])

        # 3) 转发给伙伴（在线才转，离线靠对方上线后拉历史补齐）
        # 自愈：万一内存里的 partner_id 过期了（比如对方刚重装），回库里再确认一次
        partner_id = conn.partner_id or self.db.get_partner_id(conn.device_id)
        if partner_id != conn.partner_id:
            conn.partner_id = partner_id
        partner = self._get_connection(partner_id)
        if partner is None:
            return

        forwarded = partner.send_encrypted(
            msg_type,
            payload=clean,
            from_device=conn.device_id,
        )
        if forwarded:
            logger.debug("转发 %s %s -> %s", msg_type, conn.device_id[:8], partner.device_id[:8])

    # ---------------- 头像 ----------------

    def _handle_avatar(self, conn: ClientConnection, envelope: dict) -> None:
        """
        处理头像上传：落库（每人只留最新一张）并转发给对方。

        头像不是「时间序列」而是「最新状态」，所以走独立的 avatars 表，
        不参与 cleanup_old_records 的过期清理。
        """
        assert conn.key is not None and conn.device_id is not None

        payload_b64 = envelope.get("payload")
        if not payload_b64:
            conn.send_error("avatar 缺少 payload", "bad_request")
            return

        try:
            data = crypto.decrypt_json(payload_b64, conn.key)
        except ValueError as exc:
            conn.send_error(f"解密失败：{exc}", "auth_failed")
            conn.closed = True
            return

        mime = str(data.get("mime", "image/jpeg"))[:64]
        if mime not in AVATAR_MIMES:
            mime = "image/jpeg"

        encoded = data.get("data")
        if not isinstance(encoded, str) or not encoded:
            conn.send_error("avatar 缺少 data", "bad_request")
            return

        try:
            raw = base64.b64decode(encoded, validate=True)
        except Exception:                          # noqa: BLE001
            conn.send_error("头像数据不是合法的 Base64", "bad_request")
            return

        if not raw:
            conn.send_error("头像数据为空", "bad_request")
            return
        if len(raw) > config.MAX_AVATAR_BYTES:
            conn.send_error(
                f"头像过大：{len(raw)} 字节（上限 {config.MAX_AVATAR_BYTES}）", "bad_request"
            )
            return

        timestamp = int(data.get("timestamp") or int(time.time() * 1000))
        self.db.save_avatar(conn.device_id, mime, raw, timestamp)

        partner_id = conn.partner_id or self.db.get_partner_id(conn.device_id)
        conn.partner_id = partner_id
        partner = self._get_connection(partner_id)
        if partner is None:
            logger.info("对方不在线，头像已存库待其上线后拉取")
            return

        partner.send_encrypted(
            TYPE_AVATAR,
            payload={"mime": mime, "data": encoded, "timestamp": timestamp},
            from_device=conn.device_id,
        )
        logger.info("头像已转发 %s -> %s", conn.device_id[:8], partner.device_id[:8])

    # ---------------- 历史查询 ----------------

    def _handle_history_request(self, conn: ClientConnection, envelope: dict) -> None:
        assert conn.key is not None and conn.device_id is not None

        payload_b64 = envelope.get("payload")
        if not payload_b64:
            conn.send_error("history_request 缺少 payload", "bad_request")
            return

        try:
            request = crypto.decrypt_json(payload_b64, conn.key)
        except ValueError as exc:
            conn.send_error(f"解密失败：{exc}", "auth_failed")
            conn.closed = True
            return

        data_type = request.get("data_type")
        if data_type not in VALID_HISTORY_TYPES:
            conn.send_error(f"非法 data_type：{data_type}", "bad_request")
            return

        # 只能查伙伴的数据；target_device_id 只做校验，防止被用来读别人的数据
        target_device_id = request.get("target_device_id") or conn.partner_id
        if conn.partner_id is None or target_device_id != conn.partner_id:
            conn.send_error("尚未配对，无法查询对方数据", "bad_request")
            return

        limit = request.get("limit", config.HISTORY_DEFAULT_LIMIT)
        try:
            limit = int(limit)
        except (TypeError, ValueError):
            limit = config.HISTORY_DEFAULT_LIMIT
        limit = max(1, min(limit, config.HISTORY_MAX_LIMIT))

        if data_type == TYPE_BATTERY:
            records = self.db.query_battery(target_device_id, limit)
        elif data_type == TYPE_LOCATION:
            records = self.db.query_location(target_device_id, limit)
        elif data_type == TYPE_AVATAR:
            # 头像只有一张，limit 无意义
            records = self.db.query_avatar(target_device_id)
        else:
            records = self.db.query_usage(target_device_id, limit)

        conn.send_encrypted(
            TYPE_HISTORY_RESPONSE,
            payload={"data_type": data_type, "records": records},
        )
        logger.info(
            "历史查询 %s -> %s %d 条", conn.device_id[:8], data_type, len(records)
        )

    # ------------------------------------------------------------------
    # 后台维护
    # ------------------------------------------------------------------

    def _cleanup_loop(self) -> None:
        while self._running:
            # 分段睡眠，退出时能及时响应
            for _ in range(config.CLEANUP_INTERVAL):
                if not self._running:
                    return
                time.sleep(1)
            try:
                self.db.cleanup_old_records()
            except Exception as exc:              # noqa: BLE001
                logger.error("清理任务异常：%s", exc)


# ======================================================================
# 日志
# ======================================================================

def setup_logging(debug: bool = False) -> None:
    level = logging.DEBUG if debug else getattr(logging, config.LOG_LEVEL, logging.INFO)
    root = logging.getLogger()
    root.setLevel(level)

    formatter = logging.Formatter(
        fmt="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(formatter)
    root.addHandler(console)

    if config.LOG_TO_FILE:
        directory = os.path.dirname(os.path.abspath(config.LOG_FILE))
        if directory:
            os.makedirs(directory, exist_ok=True)
        file_handler = logging.handlers.RotatingFileHandler(
            config.LOG_FILE,
            maxBytes=config.LOG_MAX_BYTES,
            backupCount=config.LOG_BACKUP_COUNT,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)


# ======================================================================
# 入口
# ======================================================================

def main() -> int:
    parser = argparse.ArgumentParser(description="异地之约 服务端")
    parser.add_argument("--host", default=config.HOST, help="监听地址")
    parser.add_argument("--port", type=int, default=config.PORT, help="监听端口")
    parser.add_argument("--db", default=config.DB_PATH, help="SQLite 数据库路径")
    parser.add_argument("--debug", action="store_true", help="打开 DEBUG 日志")
    parser.add_argument("--stats", action="store_true", help="打印统计信息后退出")
    args = parser.parse_args()

    setup_logging(args.debug)

    if args.db != config.DB_PATH:
        config.DB_PATH = args.db

    if args.stats:
        database = Database(args.db)
        print(json.dumps(database.stats(), ensure_ascii=False, indent=2))
        database.close()
        return 0

    server = BaobaoServer(host=args.host, port=args.port)

    def handle_signal(signum, _frame):
        logger.info("收到信号 %s", signum)
        server.shutdown()

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()
    except OSError as exc:
        logger.error("启动失败：%s", exc)
        logger.error("端口被占用？用 `ss -lntp | grep %d` 检查一下", args.port)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
