# -*- coding: utf-8 -*-
"""
SQLite 存储层。

设计要点：
  1. **单连接 + 互斥锁**：个人服务端并发量极小，用一把锁比连接池简单可靠得多；
     所有写操作都在锁内完成，避免 "database is locked"。
  2. **WAL 模式**：读写不互相阻塞，查询历史的同时还能写入。
  3. **开关日志/同步策略**：`synchronous=NORMAL` 在 WAL 下已经足够安全，且写入快很多
     （Orange Pi 的 eMMC/SD 卡写入很慢，这个参数很关键）。
  4. **不存配对码明文**：配对码就是 AES 密钥的种子，数据库被拖走等于密钥泄露。
     因此只存 `sha256(pair_code)`，用它做“群组 ID”效果完全相同。
"""

import base64
import hashlib
import logging
import os
import sqlite3
import threading
import time
from typing import Any, Dict, List, Optional

import config

logger = logging.getLogger("baobao.db")


def hash_pair_code(pair_code: str) -> str:
    """
    配对码 -> 群组标识。

    服务端只用它判断“这两台设备是不是一对”，不需要也不可能反推出配对码。
    """
    return hashlib.sha256(pair_code.encode("utf-8")).hexdigest()


class Database:
    """SQLite 封装。所有 public 方法都是线程安全的。"""

    def __init__(self, path: Optional[str] = None):
        # 注意：默认值不能写成 `path=config.DB_PATH`，
        # 那样会在函数定义时求值，命令行 --db 覆盖 config.DB_PATH 之后就不生效了。
        self.path = path or config.DB_PATH
        self._lock = threading.RLock()

        # 确保目录存在
        directory = os.path.dirname(os.path.abspath(self.path))
        if directory:
            os.makedirs(directory, exist_ok=True)

        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._configure()
        self._create_tables()

    # ------------------------------------------------------------------
    # 初始化
    # ------------------------------------------------------------------

    def _configure(self) -> None:
        cursor = self._conn.cursor()
        # incremental_vacuum 必须先开 auto_vacuum=INCREMENTAL 才有意义：
        # 默认 auto_vacuum=0 时那条 PRAGMA 是个静默空操作，删除的数据页永远留在 freelist 里。
        # 注意：auto_vacuum 只对「新建的库」立即生效，已有库需要手工 VACUUM 一次。
        cursor.execute("PRAGMA auto_vacuum=INCREMENTAL")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA synchronous=NORMAL")
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA busy_timeout=5000")
        self._conn.commit()

    def _create_tables(self) -> None:
        with self._lock:
            cursor = self._conn.cursor()

            # ---------------- 设备表 ----------------
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS devices (
                    device_id       TEXT PRIMARY KEY,
                    pair_code_hash  TEXT NOT NULL,      -- sha256(pair_code)，仅作群组标识
                    partner_id      TEXT,               -- 对方设备 ID（配对后双向写入）
                    app_version     TEXT,
                    model           TEXT,
                    first_seen      INTEGER NOT NULL,
                    last_seen       INTEGER NOT NULL
                )
                """
            )
            cursor.execute(
                "CREATE INDEX IF NOT EXISTS idx_devices_pair ON devices(pair_code_hash)"
            )

            # ---------------- 电量 ----------------
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS battery_reports (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id   TEXT NOT NULL,
                    level       INTEGER NOT NULL,
                    charging    INTEGER NOT NULL,
                    timestamp   INTEGER NOT NULL
                )
                """
            )
            cursor.execute(
                "CREATE INDEX IF NOT EXISTS idx_battery_dev_ts "
                "ON battery_reports(device_id, timestamp)"
            )

            # ---------------- 定位 ----------------
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS location_reports (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id   TEXT NOT NULL,
                    lat         REAL NOT NULL,
                    lng         REAL NOT NULL,
                    accuracy    REAL,
                    provider    TEXT,
                    bearing     REAL,
                    timestamp   INTEGER NOT NULL
                )
                """
            )
            cursor.execute(
                "CREATE INDEX IF NOT EXISTS idx_location_dev_ts "
                "ON location_reports(device_id, timestamp)"
            )

            # ---------------- 使用行为 ----------------
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS usage_reports (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    device_id    TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    app_label    TEXT,
                    event_type   TEXT NOT NULL,
                    timestamp    INTEGER NOT NULL
                )
                """
            )
            cursor.execute(
                "CREATE INDEX IF NOT EXISTS idx_usage_dev_ts "
                "ON usage_reports(device_id, timestamp)"
            )

            # ---------------- 头像 ----------------
            # 只保留每人最新一张，不做历史。data 存 JPEG/PNG 原始字节。
            # 限制在 256x256，一帧 base64 后约 30KB，远小于 1MiB 的帧上限。
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS avatars (
                    device_id   TEXT PRIMARY KEY,
                    mime        TEXT NOT NULL,
                    data        BLOB NOT NULL,
                    updated_at  INTEGER NOT NULL
                )
                """
            )

            self._conn.commit()

        self._migrate()
        logger.info("数据库就绪：%s", self.path)

    def _migrate(self) -> None:
        """
        轻量迁移：给老版本建的库补上后来新增的列。

        SQLite 的 CREATE TABLE IF NOT EXISTS 不会改动已存在的表结构，
        所以升级服务端时必须显式 ALTER，否则老库一查询就报 "no such column"。
        """
        with self._lock:
            cursor = self._conn.cursor()
            cursor.execute("PRAGMA table_info(location_reports)")
            columns = {row["name"] for row in cursor.fetchall()}

            if "bearing" not in columns:
                cursor.execute("ALTER TABLE location_reports ADD COLUMN bearing REAL")
                self._conn.commit()
                logger.info("已为老数据库补上 location_reports.bearing 列")

    # ------------------------------------------------------------------
    # 设备 / 配对
    # ------------------------------------------------------------------

    def register_device(
        self,
        device_id: str,
        pair_code: str,
        app_version: str = "",
        model: str = "",
    ) -> Optional[str]:
        """
        注册或更新设备，并返回其伙伴设备 ID。

        配对规则：同一个 `pair_code_hash` 下的设备按「最近活跃」排序，取前两台互为伙伴。
        这样处理是为了应对「重装 APP / 换手机」：新设备一连上来就会成为活跃伙伴，
        而不是出现 A 指向新机、新机指向 A、旧机还指向 A 这种三方错乱。

        :return: 对方设备 ID；如果群组里只有自己一台，返回 None
        """
        now = int(time.time() * 1000)
        pair_hash = hash_pair_code(pair_code)

        with self._lock:
            cursor = self._conn.cursor()

            # 1) 写入/更新自己
            cursor.execute(
                """
                INSERT INTO devices (device_id, pair_code_hash, app_version, model,
                                     first_seen, last_seen)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    pair_code_hash = excluded.pair_code_hash,
                    app_version    = excluded.app_version,
                    model          = excluded.model,
                    last_seen      = excluded.last_seen
                """,
                (device_id, pair_hash, app_version, model, now, now),
            )

            # 2) 取同群组内最活跃的两台设备
            cursor.execute(
                """
                SELECT device_id FROM devices
                WHERE pair_code_hash = ?
                ORDER BY last_seen DESC, first_seen DESC
                LIMIT 2
                """,
                (pair_hash,),
            )
            active = [row["device_id"] for row in cursor.fetchall()]

            if len(active) == 2:
                first, second = active
                # 3) 两台互为伙伴
                cursor.execute(
                    "UPDATE devices SET partner_id = ? WHERE device_id = ?",
                    (second, first),
                )
                cursor.execute(
                    "UPDATE devices SET partner_id = ? WHERE device_id = ?",
                    (first, second),
                )
                # 4) 群组里更早的历史设备（例如重装前的旧安装）清空关系，
                #    否则它们会一直指向一个已经不在用的旧伙伴
                cursor.execute(
                    """
                    UPDATE devices SET partner_id = NULL
                    WHERE pair_code_hash = ? AND device_id NOT IN (?, ?)
                    """,
                    (pair_hash, first, second),
                )
                partner_id = second if first == device_id else first
            else:
                cursor.execute(
                    "UPDATE devices SET partner_id = NULL WHERE device_id = ?",
                    (device_id,),
                )
                partner_id = None

            self._conn.commit()

        logger.info(
            "设备注册 device=%s partner=%s", device_id[:8], (partner_id or "无")[:8]
        )
        return partner_id

    def get_partner_id(self, device_id: str) -> Optional[str]:
        """查询某设备的伙伴 ID（内存里查不到时用数据库兜底）"""
        with self._lock:
            cursor = self._conn.cursor()
            cursor.execute(
                "SELECT partner_id FROM devices WHERE device_id = ?", (device_id,)
            )
            row = cursor.fetchone()
        if row is None:
            return None
        return row["partner_id"]

    def get_device(self, device_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            cursor = self._conn.cursor()
            cursor.execute(
                "SELECT * FROM devices WHERE device_id = ?", (device_id,)
            )
            row = cursor.fetchone()
        return dict(row) if row else None

    def touch_device(self, device_id: str) -> None:
        """更新 last_seen（每次认证时调用）"""
        with self._lock:
            self._conn.execute(
                "UPDATE devices SET last_seen = ? WHERE device_id = ?",
                (int(time.time() * 1000), device_id),
            )
            self._conn.commit()

    # ------------------------------------------------------------------
    # 写入上报
    # ------------------------------------------------------------------

    def save_battery(self, device_id: str, level: int, charging: bool, timestamp: int) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO battery_reports (device_id, level, charging, timestamp) "
                "VALUES (?, ?, ?, ?)",
                (device_id, int(level), 1 if charging else 0, int(timestamp)),
            )
            self._conn.commit()

    def save_location(
        self,
        device_id: str,
        lat: float,
        lng: float,
        accuracy: float,
        provider: str,
        timestamp: int,
        bearing: float = -1.0,
    ) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO location_reports "
                "(device_id, lat, lng, accuracy, provider, bearing, timestamp) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (device_id, float(lat), float(lng), float(accuracy or 0),
                 provider or "", float(bearing), int(timestamp)),
            )
            self._conn.commit()

    # ------------------------------------------------------------------
    # 头像
    # ------------------------------------------------------------------

    def save_avatar(self, device_id: str, mime: str, data: bytes, updated_at: int) -> None:
        """保存（覆盖）某设备的头像。每人只留最新一张。"""
        with self._lock:
            self._conn.execute(
                """
                INSERT INTO avatars (device_id, mime, data, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    mime       = excluded.mime,
                    data       = excluded.data,
                    updated_at = excluded.updated_at
                """,
                (device_id, mime or "image/jpeg", sqlite3.Binary(data), int(updated_at)),
            )
            self._conn.commit()
        logger.info("保存头像 device=%s %d 字节", device_id[:8], len(data))

    def query_avatar(self, device_id: str) -> List[Dict[str, Any]]:
        """
        查询某设备的头像。

        :return: 0 或 1 条记录，字段 mime / data(base64) / timestamp
                 —— 复用 history_response 的 records 结构，所以包成 list
        """
        with self._lock:
            cursor = self._conn.cursor()
            cursor.execute(
                "SELECT mime, data, updated_at FROM avatars WHERE device_id = ?",
                (device_id,),
            )
            row = cursor.fetchone()

        if row is None:
            return []

        # BLOB 不能直接进 JSON，转成 base64 字符串
        encoded = base64.b64encode(row["data"]).decode("ascii")
        return [{
            "mime": row["mime"],
            "data": encoded,
            "timestamp": row["updated_at"],
        }]

    def get_avatar_updated_at(self, device_id: str) -> int:
        """头像的最后更新时间；没有头像返回 0"""
        with self._lock:
            cursor = self._conn.cursor()
            cursor.execute(
                "SELECT updated_at FROM avatars WHERE device_id = ?", (device_id,)
            )
            row = cursor.fetchone()
        return row["updated_at"] if row else 0

    def save_usage(
        self,
        device_id: str,
        package_name: str,
        app_label: str,
        event_type: str,
        timestamp: int,
    ) -> None:
        with self._lock:
            self._conn.execute(
                "INSERT INTO usage_reports "
                "(device_id, package_name, app_label, event_type, timestamp) "
                "VALUES (?, ?, ?, ?, ?)",
                (device_id, package_name, app_label or "", event_type, int(timestamp)),
            )
            self._conn.commit()

    # ------------------------------------------------------------------
    # 历史查询（返回按 timestamp 升序，与协议约定一致）
    # ------------------------------------------------------------------

    def query_battery(self, device_id: str, limit: int) -> List[Dict[str, Any]]:
        rows = self._query_recent(
            "SELECT level, charging, timestamp FROM battery_reports "
            "WHERE device_id = ? ORDER BY timestamp DESC LIMIT ?",
            device_id,
            limit,
        )
        # SQLite 没有布尔类型，存进去是 0/1。实时转发路径发的是真正的 true/false，
        # 历史路径必须转成同一类型，否则 Android 端 JSONObject.optBoolean 拿不到值。
        for row in rows:
            row["charging"] = bool(row["charging"])
        return rows

    def query_location(self, device_id: str, limit: int) -> List[Dict[str, Any]]:
        return self._query_recent(
            "SELECT lat, lng, accuracy, provider, bearing, timestamp "
            "FROM location_reports "
            "WHERE device_id = ? ORDER BY timestamp DESC LIMIT ?",
            device_id,
            limit,
        )

    def query_usage(self, device_id: str, limit: int) -> List[Dict[str, Any]]:
        return self._query_recent(
            "SELECT package_name, app_label, event_type, timestamp FROM usage_reports "
            "WHERE device_id = ? ORDER BY timestamp DESC LIMIT ?",
            device_id,
            limit,
        )

    def _query_recent(self, sql: str, device_id: str, limit: int) -> List[Dict[str, Any]]:
        capped = max(1, min(int(limit), config.HISTORY_MAX_LIMIT))
        with self._lock:
            cursor = self._conn.cursor()
            cursor.execute(sql, (device_id, capped))
            rows = [dict(row) for row in cursor.fetchall()]
        # 查询时按时间倒序取“最近 N 条”，返回前翻正为升序
        rows.reverse()
        return rows

    # ------------------------------------------------------------------
    # 维护
    # ------------------------------------------------------------------

    def cleanup_old_records(self) -> int:
        """
        删除超过保留期的历史数据。

        :return: 删除的总行数
        """
        if config.HISTORY_RETENTION_DAYS <= 0:
            return 0

        cutoff = int((time.time() - config.HISTORY_RETENTION_DAYS * 86400) * 1000)
        deleted = 0

        with self._lock:
            cursor = self._conn.cursor()
            for table in ("battery_reports", "location_reports", "usage_reports"):
                cursor.execute(f"DELETE FROM {table} WHERE timestamp < ?", (cutoff,))
                deleted += cursor.rowcount or 0
            self._conn.commit()
            # 回收空间（WAL 模式下不会阻塞读写）
            cursor.execute("PRAGMA incremental_vacuum")

        if deleted:
            logger.info("清理过期数据 %d 行", deleted)
        return deleted

    def stats(self) -> Dict[str, int]:
        """各表行数，方便 `python server.py --stats` 快速看一眼"""
        result: Dict[str, int] = {}
        with self._lock:
            cursor = self._conn.cursor()
            for table in ("devices", "battery_reports", "location_reports", "usage_reports"):
                cursor.execute(f"SELECT COUNT(*) AS c FROM {table}")
                result[table] = cursor.fetchone()["c"]
        return result

    def close(self) -> None:
        with self._lock:
            try:
                self._conn.commit()
            except Exception:                    # noqa: BLE001
                pass
            self._conn.close()
