#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
服务端自测脚本（不需要手机）。

模拟两台手机用同一个配对码连上服务端，完整跑一遍：
    认证 -> 配对 -> 电量/定位/使用上报 -> 转发 -> 历史查询 -> 心跳 -> 错误配对码

用途：部署完服务端后，先用它确认“协议 + 加密 + 转发 + 存库”整条链路是通的，
     再去折腾 APP 和手机权限。

用法：
    # 终端 1：启动服务端
    python3 server.py --port 19527 --db /tmp/test.db

    # 终端 2：跑测试
    python3 test_client.py --port 19527
"""

import argparse
import base64
import json
import socket
import struct
import sys
import threading
import time
from typing import Any, Dict, Optional

import crypto

_HEADER = struct.Struct(">I")

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"


class TestClient:
    """一个极简的协议客户端，逻辑与 Android 端 TcpClient 一致。"""

    def __init__(self, host: str, port: int, device_id: str, pair_code: str):
        self.host = host
        self.port = port
        self.device_id = device_id
        self.pair_code = pair_code
        self.key = crypto.derive_key(pair_code)

        self.sock: Optional[socket.socket] = None
        self.inbox: list = []
        self._lock = threading.Lock()
        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._seq = 0

    # ---------------- 连接 ----------------

    def connect(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        # 连上之后切回阻塞模式：读线程只靠 close() 退出。
        # 如果保留 5 秒超时，socket.timeout 是 OSError 的子类，
        # 会被 _read_loop 的 except 吃掉，一次静默就永久终止读线程，
        # 之后所有 wait_for 都会误报 FAIL。
        self.sock.settimeout(None)
        self._running = True
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._running = False
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass

    # ---------------- 收发 ----------------

    def send(self, msg_type: str, payload: Optional[dict] = None,
             plain_extra: Optional[dict] = None) -> None:
        envelope: Dict[str, Any] = {
            "type": msg_type,
            "device_id": self.device_id,
            "ts": int(time.time() * 1000),
        }
        if plain_extra:
            envelope.update(plain_extra)
        if payload is not None:
            envelope["payload"] = crypto.encrypt_json(payload, self.key)

        body = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        assert self.sock is not None
        self.sock.sendall(_HEADER.pack(len(body)) + body)

    def _read_loop(self) -> None:
        try:
            while self._running:
                header = self._recv_exact(4)
                if header is None:
                    return
                (length,) = _HEADER.unpack(header)
                body = self._recv_exact(length)
                if body is None:
                    return
                envelope = json.loads(body.decode("utf-8"))
                # 自动回 pong 不需要，客户端只收
                with self._lock:
                    self.inbox.append(envelope)
        except (OSError, ValueError):
            return

    def _recv_exact(self, size: int) -> Optional[bytes]:
        buf = bytearray()
        while len(buf) < size:
            chunk = self.sock.recv(size - len(buf))       # type: ignore[union-attr]
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    def wait_for(self, msg_type: str, timeout: float = 5.0) -> Optional[dict]:
        """等待指定类型的消息，期间把其它消息留在队列里"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self._lock:
                for index, envelope in enumerate(self.inbox):
                    if envelope.get("type") == msg_type:
                        return self.inbox.pop(index)
            time.sleep(0.02)
        return None

    def decrypt(self, envelope: dict) -> dict:
        payload = envelope.get("payload")
        if not payload:
            return {}
        return crypto.decrypt_json(payload, self.key)

    def auth(self) -> Optional[dict]:
        """
        发送 auth。注意：pair_code 必须明文放信封里（服务端拿它派生密钥），
        密文 payload 里再放一份，作为“确实持有密钥”的证明。
        """
        self.send(
            "auth",
            payload={
                "pair_code": self.pair_code,
                "device_id": self.device_id,
                "app_version": "test-client",
                "model": "Python Simulator",
            },
            plain_extra={"pair_code": self.pair_code},
        )
        return self.wait_for("auth_ok")


# ======================================================================
# 测试用例
# ======================================================================

def main() -> int:
    parser = argparse.ArgumentParser(description="异地之约 服务端自测")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19527)
    args = parser.parse_args()

    pair_code = "528520"
    results = []

    # 每次运行都用不同的设备 ID：否则第二次跑同一个数据库时，
    # 服务端会认为 A 早就有伙伴了，「A 首次认证时还没有伙伴」这条会误报失败。
    run_tag = f"{int(time.time()) % 1_000_000:06d}"
    device_a_id = f"AAAA{run_tag}"
    device_b_id = f"BBBB{run_tag}"

    def check(name: str, ok: bool, detail: str = "") -> None:
        results.append(ok)
        mark = PASS if ok else FAIL
        print(f"[{mark}] {name}" + (f"  ({detail})" if detail else ""))

    print(f"连接 {args.host}:{args.port} …\n")

    # ---------- 1. 错误配对码应被拒绝 ----------
    # 先单独验证：客户端用自己的密钥加密，但明文声明了另一个配对码，服务端解不开
    bad = TestClient(args.host, args.port, "baddevice0001", "111111")
    try:
        bad.connect()
        bad.send(
            "auth",
            payload={"pair_code": "999999", "device_id": "baddevice0001"},
            plain_extra={"pair_code": "999999"},   # 明文与密文不一致
        )
        error = bad.wait_for("error")
        check("配对码不一致时返回 error", error is not None,
              error.get("message", "") if error else "无响应")
    except OSError as exc:
        check("配对码不一致时返回 error", False, str(exc))
    finally:
        bad.close()

    # ---------- 2. 设备 A 认证 ----------
    device_a = TestClient(args.host, args.port, device_a_id, pair_code)
    device_a.connect()
    auth_a = device_a.auth()
    check("设备 A 认证成功", auth_a is not None)
    if auth_a is None:
        print("\n认证都失败了，后面不用测了。检查服务端是否在运行。")
        return 1

    payload_a = device_a.decrypt(auth_a)
    check("A 首次认证时还没有伙伴", payload_a.get("partner_device_id") is None,
          f"partner={payload_a.get('partner_device_id')}")

    # ---------- 3. 设备 B 认证 + 互相配对 ----------
    device_b = TestClient(args.host, args.port, device_b_id, pair_code)
    device_b.connect()
    auth_b = device_b.auth()
    check("设备 B 认证成功", auth_b is not None)
    if auth_b is None:
        return 1

    payload_b = device_b.decrypt(auth_b)
    check("B 拿到伙伴 ID = A", payload_b.get("partner_device_id") == device_a_id,
          f"partner={payload_b.get('partner_device_id')}")

    status = device_a.wait_for("status")
    check("A 收到 B 上线的 status 推送",
          status is not None and status.get("online") is True)

    # ---------- 4. 电量上报与转发 ----------
    device_a.send("battery", {"level": 88, "charging": True,
                             "timestamp": int(time.time() * 1000)})
    battery = device_b.wait_for("battery")
    forwarded = device_b.decrypt(battery) if battery else {}
    check("B 收到 A 的电量转发",
          forwarded.get("level") == 88 and forwarded.get("charging") is True,
          json.dumps(forwarded, ensure_ascii=False))
    check("转发帧带 from 字段标记来源",
          battery is not None and battery.get("from") == device_a_id)

    # ---------- 5. 定位上报与转发（含方向角） ----------
    now_ms = int(time.time() * 1000)
    device_a.send("location", {
        "lat": 39.9087, "lng": 116.3975, "accuracy": 12.5,
        "provider": "fused", "bearing": 123.5, "timestamp": now_ms,
    })
    location = device_b.wait_for("location")
    loc_payload = device_b.decrypt(location) if location else {}
    check("B 收到 A 的定位转发",
          abs(loc_payload.get("lat", 0) - 39.9087) < 1e-6,
          json.dumps(loc_payload, ensure_ascii=False))
    check("定位方向角(bearing)正确透传",
          abs(loc_payload.get("bearing", -1) - 123.5) < 1e-6,
          f"bearing={loc_payload.get('bearing')}")

    # 非法 bearing 应被归一成 -1（未知），而不是原样入库
    device_a.send("location", {
        "lat": 39.9087, "lng": 116.3975, "accuracy": 5.0,
        "provider": "fused", "bearing": 9999, "timestamp": now_ms + 1,
    })
    bad_bearing = device_b.wait_for("location")
    bad_payload = device_b.decrypt(bad_bearing) if bad_bearing else {}
    check("非法 bearing 被归一为 -1",
          bad_payload.get("bearing") == -1.0,
          f"bearing={bad_payload.get('bearing')}")

    # ---------- 6. 使用行为转发 ----------
    device_b.send("usage", {
        "package_name": "com.tencent.mm", "app_label": "微信",
        "event_type": "start", "timestamp": now_ms,
    })
    usage = device_a.wait_for("usage")
    usage_payload = device_a.decrypt(usage) if usage else {}
    check("A 收到 B 的使用记录（中文应用名不乱码）",
          usage_payload.get("app_label") == "微信",
          json.dumps(usage_payload, ensure_ascii=False))

    # ---------- 7. 历史查询 ----------
    device_b.send("history_request", {
        "target_device_id": device_a_id,
        "data_type": "location",
        "limit": 50,
    })
    history = device_b.wait_for("history_response")
    history_payload = device_b.decrypt(history) if history else {}
    records = history_payload.get("records", [])
    check("B 拉到 A 的历史定位", len(records) >= 1, f"{len(records)} 条")
    if records:
        check("历史记录按时间升序返回",
              records == sorted(records, key=lambda r: r["timestamp"]))

    # 越权：B 不能查别的设备
    device_b.send("history_request", {
        "target_device_id": "someOtherDevice",
        "data_type": "location",
        "limit": 10,
    })
    denied = device_b.wait_for("error")
    check("越权查询他人历史被拒绝", denied is not None)

    # 非法 data_type
    device_b.send("history_request", {
        "target_device_id": device_a_id,
        "data_type": "passwords",
        "limit": 10,
    })
    check("非法 data_type 被拒绝", device_b.wait_for("error") is not None)

    # ---------- 7.5 头像上传 / 转发 / 回捞 ----------
    # 用一段可校验的假 JPEG 数据，验证「字节完全一致」而不是只看长度
    avatar_bytes = bytes(range(256)) * 4
    avatar_b64 = base64.b64encode(avatar_bytes).decode("ascii")

    device_a.send("avatar", {
        "mime": "image/jpeg",
        "data": avatar_b64,
        "timestamp": now_ms + 10,
    })
    avatar_msg = device_b.wait_for("avatar")
    check("B 收到 A 的头像转发", avatar_msg is not None)
    if avatar_msg is not None:
        avatar_payload = device_b.decrypt(avatar_msg)
        try:
            decoded = base64.b64decode(avatar_payload.get("data", ""))
        except Exception:                      # noqa: BLE001
            decoded = b""
        check("头像内容逐字节一致（Base64 往返无损）",
              decoded == avatar_bytes,
              f"{len(decoded)}/{len(avatar_bytes)} 字节")
        check("头像帧带 from 字段", avatar_msg.get("from") == device_a_id)
    else:
        check("头像内容逐字节一致（Base64 往返无损）", False, "没收到头像帧")
        check("头像帧带 from 字段", False, "没收到头像帧")

    # 对方离线期间换的头像，靠 history_request 补回来
    device_b.send("history_request", {
        "target_device_id": device_a_id,
        "data_type": "avatar",
        "limit": 1,
    })
    avatar_history = device_b.wait_for("history_response")
    avatar_records = device_b.decrypt(avatar_history).get("records", []) if avatar_history else []
    check("能通过 history_request 拉回头像",
          len(avatar_records) == 1 and
          base64.b64decode(avatar_records[0].get("data", "")) == avatar_bytes,
          f"{len(avatar_records)} 条")

    # 再传一次应该覆盖而不是追加
    device_a.send("avatar", {
        "mime": "image/jpeg",
        "data": base64.b64encode(b"\xff" * 64).decode("ascii"),
        "timestamp": now_ms + 20,
    })
    device_b.wait_for("avatar")
    device_b.send("history_request", {
        "target_device_id": device_a_id,
        "data_type": "avatar",
        "limit": 1,
    })
    replaced_msg = device_b.wait_for("history_response")
    replaced = device_b.decrypt(replaced_msg).get("records", []) if replaced_msg else []
    check("重复上传头像会覆盖而不是累积",
          len(replaced) == 1 and base64.b64decode(replaced[0].get("data", "")) == b"\xff" * 64)

    # 非法 Base64 必须被拒绝
    device_a.send("avatar", {
        "mime": "image/jpeg",
        "data": "!!!这不是 base64!!!",
        "timestamp": now_ms + 30,
    })
    check("非法 Base64 头像被拒绝", device_a.wait_for("error") is not None)

    # 非法 mime 会被归一到 image/jpeg，而不是报错
    device_a.send("avatar", {
        "mime": "application/x-evil",
        "data": base64.b64encode(b"\x01\x02\x03\x04").decode("ascii"),
        "timestamp": now_ms + 40,
    })
    mime_msg = device_b.wait_for("avatar")
    check("非法 mime 被归一为 image/jpeg",
          mime_msg is not None and device_b.decrypt(mime_msg).get("mime") == "image/jpeg")

    # ---------- 8. 心跳 ----------
    device_a.send("ping")
    pong = device_a.wait_for("pong", timeout=3)
    check("ping -> pong", pong is not None)

    # ---------- 9. 未知类型 ----------
    device_a.send("hack_the_planet")
    check("未知消息类型返回 error", device_a.wait_for("error") is not None)

    # ---------- 10. 断线通知 ----------
    device_b.close()
    offline = device_a.wait_for("status", timeout=5)
    check("B 断开后 A 收到离线通知",
          offline is not None and offline.get("online") is False)

    device_a.close()

    # ---------- 汇总 ----------
    total = len(results)
    passed = sum(1 for r in results if r)
    print(f"\n{'=' * 46}")
    print(f"结果：{passed}/{total} 通过")
    print(f"{'=' * 46}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
