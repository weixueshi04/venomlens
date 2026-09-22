import json
import unittest
from unittest.mock import patch

import httpx

from inference.errors import ServiceError
from inference.provider import ENDPOINT, HhodataProvider
from inference.settings import ROOT


CATALOG = [{"speciesId": "test_corn", "commonName": "玉米蛇", "scientificName": "Pantherophis guttatus",
            "verificationStatus": "verified", "aliases": ["Corn Snake"]}]
COMPLETED = [1000, [{"box": [1, 2, 30, 40],
                     "list": [[97.6, "玉米蛇|Corn Snake|Pantherophis guttatus", 1, "test"]]}]]
TASK = "HH-B_example_1"


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        guard = patch("httpx.AsyncHTTPTransport.handle_async_request", side_effect=AssertionError("真实网络请求被测试阻止"))
        guard.start()
        self.addCleanup(guard.stop)
        self.requests = []

    def provider(self, payload=COMPLETED, status=200):
        def handle(request):
            self.requests.append(request)
            return httpx.Response(status, json=payload)
        return HhodataProvider("test-key-not-real", "R", CATALOG, httpx.MockTransport(handle))

    async def test_upload_uses_header_and_multipart(self):
        result = await self.provider().upload(b"test-image")
        request = self.requests[0]
        self.assertEqual(str(request.url), ENDPOINT)
        self.assertEqual(request.headers["api_key"], "test-key-not-real")
        self.assertNotIn("authorization", request.headers)
        self.assertIn("multipart/form-data", request.headers["content-type"])
        self.assertIn(b'name="upload"', request.content)
        self.assertIn(b'name="class"\r\n\r\nR\r\n', request.content)
        self.assertIn(b'filename="capture.jpg"', request.content)
        self.assertNotIn(b"test-key-not-real", request.content)
        self.assertEqual(result.status, "candidates")

    async def test_refresh_is_multipart_without_image(self):
        await self.provider().refresh(TASK)
        request = self.requests[0]
        self.assertIn("multipart/form-data", request.headers["content-type"])
        self.assertIn(b'name="resultid"', request.content)
        self.assertIn(TASK.encode(), request.content)
        self.assertNotIn(b'name="image"', request.content)
        self.assertEqual(len(self.requests), 1)

    async def test_pending_does_not_poll(self):
        result = await self.provider([1000, TASK]).upload(b"image")
        self.assertEqual((result.status, result.task_id), ("pending", TASK))
        self.assertEqual(len(self.requests), 1)

    async def test_processing_matches_existing_task(self):
        result = await self.provider([1001, TASK]).refresh(TASK)
        self.assertEqual(result.status, "pending")
        self.assertEqual(len(self.requests), 1)

    async def test_1001_download_failure_is_not_processing(self):
        with self.assertRaises(ServiceError) as raised:
            await self.provider([1001, "URL download failed"]).refresh(TASK)
        self.assertEqual(raised.exception.code, "UPSTREAM_ERROR")
        self.assertEqual(len(self.requests), 1)

    async def test_raw_score_is_not_a_probability(self):
        result = await self.provider().upload(b"image")
        self.assertIsNone(result.candidates[0]["score"])
        self.assertEqual(result.candidates[0]["providerScore"], 97.6)

    async def test_unknown_species_is_uncertain(self):
        response = [1000, [{"list": [[99, "未核验物种", 1, "test"]]}]]
        result = await self.provider(response).upload(b"image")
        self.assertEqual((result.status, result.candidates), ("uncertain", []))

    async def test_unverified_catalog_is_not_used_for_live_mapping(self):
        provider = HhodataProvider("test", "B", [{**CATALOG[0], "verificationStatus": "pending_review"}])
        result = provider.parse(COMPLETED)
        self.assertEqual((result.status, result.candidates), ("uncertain", []))

    async def test_no_animals_and_empty_results_are_not_safety_claims(self):
        for payload in ([1010, "No animals"], [1000, []]):
            with self.subTest(payload=payload):
                result = self.provider().parse(payload)
                self.assertEqual((result.status, result.candidates), ("uncertain", []))

    async def test_malformed_results_fail_closed(self):
        invalid = [{"status": "ok"}, [True, []], [1000], [1000, None], [1000, [{}]],
                   [1000, [{"list": [[True, "玉米蛇"]]}]],
                   [1000, [{"list": [[10 ** 400, "玉米蛇"]]}]],
                   [1000, [{"list": [[float("nan"), "玉米蛇"]]}]]]
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(ServiceError) as raised:
                self.provider().parse(payload)
            self.assertEqual(raised.exception.code, "INVALID_MODEL_OUTPUT")

    async def test_limit_errors_are_sanitized(self):
        for status, payload in ((503, [1007, "secret upstream message"]), (200, [1007, "secret upstream message"])):
            with self.subTest(status=status), self.assertRaises(ServiceError) as raised:
                await self.provider(payload, status).upload(b"image")
            self.assertEqual(raised.exception.status_code, 429)
            self.assertNotIn("secret", str(raised.exception))

    async def test_timeout_is_single_attempt(self):
        count = 0
        def handle(request):
            nonlocal count
            count += 1
            raise httpx.ReadTimeout("secret request details", request=request)
        provider = HhodataProvider("test", "B", CATALOG, httpx.MockTransport(handle))
        with self.assertRaises(ServiceError) as raised:
            await provider.upload(b"image")
        self.assertEqual((raised.exception.code, raised.exception.status_code), ("UPSTREAM_TIMEOUT", 504))
        self.assertNotIn("secret", str(raised.exception))
        self.assertEqual(count, 1)

    async def test_invalid_json_and_redirect_are_not_retried(self):
        for status in (200, 302):
            requests = []
            def handle(request):
                requests.append(request)
                return httpx.Response(status, text="not json", headers={"location": "https://example.invalid"})
            provider = HhodataProvider("test", "B", CATALOG, httpx.MockTransport(handle))
            with self.subTest(status=status), self.assertRaises(ServiceError):
                await provider.upload(b"image")
            self.assertEqual(len(requests), 1)

    async def test_verified_keelback_names_and_aliases_map_to_one_species(self):
        catalog = json.loads((ROOT / "data/species.json").read_text(encoding="utf-8"))
        provider = HhodataProvider("test-key", "R", catalog)
        for name in ("颈棱蛇", "Red Keelback", "Pseudagkistrodon rudis", "伪腹蛇",
                     "  PSEUDAGKISTRODON RUDIS  "):
            with self.subTest(name=name):
                result = provider.parse([1000, [{"list": [[97.6, name, 9316, "R"]]}]])
                self.assertEqual(result.status, "candidates")
                self.assertEqual(result.candidates, [{
                    "speciesId": "pseudagkistrodon_rudis", "commonName": "颈棱蛇",
                    "scientificName": "Pseudagkistrodon rudis", "score": None, "providerScore": 97.6,
                }])
        entry = next(row for row in catalog if row["speciesId"] == "pseudagkistrodon_rudis")
        self.assertEqual(entry["verificationStatus"], "verified")
        self.assertEqual(entry["riskStatus"], "unknown")
        self.assertIsNone(entry["referenceImage"])

    async def test_existing_project_species_remain_unverified(self):
        catalog = json.loads((ROOT / "data/species.json").read_text(encoding="utf-8"))
        provider = HhodataProvider("test-key", "R", catalog)
        for species_id in ("pantherophis_guttatus", "lampropeltis_californiae"):
            with self.subTest(species_id=species_id):
                entry = next(row for row in catalog if row["speciesId"] == species_id)
                self.assertEqual(entry["verificationStatus"], "pending_review")
                result = provider.parse([1000, [{"list": [[99, entry["scientificName"], 1, "R"]]}]])
                self.assertEqual((result.status, result.candidates), ("uncertain", []))


if __name__ == "__main__":
    unittest.main()
