import os
import re
from dataclasses import dataclass, field
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Settings:
    mode: str = "mock"
    api_key: str = field(default="", repr=False)
    proxy_token: str = field(default="", repr=False)
    animal_class: str = "R"
    live_call_limit: int = 0
    ledger_path: Path = ROOT / ".runtime" / "hhodata.sqlite3"
    # 演示 lane：放行未核验名称进入候选（带 demoRelease 标注）。默认关；路演演示专用，部署不得开启
    demo_release_unverified: bool = False

    def __post_init__(self):
        if self.mode not in {"mock", "hhodata"}:
            raise ValueError("RECOGNITION_MODE must be mock or hhodata")
        if not 0 <= self.live_call_limit <= 50:
            raise ValueError("LIVE_CALL_LIMIT must be between 0 and 50")
        if self.mode == "hhodata":
            if not self.api_key or len(self.proxy_token) < 16:
                raise ValueError("Live mode requires HHODATA_API_KEY and a PROXY_TOKEN of at least 16 characters")
            if not re.fullmatch(r"[BMARF]{1,5}", self.animal_class):
                raise ValueError("HHODATA_CLASS must be a combination of B/M/A/R/F; snake recognition uses R")

    @classmethod
    def from_env(cls):
        return cls(
            mode=os.getenv("RECOGNITION_MODE", "mock"),
            api_key=os.getenv("HHODATA_API_KEY", ""),
            proxy_token=os.getenv("PROXY_TOKEN", ""),
            animal_class=os.getenv("HHODATA_CLASS", "R"),
            live_call_limit=int(os.getenv("LIVE_CALL_LIMIT", "0")),
            ledger_path=Path(os.getenv("LIVE_LEDGER_PATH", str(ROOT / ".runtime" / "hhodata.sqlite3"))),
            demo_release_unverified=os.getenv("DEMO_RELEASE_UNVERIFIED", "") in {"1", "true", "True"},
        )
