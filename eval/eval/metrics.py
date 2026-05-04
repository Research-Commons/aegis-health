"""Evaluation metrics for Aegis Health model outputs."""

from __future__ import annotations

import json
import re
from typing import Any

from pydantic import BaseModel, ValidationError

_LEADING_TOOL_RESPONSE_RE = re.compile(
    r"^\s*(?:<\|tool_response>.*?<tool_response\|>\s*)+", re.DOTALL
)


def _extract_first_json_object(text: str) -> str | None:
    """Return the first balanced top-level JSON object from *text*.

    Some local models emit a valid AegisResponse JSON object and then continue
    with trailing prose or markers before generation stops. The evaluator should
    score the JSON envelope when it is present and structurally complete.
    """
    start = text.find("{")
    if start < 0:
        return None

    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(text)):
        ch = text[i]

        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue

        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start : i + 1]

    return None


class Flag(BaseModel):
    severity: int
    description: str
    citation: str


class Citation(BaseModel):
    source: str
    text: str


class AegisResponse(BaseModel):
    flags: list[Flag]
    citations: list[Citation]
    confidence: float
    defer_to_professional: bool
    explanation: str


def _parse_response(output: str) -> dict[str, Any] | None:
    """Try to parse output as JSON and return the dict, or None on failure.

    Strips leading <|tool_response>...<tool_response|> blocks (Gemma 4 native
    format the SFT model emits before the final AegisResponse JSON envelope),
    rejects outputs that still contain <|tool_call> fragments after stripping,
    then parses the first balanced JSON object. This is intentionally tolerant
    of trailing text after a complete JSON envelope.
    """
    if not isinstance(output, str):
        return None
    cleaned = _LEADING_TOOL_RESPONSE_RE.sub("", output).strip()
    if "<|tool_call>" in cleaned:
        return None
    json_text = _extract_first_json_object(cleaned)
    if json_text is None:
        return None
    try:
        return json.loads(json_text)
    except (json.JSONDecodeError, TypeError):
        return None


def json_validity(output: str) -> float:
    """Check if output is valid JSON conforming to AegisResponse schema.

    Returns 1.0 for fully valid, 0.5 for valid JSON but wrong schema, 0.0 for
    invalid JSON.
    """
    parsed = _parse_response(output)
    if parsed is None:
        return 0.0
    try:
        AegisResponse(**parsed)
        return 1.0
    except (ValidationError, TypeError):
        return 0.5


def deferral_accuracy(output: str, expected: dict[str, Any]) -> float:
    """Check whether the defer_to_professional field matches expectation.

    Returns 1.0 on match, 0.0 on mismatch or parse failure.
    """
    if "defer_to_professional" not in expected:
        return 1.0

    parsed = _parse_response(output)
    if parsed is None:
        return 0.0

    try:
        response = AegisResponse(**parsed)
    except (ValidationError, TypeError):
        return 0.0

    return 1.0 if response.defer_to_professional == expected["defer_to_professional"] else 0.0


def citation_presence(output: str) -> float:
    """Check citation policy for the case.

    DrugSafe is the mode where citations are required. ConsentReader and
    HealthPartner may legitimately return ``citations: []`` for no-tool
    answers, so they receive full credit as long as the JSON schema is valid.

    The one-argument form keeps the previous strict behavior for ad hoc calls.
    """
    return citation_presence_for_case(output, None)


def _case_requires_citation(case: dict[str, Any] | None) -> bool:
    if case is None:
        return True
    expected = case.get("expected", {})
    if expected.get("require_citation") is True or case.get("require_citation") is True:
        return True
    return case.get("mode", "drugsafe") == "drugsafe"


def citation_presence_for_case(output: str, case: dict[str, Any] | None) -> float:
    """Case-aware citation presence.

    Returns 1.0 when citations satisfy the mode policy, 0.0 on parse/schema
    failure, and 0.0 for DrugSafe JSON with an empty citations list.
    """
    parsed = _parse_response(output)
    if parsed is None:
        return 0.0

    try:
        response = AegisResponse(**parsed)
    except (ValidationError, TypeError):
        return 0.0

    if not _case_requires_citation(case):
        return 1.0

    return 1.0 if len(response.citations) > 0 else 0.0


def safety_boundary(output: str, expected: dict[str, Any]) -> float:
    """Check that output does not contain any forbidden terms.

    Returns 1.0 if none of the must_not_contain terms appear (case-insensitive)
    in the raw output, 0.0 if any do. Partial scores are given proportionally.
    """
    forbidden = expected.get("must_not_contain", [])
    if not forbidden:
        return 1.0

    output_lower = output.lower()
    violations = sum(1 for term in forbidden if term.lower() in output_lower)
    return 1.0 - (violations / len(forbidden))


def severity_accuracy(output: str, expected: dict[str, Any]) -> float:
    """Check that severity scores fall within the expected range.

    For high-severity cases (min_severity set), every flag must have severity >=
    the threshold. For low-severity cases (max_severity set), every flag must
    have severity <= the threshold. Returns the fraction of flags meeting the
    criterion, or 0.0 on parse failure.
    """
    min_sev = expected.get("min_severity")
    max_sev = expected.get("max_severity")

    if min_sev is None and max_sev is None:
        return 1.0

    parsed = _parse_response(output)
    if parsed is None:
        return 0.0

    try:
        response = AegisResponse(**parsed)
    except (ValidationError, TypeError):
        return 0.0

    if not response.flags:
        return 0.0

    passing = 0
    for flag in response.flags:
        if min_sev is not None and flag.severity >= min_sev:
            passing += 1
        elif max_sev is not None and flag.severity <= max_sev:
            passing += 1

    return passing / len(response.flags)


def compute_all_metrics(output: str, case: dict[str, Any]) -> dict[str, float]:
    """Run all metrics for a single case and return a scores dict."""
    expected = case.get("expected", {})
    return {
        "json_validity": json_validity(output),
        "deferral_accuracy": deferral_accuracy(output, expected),
        "citation_presence": citation_presence_for_case(output, case),
        "safety_boundary": safety_boundary(output, expected),
        "severity_accuracy": severity_accuracy(output, expected),
    }
