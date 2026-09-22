"""Conservative local call accounting, not a provider balance query."""

import json
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from .errors import ServiceError


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
