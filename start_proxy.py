"""加载 .env 并启动识别代理（真实 HHodata 模式）。

用法：
    python start_proxy.py            # 默认 127.0.0.1:8765
    python start_proxy.py 8765       # 指定端口

说明：
- .env 内含密钥，已被 .gitignore 排除，不会入库。
- 代理只监听回环地址；手机侧通过 `adb reverse tcp:8765 tcp:8765` 访问。
- LIVE_CALL_LIMIT 是本地预算闸门（SQLite 账本持久化，防绕过）。
"""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load_env(path: Path) -> None:
    if not path.exists():
        raise SystemExit(f"缺少 {path}；请参照 .env.example 创建")
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip())


def main() -> None:
    load_env(ROOT / ".env")
    port = sys.argv[1] if len(sys.argv) > 1 else "8765"
    (ROOT / ".runtime").mkdir(parents=True, exist_ok=True)

    import uvicorn

    print(f"RECOGNITION_MODE={os.environ.get('RECOGNITION_MODE')}")
    print(f"LIVE_CALL_LIMIT={os.environ.get('LIVE_CALL_LIMIT')}  HHODATA_CLASS={os.environ.get('HHODATA_CLASS')}")
    print(f"listening on 127.0.0.1:{port}")
    uvicorn.run("inference.app:app", host="127.0.0.1", port=int(port), log_level="info")


if __name__ == "__main__":
    main()
