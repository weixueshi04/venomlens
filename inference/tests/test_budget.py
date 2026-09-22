import tempfile
import unittest
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from pathlib import Path
from threading import Barrier

from inference.budget import BudgetLedger, SavedResult
from inference.errors import ServiceError


def reserve_once(path, limit, recognition_id):
    try:
        return BudgetLedger(path, limit).find_or_reserve_upload(recognition_id)
    except ServiceError as error:
        return error.code


class BudgetLedgerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "nested" / "ledger.sqlite3"

    def assert_service_error(self, code, status, operation, *args):
        with self.assertRaises(ServiceError) as caught:
            operation(*args)
        self.assertEqual((caught.exception.code, caught.exception.status_code), (code, status))

    def test_limits_and_zero_budget(self):
        for limit in (-1, 51, 1.5, True, "1", None):
            with self.subTest(limit=limit), self.assertRaises(ValueError):
                BudgetLedger(self.path, limit)
        ledger = BudgetLedger(self.path, 0)
        self.assert_service_error("LOCAL_BUDGET_EXHAUSTED", 429,
                                  ledger.find_or_reserve_upload, "image")
        self.assertEqual(ledger.used(), 0)
        self.assertEqual(BudgetLedger(self.path, 50).used(), 0)

    def test_new_upload_costs_one_and_blocks_inflight(self):
        ledger = BudgetLedger(self.path, 1)
        self.assertIsNone(ledger.find_or_reserve_upload("image"))
        self.assertEqual(ledger.used(), 1)
        self.assert_service_error("OPERATION_IN_PROGRESS", 409,
                                  ledger.find_or_reserve_upload, "image")
        self.assert_service_error("LOCAL_BUDGET_EXHAUSTED", 429,
                                  ledger.find_or_reserve_upload, "other")
        self.assertEqual(ledger.used(), 1)

    def test_same_image_replays_result_at_exhausted_budget(self):
        ledger = BudgetLedger(self.path, 1)
        ledger.find_or_reserve_upload("image")
        expected = SavedResult(200, {"result": "测试", "items": [1]}, "task")
        ledger.finish("image", expected.status_code, expected.body, expected.task_id)
        replay = ledger.find_or_reserve_upload("image")
        self.assertEqual(replay, expected)
        replay.body["items"].append(2)
        self.assertEqual(ledger.find_or_reserve_upload("image"), expected)
        restarted = BudgetLedger(self.path, 0)
        self.assertEqual(restarted.reserve_refresh("image"), expected)
        self.assertEqual(restarted.used(), 1)

    def test_timeout_failure_is_final_without_refund_or_retry(self):
        ledger = BudgetLedger(self.path, 1)
        ledger.find_or_reserve_upload("image")
        ledger.finish("image", 504, {"error": "UPSTREAM_TIMEOUT"})
        restarted = BudgetLedger(self.path, 1)
        expected = SavedResult(504, {"error": "UPSTREAM_TIMEOUT"})
        self.assertEqual(restarted.find_or_reserve_upload("image"), expected)
        self.assertEqual(restarted.reserve_refresh("image"), expected)
        self.assertEqual(restarted.used(), 1)

    def test_pending_refresh_costs_each_call_but_completed_does_not(self):
        ledger = BudgetLedger(self.path, 3)
        ledger.find_or_reserve_upload("image")
        for used in (1, 2):
            ledger.finish("image", 202, {"status": "pending"}, "task")
            self.assertEqual(ledger.find_or_reserve_upload("image"),
                             SavedResult(202, {"status": "pending"}, "task"))
            self.assertEqual(ledger.used(), used)
            self.assertEqual(ledger.reserve_refresh("image"), "task")
            self.assert_service_error("OPERATION_IN_PROGRESS", 409,
                                      ledger.reserve_refresh, "image")
            self.assert_service_error("OPERATION_IN_PROGRESS", 409,
                                      ledger.find_or_reserve_upload, "image")
        ledger.finish("image", 200, {"status": "completed"})
        self.assertEqual(ledger.reserve_refresh("image"),
                         SavedResult(200, {"status": "completed"}))
        self.assertEqual(ledger.used(), 3)

    def test_exhausted_refresh_preserves_pending_result(self):
        ledger = BudgetLedger(self.path, 1)
        ledger.find_or_reserve_upload("image")
        ledger.finish("image", 202, {}, "task")
        self.assert_service_error("LOCAL_BUDGET_EXHAUSTED", 429,
                                  ledger.reserve_refresh, "image")
        self.assertEqual(ledger.find_or_reserve_upload("image"), SavedResult(202, {}, "task"))
        self.assertEqual(ledger.used(), 1)

    def test_unknown_refresh_and_finish_do_not_charge(self):
        ledger = BudgetLedger(self.path, 0)
        self.assert_service_error("UNKNOWN_RECOGNITION", 404, ledger.reserve_refresh, "missing")
        self.assert_service_error("UNKNOWN_RECOGNITION", 404, ledger.finish, "missing", 200, {})
        self.assertEqual(ledger.used(), 0)

    def test_invalid_pending_finish_keeps_reservation(self):
        ledger = BudgetLedger(self.path, 1)
        ledger.find_or_reserve_upload("image")
        for task_id in (None, "", 123):
            with self.assertRaises(ValueError):
                ledger.finish("image", 202, {}, task_id)
        self.assert_service_error("OPERATION_IN_PROGRESS", 409, ledger.reserve_refresh, "image")
        self.assertEqual(ledger.used(), 1)

    def test_crashed_upload_and_refresh_remain_inflight_after_restart(self):
        for refresh in (False, True):
            path = self.path.with_name(f"crash-{refresh}.sqlite3")
            ledger = BudgetLedger(path, 3)
            ledger.find_or_reserve_upload("image")
            if refresh:
                ledger.finish("image", 202, {}, "task")
                ledger.reserve_refresh("image")
            restarted = BudgetLedger(path, 3)
            for operation in (restarted.find_or_reserve_upload, restarted.reserve_refresh):
                self.assert_service_error("OPERATION_IN_PROGRESS", 409, operation, "image")
            self.assertEqual(restarted.used(), 2 if refresh else 1)

    def test_threads_same_image_and_different_images_respect_limit(self):
        for same in (True, False):
            path = self.path.with_name(f"threads-{same}.sqlite3")
            barrier = Barrier(8)
            def reserve(index):
                barrier.wait(timeout=30)
                return reserve_once(path, 3, "same" if same else str(index))
            with ThreadPoolExecutor(max_workers=8) as pool:
                results = list(pool.map(reserve, range(8)))
            expected_used = 1 if same else 3
            error = "OPERATION_IN_PROGRESS" if same else "LOCAL_BUDGET_EXHAUSTED"
            self.assertEqual(results.count(None), expected_used)
            self.assertEqual(results.count(error), 8 - expected_used)
            self.assertEqual(BudgetLedger(path, 3).used(), expected_used)

    def test_processes_share_atomic_limit(self):
        with ProcessPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(reserve_once, self.path, 3, str(i)) for i in range(12)]
            results = [future.result(timeout=60) for future in futures]
        self.assertEqual(results.count(None), 3)
        self.assertEqual(results.count("LOCAL_BUDGET_EXHAUSTED"), 9)
        self.assertEqual(BudgetLedger(self.path, 3).used(), 3)


if __name__ == "__main__":
    unittest.main()
