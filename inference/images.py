from io import BytesIO
import warnings

from PIL import Image, ImageOps, UnidentifiedImageError

from inference.errors import ServiceError


MAX_IMAGE_BYTES = 2_000_000
MAX_PIXELS = 16_000_000


def prepare_image(data: bytes) -> bytes:
    if len(data) > MAX_IMAGE_BYTES:
        raise ServiceError("IMAGE_TOO_LARGE", "图片不得超过 2,000,000 字节", 413)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as image:
                if image.format != "JPEG":
                    raise ServiceError("INVALID_IMAGE", "当前仅接收 JPEG 图片", 400)
                width, height = image.size
                if not (11 <= width <= 8192 and 11 <= height <= 8192) or width * height > MAX_PIXELS:
                    raise ServiceError("INVALID_IMAGE", "图片边长须为 11–8192，且不超过 1600 万像素", 400)
                image.load()
                clean = ImageOps.exif_transpose(image).convert("RGB")
                clean.info.clear()
                output = BytesIO()
                clean.save(output, format="JPEG", quality=90)
                result = output.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise ServiceError("INVALID_IMAGE", "无法解码图片", 400) from None
    if len(result) > MAX_IMAGE_BYTES:
        raise ServiceError("IMAGE_TOO_LARGE", "规范化图片后仍超过 2,000,000 字节，请缩小图片", 413)
    return result
