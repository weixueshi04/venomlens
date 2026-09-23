"""Conservative local call accounting, not a provider balance query."""

import json
import random
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from .errors import ServiceError


# 仅用于 schema 初始化的有界重试参数。
# Windows 的文件强制锁语义下，多个连接在同一瞬间对同一个 sqlite 文件做首次写入
# （建表 + 建 hot journal）时，竞争失败的一方拿到的是 SQLITE_READONLY 而不是
# SQLITE_BUSY；_connection() 里的 timeout=30 只对 BUSY 生效，不会替我们重试
# readonly，于是直接抛出。CREATE TABLE IF NOT EXISTS 幂等，重试零副作用。
# 上限刻意设小：真正的只读故障（如目录无写权限）仍会在约 0.15s 后如实抛出，
# 不会被重试悄悄掩盖。
_SCHEMA_ATTEMPTS = 5
_SCHEMA_BACKOFF_SECONDS = 0.01
# sqlite 在这类竞态下报出的措辞；其余 OperationalError（语法错、无此表等）必须原样抛出。
_TRANSIENT_SCHEMA_MARKERS = ("readonly", "locked")


def _transient_schema_error(error: sqlite3.OperationalError) -> bool:
    """判断建表失败是否属于可安全重试的并发竞态。

    只认 readonly / locked 两类措辞：它们在 Windows 并发首写下是瞬态的，重试即可过去。
    其余 OperationalError（SQL 语法错误、无此表、磁盘损坏等）是确定性故障，
    重试只会拖慢失败、掩盖真因，必须原样抛出。大小写不敏感，因为 sqlite 不同版本
    的措辞大小写并不统一。
    """
    message = str(error).lower()
    return any(marker in message for marker in _TRANSIENT_SCHEMA_MARKERS)


@dataclass
class SavedResult:
    status_code: int
    body: dict
    task_id: str | None = None


class BudgetLedger:
    def __init__(self, path: Path, limit: int):
        if type(limit) is not int or not 0 <= limit <= 50:
            raise ValueError("本地调用限额必须是 0 到 50 的整数")
        self.path = path
        self.limit = limit
        path.parent.mkdir(parents=True, exist_ok=True)
        self._create_schema()

    def _create_schema(self) -> None:
        """建表；全类唯一允许重试的地方。

        红线：只有这两条幂等 DDL 走重试。_reserve / finish / reserve_refresh / used
        靠 BEGIN IMMEDIATE + timeout=30 正确串行化，给它们加重试会重复计入 calls
        行、突破本地限额，绝不能动。
        """
        for attempt in range(1, _SCHEMA_ATTEMPTS + 1):
            try:
                # 整个 with 块一起重试：连接在异常后已被 _connection() 关闭，
                # 且 BEGIN IMMEDIATE 本身也可能是失败点，只重试 execute 无效。
                with self._connection() as db:
                    db.execute("""CREATE TABLE IF NOT EXISTS recognitions (
                        recognition_id TEXT PRIMARY KEY,
                        inflight INTEGER NOT NULL,
                        status_code INTEGER,
                        body TEXT,
                        task_id TEXT
                    )""")
                    db.execute("""CREATE TABLE IF NOT EXISTS calls (
                        id INTEGER PRIMARY KEY,
                        recognition_id TEXT NOT NULL
                    )""")
                return
            except sqlite3.OperationalError as error:
                if attempt == _SCHEMA_ATTEMPTS or not _transient_schema_error(error):
                    raise
                # 退避加抖动：并发方是同一瞬间撞在一起的（测试用 Barrier 同步），
                # 固定退避会让它们再次同一瞬间重试、反复相撞；抖动打散重试时刻。
                time.sleep(_SCHEMA_BACKOFF_SECONDS * attempt * random.uniform(0.5, 1.5))

    @contextmanager
    def _connection(self, write: bool = True):
        db = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            with db:
                db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
                yield db
        finally:
            db.close()

    @staticmethod
    def _unknown():
        return ServiceError("UNKNOWN_RECOGNITION", "未找到识别任务", 404)

    def find_or_reserve_upload(self, recognition_id: str) -> SavedResult | None:
        return self._reserve(recognition_id, refresh=False)

    def reserve_refresh(self, recognition_id: str) -> SavedResult | str:
        return self._reserve(recognition_id, refresh=True)

    def _reserve(self, recognition_id: str, refresh: bool):
        with self._connection() as db:
            row = db.execute(
                "SELECT * FROM recognitions WHERE recognition_id = ?",
                (recognition_id,),
            ).fetchone()
            if row is not None:
                if row["inflight"]:
                    raise ServiceError(
                        "OPERATION_IN_PROGRESS", "操作正在处理中，请勿重复提交", 409
                    )
                if not refresh or row["status_code"] != 202 or not row["task_id"]:
                    return SavedResult(
                        row["status_code"], json.loads(row["body"]), row["task_id"]
                    )
            elif refresh:
                raise self._unknown()
            if db.execute("SELECT COUNT(*) FROM calls").fetchone()[0] >= self.limit:
                raise ServiceError(
                    "LOCAL_BUDGET_EXHAUSTED", "本地调用额度已用尽", 429
                )
            db.execute("INSERT INTO calls (recognition_id) VALUES (?)", (recognition_id,))
            if row is None:
                db.execute(
                    "INSERT INTO recognitions (recognition_id, inflight) VALUES (?, 1)",
                    (recognition_id,),
                )
                return None
            db.execute(
                "UPDATE recognitions SET inflight = 1 WHERE recognition_id = ?",
                (recognition_id,),
            )
            return row["task_id"]

    def finish(self, recognition_id: str, status_code: int, body: dict,
               task_id: str | None = None):
        if status_code == 202 and (not isinstance(task_id, str) or not task_id):
            raise ValueError("待处理结果必须包含任务标识")
        if not isinstance(body, dict):
            raise ValueError("业务响应必须是字典")
        payload = json.dumps(body, ensure_ascii=False, allow_nan=False)
        with self._connection() as db:
            result = db.execute(
                """UPDATE recognitions
                   SET inflight = 0, status_code = ?, body = ?, task_id = ?
                   WHERE recognition_id = ?""",
                (status_code, payload, task_id, recognition_id),
            )
            if result.rowcount == 0:
                raise self._unknown()

    def used(self) -> int:
        with self._connection(write=False) as db:
            return db.execute("SELECT COUNT(*) FROM calls").fetchone()[0]
