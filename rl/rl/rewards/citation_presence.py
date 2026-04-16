"""Reward function for citation presence and quality."""

from __future__ import annotations

import json
from typing import Any


def citation_presence_reward(output: str, **kwargs: Any) -> float:
    """Score output based on whether citations are present and well-formed.

    Returns:
        1.0 — citations list is non-empty and every entry has a valid ``source`` field
        0.5 — some citations exist but at least one is missing the ``source`` field
        0.0 — no citations, empty list, or unparseable JSON
    """
    try:
        parsed = json.loads(output)
    except (json.JSONDecodeError, TypeError):
        return 0.0

    if not isinstance(parsed, dict):
        return 0.0

    citations = parsed.get("citations")
    if not isinstance(citations, list) or len(citations) == 0:
        return 0.0

    valid = sum(
        1
        for c in citations
        if isinstance(c, dict) and isinstance(c.get("source"), str) and c["source"].strip()
    )

    if valid == len(citations):
        return 1.0
    if valid > 0:
        return 0.5
    return 0.0
