import hashlib
import hmac
import json
import re
from time import perf_counter

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from python_multipart.exceptions import MultipartParseError
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException, MultiPartParser

from inference.budget import BudgetLedger, SavedResult
from inference.errors import ServiceError
from inference.images import MAX_IMAGE_BYTES, prepare_image
from inference.models import RecognitionResult
from inference.provider import HhodataProvider
from inference.settings import ROOT, Settings


SCENARIOS = {"candidates": 200, "multiple": 200, "uncertain": 200, "no_snake": 200,
             "pending": 202, "timeout": 504, "invalid_output": 502}
MAX_REQUEST_BYTES = MAX_IMAGE_BYTES + 65_536


def validate_request_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", value):
        raise ServiceError("INVALID_REQUEST", "requestId 须为 1–64 位字母、数字、点、下划线或连字符", 400)
    return value


async def bounded_body(request: Request, limit: int) -> bytes:
    data = bytearray()
    async for chunk in request.stream():
        data.extend(chunk)
        if len(data) > limit:
            raise ServiceError("IMAGE_TOO_LARGE", "请求体超过本地大小限制", 413)
    return bytes(data)


async def read_upload(request: Request):
    if not request.headers.get("content-type", "").lower().startswith("multipart/form-data;"):
        raise ServiceError("INVALID_REQUEST", "请使用 multipart/form-data", 400)
    data = await bounded_body(request, MAX_REQUEST_BYTES)

    async def stream():
        yield data

    parser = MultiPartParser(request.headers, stream(), max_files=1, max_fields=2)
    parser.spool_max_size = MAX_REQUEST_BYTES + 1
    try:
        form = await parser.parse()
    except (MultiPartException, MultipartParseError):
        raise ServiceError("INVALID_REQUEST", "上传表单不符合约定", 400) from None
    try:
        if len(form.multi_items()) != len(form) or set(form) - {"image", "requestId", "uploadConsent"}:
            raise ServiceError("INVALID_REQUEST", "表单包含重复或未知字段", 400)
        request_id = validate_request_id(form.get("requestId"))
        request.state.request_id = request_id
        image = form.get("image")
        if not isinstance(image, UploadFile):
            raise ServiceError("INVALID_IMAGE", "请提供 image 文件", 400)
        consent = form.get("uploadConsent", "false")
        if consent not in {"true", "false"}:
            raise ServiceError("INVALID_REQUEST", "uploadConsent 必须是 true 或 false", 400)
        return request_id, await image.read(MAX_IMAGE_BYTES + 1), consent == "true"
    finally:
        await form.close()


def error_body(request_id: str, error: ServiceError, source: str):
    return {"requestId": request_id, "resultSource": source,
            "error": {"code": error.code, "message": error.message}}


def create_app(settings: Settings | None = None, provider=None):
    settings = settings or Settings.from_env()
    app = FastAPI(title="一拍知蛇 · 开发代理", version="0.1.0")
    ledger = None
    if settings.mode == "hhodata":
        ledger = BudgetLedger(settings.ledger_path, settings.live_call_limit)
        if provider is None:
            catalog = json.loads((ROOT / "data" / "species.json").read_text(encoding="utf-8"))
            provider = HhodataProvider(settings.api_key, settings.animal_class, catalog,
                                       demo_release=settings.demo_release_unverified)

    def authorize(request):
        if settings.proxy_token:
            expected = f"Bearer {settings.proxy_token}".encode()
            actual = request.headers.get("authorization", "").encode()
            if not hmac.compare_digest(actual, expected):
                raise ServiceError("UNAUTHORIZED", "代理访问凭证无效", 401)
        if settings.mode == "hhodata" and "x-mock-scenario" in request.headers:
            raise ServiceError("INVALID_REQUEST", "真实模式不接受模拟场景头", 400)

    def mock_result(request, request_id):
        scenario = request.headers.get("x-mock-scenario", "candidates")
        if scenario not in SCENARIOS:
            raise ServiceError("INVALID_REQUEST", "未知模拟场景", 400)
        body = json.loads((ROOT / "contracts" / "fixtures" / f"{scenario}.json").read_text(encoding="utf-8"))
        body["requestId"] = request_id
        return JSONResponse(body, status_code=SCENARIOS[scenario])

    def replay(saved: SavedResult, request_id: str):
        body = {**saved.body, "requestId": request_id, "resultSource": "cache"}
        return JSONResponse(body, status_code=saved.status_code)

    async def perform(recognition_id, request_id, operation):
        started = perf_counter()
        try:
            result = await operation()
            body = RecognitionResult(
                requestId=request_id,
                status=result.status,
                candidates=result.candidates,
                resultSource="live",
                recognitionId=recognition_id,
                latencyMs=int((perf_counter() - started) * 1000),
            ).model_dump()
            status = 202 if result.status == "pending" else 200
            ledger.finish(recognition_id, status, body, result.task_id)
            return JSONResponse(body, status_code=status)
        except ServiceError as error:
            body = error_body(request_id, error, "live")
            ledger.finish(recognition_id, error.status_code, body)
            return JSONResponse(body, status_code=error.status_code)

    @app.exception_handler(ServiceError)
    async def service_error(request: Request, error: ServiceError):
        source = "mock" if settings.mode == "mock" else "live"
        body = error_body(getattr(request.state, "request_id", "unknown"), error, source)
        return JSONResponse(body, status_code=error.status_code)

    @app.get("/healthz")
    async def health():
        return {"status": "ok", "mode": settings.mode,
                "localCallLimit": settings.live_call_limit,
                "localCallsUsed": ledger.used() if ledger else 0,
                "demoReleaseUnverified": settings.demo_release_unverified}

    @app.post("/v1/recognitions", response_model=RecognitionResult,
              responses={202: {"model": RecognitionResult}},
              openapi_extra={"requestBody": {"required": True, "content": {
                  "multipart/form-data": {"schema": {"type": "object", "required": ["requestId", "image"],
                      "properties": {"requestId": {"type": "string"},
                                     "image": {"type": "string", "format": "binary"},
                                     "uploadConsent": {"type": "string", "enum": ["true", "false"]}}}}
              }}})
    async def recognize(request: Request):
        authorize(request)
        request_id, image, consent = await read_upload(request)
        image = prepare_image(image)
        if settings.mode == "mock":
            return mock_result(request, request_id)
        if not consent:
            raise ServiceError("UPLOAD_CONSENT_REQUIRED", "请先取得图片上传同意", 403)
        digest = hashlib.sha256(settings.animal_class.encode() + b"\0" + image).hexdigest()
        saved = ledger.find_or_reserve_upload(digest)
        if saved is not None:
            return replay(saved, request_id)
        return await perform(digest, request_id, lambda: provider.upload(image))

    @app.post("/v1/recognitions/{recognition_id}/refresh", response_model=RecognitionResult,
              responses={202: {"model": RecognitionResult}},
              openapi_extra={"requestBody": {"required": True, "content": {
                  "application/json": {"schema": {"type": "object", "required": ["requestId"],
                                                  "properties": {"requestId": {"type": "string"}}}}
              }}})
    async def refresh(recognition_id: str, request: Request):
        authorize(request)
        if not re.fullmatch(r"[a-f0-9]{64}", recognition_id):
            raise ServiceError("INVALID_REQUEST", "recognitionId 格式错误", 400)
        try:
            body = json.loads(await bounded_body(request, 4096))
        except (ValueError, UnicodeError):
            raise ServiceError("INVALID_REQUEST", "请提交含 requestId 的 JSON", 400) from None
        if not isinstance(body, dict) or set(body) != {"requestId"}:
            raise ServiceError("INVALID_REQUEST", "仅接受 requestId 字段", 400)
        request_id = validate_request_id(body["requestId"])
        request.state.request_id = request_id
        if settings.mode == "mock":
            return mock_result(request, request_id)
        reserved = ledger.reserve_refresh(recognition_id)
        if isinstance(reserved, SavedResult):
            return replay(reserved, request_id)
        return await perform(recognition_id, request_id, lambda: provider.refresh(reserved))

    return app


app = create_app()
