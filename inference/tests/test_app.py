import json
from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient
from PIL import Image

from inference.app import SCENARIOS, create_app
from inference.errors import ServiceError
from inference.images import MAX_IMAGE_BYTES, prepare_image
from inference.models import RecognitionResult
from inference.provider import HhodataProvider, ProviderResult
from inference.settings import ROOT, Settings


TOKEN = "test-proxy-token-not-real"


def jpeg(color="green", size=(32, 32), exif=None):
    output = BytesIO()
    options = {"exif": exif} if exif else {}
    Image.new("RGB", size, color).save(output, format="JPEG", **options)
    return output.getvalue()


class FakeProvider:
    def __init__(self, results):
        self.results = iter(results)
        self.calls = []

    async def upload(self, image):
        self.calls.append(("upload", image))
        result = next(self.results)
        if isinstance(result, ServiceError):
            raise result
        return result

    async def refresh(self, task):
        self.calls.append(("refresh", task))
        result = next(self.results)
        if isinstance(result, ServiceError):
            raise result
        return result


class AppTests(unittest.TestCase):
    def setUp(self):
        guard = patch("httpx.AsyncHTTPTransport.handle_async_request", side_effect=AssertionError("真实网络请求被测试阻止"))
        guard.start()
        self.addCleanup(guard.stop)
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "calls.sqlite3"
        self.headers = {"Authorization": f"Bearer {TOKEN}"}

    def live_client(self, provider, limit=1):
        settings = Settings(mode="hhodata", api_key="test-key", proxy_token=TOKEN,
                            live_call_limit=limit, ledger_path=self.path)
        client = TestClient(create_app(settings, provider))
        self.addCleanup(client.close)
        return client

    def upload(self, client, request_id="test-1", image=None, consent="true", headers=None):
        return client.post("/v1/recognitions", data={"requestId": request_id, "uploadConsent": consent},
                           files={"image": ("capture.jpg", jpeg() if image is None else image, "image/jpeg")},
                           headers=self.headers if headers is None else headers)

    def test_default_mode_has_no_live_calls(self):
        provider = FakeProvider([])
        with TestClient(create_app(Settings(), provider)) as client:
            response = self.upload(client)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["resultSource"], "mock")
            self.assertEqual(client.get("/healthz").json()["localCallsUsed"], 0)
        self.assertEqual(provider.calls, [])

    def test_all_fixtures_follow_contract(self):
        catalog_ids = {row["speciesId"] for row in json.loads((ROOT / "data/species.json").read_text(encoding="utf-8"))}
        with TestClient(create_app(Settings())) as client:
            for scenario, status in SCENARIOS.items():
                with self.subTest(scenario=scenario):
                    response = self.upload(client, headers={"X-Mock-Scenario": scenario})
                    self.assertEqual(response.status_code, status)
                    self.assertEqual(response.json()["resultSource"], "mock")
                    if status < 400:
                        result = RecognitionResult.model_validate(response.json())
                        self.assertTrue(all(row.speciesId in catalog_ids for row in result.candidates))

    def test_duplicate_image_is_cached_across_request_ids(self):
        provider = FakeProvider([ProviderResult("uncertain")])
        client = self.live_client(provider)
        first = self.upload(client)
        second = self.upload(client, request_id="test-2")
        self.assertEqual(first.status_code, 200)
        self.assertEqual(second.json()["resultSource"], "cache")
        self.assertEqual(second.json()["requestId"], "test-2")
        self.assertEqual(len(provider.calls), 1)
        self.assertEqual(client.get("/healthz").json()["localCallsUsed"], 1)

    def test_restarting_app_does_not_reset_budget(self):
        provider = FakeProvider([ProviderResult("uncertain")])
        self.upload(self.live_client(provider))
        restarted = self.live_client(FakeProvider([]))
        self.assertEqual(self.upload(restarted).json()["resultSource"], "cache")
        blocked = self.upload(restarted, image=jpeg("red"))
        self.assertEqual(blocked.json()["error"]["code"], "LOCAL_BUDGET_EXHAUSTED")

    def test_zero_budget_blocks_before_provider(self):
        provider = FakeProvider([])
        client = self.live_client(provider, limit=0)
        response = self.upload(client)
        self.assertEqual(response.status_code, 429)
        self.assertEqual(provider.calls, [])

    def test_upload_consent_is_required_before_budget(self):
        provider = FakeProvider([])
        client = self.live_client(provider)
        response = self.upload(client, consent="false")
        self.assertEqual(response.status_code, 403)
        self.assertEqual(client.get("/healthz").json()["localCallsUsed"], 0)
        self.assertEqual(provider.calls, [])

    def test_unauthorized_and_mock_override_do_not_call_provider(self):
        provider = FakeProvider([])
        client = self.live_client(provider)
        self.assertEqual(self.upload(client, headers={}).status_code, 401)
        response = self.upload(client, headers={**self.headers, "X-Mock-Scenario": "candidates"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(provider.calls, [])

    def test_failed_upload_is_cached_not_retried(self):
        provider = FakeProvider([ServiceError("UPSTREAM_TIMEOUT", "模拟超时", 504)])
        client = self.live_client(provider)
        self.assertEqual(self.upload(client).status_code, 504)
        second = self.upload(client)
        self.assertEqual(second.status_code, 504)
        self.assertEqual(second.json()["resultSource"], "cache")
        self.assertEqual(len(provider.calls), 1)

    def test_pending_refresh_requires_explicit_call_and_budget(self):
        provider = FakeProvider([ProviderResult("pending", task_id="provider-task"), ProviderResult("uncertain")])
        client = self.live_client(provider, limit=2)
        first = self.upload(client)
        self.assertEqual(first.status_code, 202)
        recognition_id = first.json()["recognitionId"]
        self.assertNotIn("provider-task", first.text)
        self.assertEqual(self.upload(client).status_code, 202)
        self.assertEqual(len(provider.calls), 1)
        path = f"/v1/recognitions/{recognition_id}/refresh"
        result = client.post(path, json={"requestId": "query-1"}, headers=self.headers)
        self.assertEqual(result.status_code, 200)
        cached = client.post(path, json={"requestId": "query-2"}, headers=self.headers)
        self.assertEqual(cached.json()["resultSource"], "cache")
        self.assertEqual(len(provider.calls), 2)
        self.assertEqual(client.get("/healthz").json()["localCallsUsed"], 2)

    def test_refresh_stops_at_budget_limit(self):
        provider = FakeProvider([ProviderResult("pending", task_id="provider-task")])
        client = self.live_client(provider)
        recognition_id = self.upload(client).json()["recognitionId"]
        response = client.post(f"/v1/recognitions/{recognition_id}/refresh",
                               json={"requestId": "query"}, headers=self.headers)
        self.assertEqual(response.status_code, 429)
        self.assertEqual(len(provider.calls), 1)

    def test_unknown_task_and_invalid_mock_scenario(self):
        client = self.live_client(FakeProvider([]))
        response = client.post(f"/v1/recognitions/{'0' * 64}/refresh",
                               json={"requestId": "query"}, headers=self.headers)
        self.assertEqual(response.status_code, 404)
        with TestClient(create_app(Settings())) as mock:
            response = self.upload(mock, headers={"X-Mock-Scenario": "../../private"})
            self.assertEqual(response.status_code, 400)

    def test_bad_images_are_rejected_without_spending(self):
        provider = FakeProvider([])
        client = self.live_client(provider)
        for image in (b"not an image", jpeg(size=(10, 32)), b"x" * (MAX_IMAGE_BYTES + 1)):
            with self.subTest(size=len(image)):
                self.assertIn(self.upload(client, image=image).status_code, {400, 413})
        self.assertEqual(provider.calls, [])
        self.assertEqual(client.get("/healthz").json()["localCallsUsed"], 0)

    def test_oversize_request_body_is_bounded(self):
        client = self.live_client(FakeProvider([]))
        response = client.post("/v1/recognitions", content=b"x" * (MAX_IMAGE_BYTES + 70_000),
                               headers={**self.headers, "content-type": "multipart/form-data; boundary=test"})
        self.assertEqual(response.status_code, 413)

    def test_image_metadata_is_removed(self):
        exif = Image.Exif()
        exif[270] = "private test metadata"
        exif[274] = 6
        cleaned = prepare_image(jpeg(size=(32, 48), exif=exif))
        self.assertNotIn(b"private test metadata", cleaned)
        with Image.open(BytesIO(cleaned)) as image:
            self.assertFalse(image.getexif())
            self.assertEqual(image.size, (48, 32))

    def test_valid_upload_stays_in_memory(self):
        with TestClient(create_app(Settings())) as client, patch("tempfile.SpooledTemporaryFile.rollover", side_effect=AssertionError("图片不得落盘")):
            response = self.upload(client, image=jpeg() + b"x" * 1_100_000)
            self.assertEqual(response.status_code, 200)

    def test_live_configuration_is_explicit_and_secrets_not_in_repr(self):
        with self.assertRaises(ValueError):
            Settings(mode="hhodata")
        with self.assertRaises(ValueError):
            Settings(live_call_limit=51)
        settings = Settings(api_key="hidden-key", proxy_token="hidden-token")
        self.assertNotIn("hidden", repr(settings))

    def test_reptile_defaults_keep_live_calls_disabled(self):
        with patch.dict("os.environ", {}, clear=True):
            for settings in (Settings(), Settings.from_env()):
                with self.subTest(settings=settings):
                    self.assertEqual(settings.animal_class, "R")
                    self.assertEqual(settings.mode, "mock")
                    self.assertEqual(settings.live_call_limit, 0)

    def test_category_environment_is_respected_and_validated(self):
        environment = {"RECOGNITION_MODE": "hhodata", "HHODATA_API_KEY": "test-key",
                       "PROXY_TOKEN": TOKEN, "LIVE_CALL_LIMIT": "0"}
        for category in ("R", "BM", "", "S", "reptile"):
            with self.subTest(category=category), patch.dict(
                "os.environ", {**environment, "HHODATA_CLASS": category}, clear=True
            ):
                if category in {"R", "BM"}:
                    settings = Settings.from_env()
                    self.assertEqual(settings.animal_class, category)
                    self.assertEqual(settings.live_call_limit, 0)
                else:
                    with self.assertRaises(ValueError):
                        Settings.from_env()

    def test_jpeg_comments_are_removed_before_hashing(self):
        original = jpeg()
        def commented(comment):
            return original[:2] + b"\xff\xfe" + (len(comment) + 2).to_bytes(2, "big") + comment + original[2:]
        first = commented(b"private-location-one")
        second = commented(b"private-location-two")
        self.assertEqual(prepare_image(first), prepare_image(second))
        self.assertNotIn(b"private-location", prepare_image(first))
        provider = FakeProvider([ProviderResult("uncertain")])
        client = self.live_client(provider)
        self.assertEqual(self.upload(client, image=first).status_code, 200)
        self.assertEqual(self.upload(client, image=second).json()["resultSource"], "cache")
        self.assertEqual(len(provider.calls), 1)

    def test_malformed_multipart_is_a_client_error(self):
        provider = FakeProvider([])
        client = self.live_client(provider)
        response = client.post("/v1/recognitions", content=b"--wrong-boundary\r\n",
                               headers={**self.headers, "content-type": "multipart/form-data; boundary=expected"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["error"]["code"], "INVALID_REQUEST")
        self.assertEqual(provider.calls, [])

    def test_huge_provider_score_is_cached_as_failure(self):
        requests = []
        def handle(request):
            requests.append(request)
            return httpx.Response(200, json=[1000, [{"list": [[10 ** 400, "玉米蛇"]]}]])
        provider = HhodataProvider("test", "B", [], httpx.MockTransport(handle))
        client = self.live_client(provider)
        first = self.upload(client)
        self.assertEqual(first.status_code, 502)
        self.assertEqual(first.json()["error"]["code"], "INVALID_MODEL_OUTPUT")
        second = self.upload(client)
        self.assertEqual((second.status_code, second.json()["resultSource"]), (502, "cache"))
        self.assertEqual(len(requests), 1)

    def test_mobile_demo_mounted_with_bundle_route(self):
        settings = Settings(mode="mock", ledger_path=self.path)
        client = TestClient(create_app(settings, None))
        self.addCleanup(client.close)
        index = client.get("/m/")
        self.assertEqual(index.status_code, 200)
        self.assertIn("一拍知蛇", index.text)
        bundle = client.get("/m/species-cards.json")
        self.assertEqual(bundle.status_code, 200)
        self.assertEqual(bundle.json()["cardSchemaVersion"], "1")

    def test_healthz_exposes_demo_release_flag_default_off(self):
        settings = Settings(mode="mock", ledger_path=self.path)
        client = TestClient(create_app(settings, None))
        self.addCleanup(client.close)
        body = client.get("/healthz").json()
        self.assertFalse(body["demoReleaseUnverified"])

    def test_recorded_keelback_result_maps_and_replays_without_more_calls(self):
        completed = [1000, [{"box": [573, 814, 1228, 1470], "list": [
            [97.6, "颈棱蛇|Red Keelback|Pseudagkistrodon rudis", 9316, "R"],
            [0.38, "|Moorish Viper|Daboia mauritanica", 11437, "R"],
        ]}]]
        requests = []
        responses = iter(([1000, "recorded-keelback-task"], completed))
        def handle(request):
            requests.append(request)
            return httpx.Response(200, json=next(responses))
        catalog = json.loads((ROOT / "data/species.json").read_text(encoding="utf-8"))
        provider = HhodataProvider("test-key", "R", catalog, httpx.MockTransport(handle))
        client = self.live_client(provider, limit=2)
        first = self.upload(client)
        self.assertEqual(first.status_code, 202)
        path = f"/v1/recognitions/{first.json()['recognitionId']}/refresh"
        response = client.post(path, json={"requestId": "query-keelback"}, headers=self.headers)
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["status"], "uncertain")
        self.assertEqual(body["candidates"], [{
            "speciesId": "pseudagkistrodon_rudis", "commonName": "颈棱蛇",
            "scientificName": "Pseudagkistrodon rudis", "score": None, "providerScore": 97.6,
            "demoRelease": False, "nameStatus": "verified",
        }])
        self.assertEqual(body["scoreType"], "unavailable")
        for cached in (self.upload(client, request_id="cached-keelback"),
                       client.post(path, json={"requestId": "cached-query"}, headers=self.headers)):
            self.assertEqual(cached.status_code, 200)
            self.assertEqual(cached.json()["resultSource"], "cache")
            self.assertEqual(cached.json()["candidates"], body["candidates"])
        self.assertEqual(len(requests), 2)
        self.assertEqual(client.get("/healthz").json()["localCallsUsed"], 2)


if __name__ == "__main__":
    unittest.main()
