from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Candidate(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    speciesId: str
    commonName: str
    scientificName: str
    score: None = None
    providerScore: float | None = None
    # 演示 lane（DEMO_RELEASE_UNVERIFIED=1）放行的未核验名称：客户端必须显著标注，不得当作核验结论
    demoRelease: bool = False
    nameStatus: Literal["verified", "pending_review"] = "verified"


class RecognitionResult(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schemaVersion: Literal["1"] = "1"
    requestId: str
    status: Literal["candidates", "uncertain", "no_snake", "pending"]
    candidates: list[Candidate] = Field(default_factory=list, max_length=3)
    scoreType: Literal["unavailable"] = "unavailable"
    qualityIssues: list[Literal["blurred", "too_small", "low_light"]] = Field(default_factory=list)
    latencyMs: int = Field(default=0, ge=0)
    resultSource: Literal["mock", "live", "cache"]
    recognitionId: str | None = None

    @model_validator(mode="after")
    def check_status(self):
        if self.status == "candidates" and not self.candidates:
            raise ValueError("candidates status requires candidates")
        if self.status in {"no_snake", "pending"} and self.candidates:
            raise ValueError("no_snake and pending require empty candidates")
        if self.status == "pending" and not self.recognitionId:
            raise ValueError("pending requires recognitionId")
        return self
