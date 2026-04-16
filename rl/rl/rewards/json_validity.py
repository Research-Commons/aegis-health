"""Reward function for JSON schema validity against AegisResponse."""

from __future__ import annotations

import json
from typing import Any

from pydantic import BaseModel, Field, ValidationError


class Flag(BaseModel):
    severity: int = Field(ge=1, le=5)
    description: str
    citation: str


class Citation(BaseModel):
    source: str
    text: str


class AegisResponse(BaseModel):
    flags: list[Flag] = Field(default_factory=list)
    citations: list[Citation] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    defer_to_professional: bool = False
    explanation: str = ""


REQUIRED_FIELDS = {"flags", "citations", "confidence", "defer_to_professional", "explanation"}


def json_validity_reward(output: str, **kwargs: Any) -> float:
    """Score output based on JSON validity and AegisResponse schema conformance.

    Returns:
        1.0 — valid JSON matching AegisResponse (all fields present, correct types)
        0.5 — valid JSON but missing/wrong fields
        0.0 — not valid JSON
    """
    try:
        parsed = json.loads(output)
    except (json.JSONDecodeError, TypeError):
        return 0.0

    if not isinstance(parsed, dict):
        return 0.5

    try:
        AegisResponse(**parsed)
        return 1.0
    except (ValidationError, TypeError):
        return 0.5
