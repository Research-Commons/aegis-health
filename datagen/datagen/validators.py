"""Validation helpers for synthetic training data."""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

REJECTED_LOG = Path(__file__).resolve().parent.parent / "output" / "rejected.jsonl"

AEGIS_RESPONSE_REQUIRED_KEYS = {"flags", "citations", "confidence", "defer_to_professional", "explanation"}
FLAG_REQUIRED_KEYS = {"severity", "description", "citation"}
CITATION_REQUIRED_KEYS = {"source", "text"}

VALID_ROLES = {"system", "user", "model"}

TOOL_CALL_RE = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.DOTALL)


def validate_aegis_response(data: dict[str, Any]) -> bool:
    """Check that *data* conforms to the AegisResponse schema."""
    if not isinstance(data, dict):
        return False
    if not AEGIS_RESPONSE_REQUIRED_KEYS.issubset(data.keys()):
        return False

    if not isinstance(data["confidence"], (int, float)):
        return False
    if not (0.0 <= data["confidence"] <= 1.0):
        return False
    if not isinstance(data["defer_to_professional"], bool):
        return False
    if not isinstance(data["explanation"], str):
        return False

    for flag in data.get("flags", []):
        if not isinstance(flag, dict) or not FLAG_REQUIRED_KEYS.issubset(flag.keys()):
            return False
        if not isinstance(flag["severity"], int) or not (1 <= flag["severity"] <= 5):
            return False

    for cit in data.get("citations", []):
        if not isinstance(cit, dict) or not CITATION_REQUIRED_KEYS.issubset(cit.keys()):
            return False

    return True


def validate_chat_format(conversation: list[dict[str, str]]) -> bool:
    """Validate Gemma 4 chat-template structure.

    Each turn must have ``role`` (system | user | model) and ``content``.
    The conversation must start with a system turn, then alternate user/model.
    """
    if not conversation or not isinstance(conversation, list):
        return False

    for turn in conversation:
        if not isinstance(turn, dict):
            return False
        if "role" not in turn or "content" not in turn:
            return False
        if turn["role"] not in VALID_ROLES:
            return False

    if conversation[0]["role"] != "system":
        return False

    expected_role = "user"
    for turn in conversation[1:]:
        if turn["role"] not in (expected_role, "model"):
            if turn["role"] == "user" and expected_role == "user":
                pass
            else:
                return False
        if turn["role"] == "user":
            expected_role = "model"
        elif turn["role"] == "model":
            expected_role = "user"

    return True


def validate_tool_calls(conversation: list[dict[str, str]]) -> bool:
    """Ensure every ``<tool_call>`` block contains valid JSON with name+arguments."""
    for turn in conversation:
        if not isinstance(turn, dict):
            return False
        content = turn.get("content", "")
        for match in TOOL_CALL_RE.finditer(content):
            try:
                payload = json.loads(match.group(1))
            except json.JSONDecodeError:
                return False
            if "name" not in payload or "arguments" not in payload:
                return False
            if not isinstance(payload["arguments"], dict):
                return False
    return True


def reject_and_log(example: dict[str, Any], reason: str) -> None:
    """Append a rejected example with its rejection reason to the reject log."""
    REJECTED_LOG.parent.mkdir(parents=True, exist_ok=True)
    entry = {"reason": reason, "example": example}
    with open(REJECTED_LOG, "a") as f:
        f.write(json.dumps(entry) + "\n")
    log.warning("Rejected example: %s", reason)
