# -*- coding: utf-8 -*-
"""
AES-GCM 加解密（与 Android 端 CryptoUtils.kt 逐字节对齐）

密钥派生：
    PBKDF2-HMAC-SHA256(pair_code.encode("utf-8"), salt=b"yuanzhiyue_salt",
                       iterations=10000, dklen=32)

密文布局（Base64 编码前）：
    IV[12] || CipherText || Tag[16]

Java 侧 `PBKDF2WithHmacSHA256` 把口令按 UTF-8 编码成字节，Python 侧同样用 UTF-8，
因此只要配对码是 ASCII，两端派生出的密钥完全一致。
"""

import base64
import json
import os
import threading

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

import config

# 密钥缓存：PBKDF2 一万次迭代大约 1~3 ms，高频上报时值得缓存
_key_cache = {}
_key_cache_lock = threading.Lock()


def derive_key(pair_code: str) -> bytes:
    """
    由配对码派生 32 字节 AES 密钥（带缓存）。

    :param pair_code: 用户输入的配对码
    :return: 32 字节密钥
    """
    cached = _key_cache.get(pair_code)
    if cached is not None:
        return cached

    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=config.KEY_LENGTH,
        salt=config.SALT,
        iterations=config.PBKDF2_ITERATIONS,
    )
    key = kdf.derive(pair_code.encode("utf-8"))

    with _key_cache_lock:
        # 简单防内存膨胀：缓存超过 256 个就清空（个人使用不可能到这么多）
        if len(_key_cache) > 256:
            _key_cache.clear()
        _key_cache[pair_code] = key
    return key


def encrypt_text(plain_text: str, key: bytes) -> str:
    """
    加密字符串，返回 Base64(IV + 密文 + Tag)。

    每次调用都生成新的随机 IV —— GCM 下 IV 复用是致命错误。
    """
    iv = os.urandom(config.GCM_IV_BYTES)
    aesgcm = AESGCM(key)
    cipher_text = aesgcm.encrypt(iv, plain_text.encode("utf-8"), None)
    return base64.b64encode(iv + cipher_text).decode("ascii")


def decrypt_text(payload_b64: str, key: bytes) -> str:
    """
    解密 Base64(IV + 密文 + Tag)。

    :raises ValueError: 解密失败（配对码不一致 / 数据被篡改 / Base64 损坏）
    """
    try:
        raw = base64.b64decode(payload_b64, validate=False)
    except Exception as exc:                     # noqa: BLE001
        raise ValueError("Base64 解码失败") from exc

    # 最短合法密文 = IV(12) + Tag(16) + 空明文(0)，少于这个长度一定有问题
    if len(raw) < config.GCM_IV_BYTES + config.GCM_TAG_BYTES:
        raise ValueError("密文长度不足")

    iv = raw[:config.GCM_IV_BYTES]
    cipher_text = raw[config.GCM_IV_BYTES:]

    try:
        aesgcm = AESGCM(key)
        return aesgcm.decrypt(iv, cipher_text, None).decode("utf-8")
    except Exception as exc:                     # noqa: BLE001
        raise ValueError("GCM 认证失败（配对码不一致或数据被篡改）") from exc


def encrypt_json(obj, key: bytes) -> str:
    """
    把 dict/list 序列化成 JSON 再加密。

    ensure_ascii=False：中文应用名（app_label）保持可读，
    两端都是 UTF-8，不会出问题。

    allow_nan=False：杜绝 Infinity / NaN 被写成裸字面量。
    那不是合法 JSON，Android 端 JSONObject 会直接解析失败并丢掉整帧。
    """
    text = json.dumps(obj, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
    return encrypt_text(text, key)


def decrypt_json(payload_b64: str, key: bytes) -> dict:
    """
    解密并解析成 dict。

    :raises ValueError: 解密失败或不是合法 JSON 对象
    """
    text = decrypt_text(payload_b64, key)
    try:
        result = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError("明文不是合法 JSON") from exc
    if not isinstance(result, dict):
        raise ValueError("明文不是 JSON 对象")
    return result


def warm_up(pair_code: str) -> bytes:
    """预热密钥缓存（设备认证时调用，避免同一轮多次 PBKDF2）"""
    return derive_key(pair_code)
