"""Validation helpers for synthetic training data."""

from __future__ import annotations

import json
import logging
import re
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

REJECTED_LOG = Path(__file__).resolve().parent.parent / "output" / "rejected.jsonl"
DEFAULT_KB_PATH = str(
    Path(__file__).resolve().parent.parent.parent / "kb" / "output" / "aegis_kb.sqlite"
)

AEGIS_RESPONSE_REQUIRED_KEYS = {"flags", "citations", "confidence", "defer_to_professional", "explanation"}
FLAG_REQUIRED_KEYS = {"severity", "description", "citation"}
CITATION_REQUIRED_KEYS = {"source", "text"}

VALID_ROLES = {"system", "user", "model"}

TOOL_CALL_RE = re.compile(r"<\|tool_call>\s*(\{.*?\})\s*<tool_call\|>", re.DOTALL)
TOOL_RESULT_RE = re.compile(r"<\|tool_result>\s*(\{.*?\})\s*<tool_result\|>", re.DOTALL)

# Tokens we accept in a flag's citation field. The check is case-insensitive
# substring match — a citation passes if any token appears anywhere in it.
# This is Tier 2 of the citation validator: presence is Tier 1 (non-empty
# string), Tier 2 adds "must look like a real source". Teacher outputs like
# "common knowledge" or "general medical practice" fail here.
_VALID_CITATION_TOKENS: tuple[str, ...] = (
    # Regulatory / standards bodies
    "fda", "nlm", "nih", "cdc", "ema", "who", "dea", "aap", "ags",
    # Curated medical knowledge bases
    "cpic", "uspstf", "acip", "beers", "lactmed", "medlineplus",
    "dailymed", "rxnorm", "ods", "dsld", "openfda", "micromedex",
    # Specialty medical guideline bodies
    "kdigo", "nkf", "joint commission",
    # Federal health privacy / consent regulations (consent mode)
    "hipaa", "cfr", "hhs", "common rule",
    # Citation-shape markers
    "pmid", "doi:", "ncbi", "monograph", "isbn",
    # Source-type keywords
    "guideline", "label", "criteria", "package insert",
    "prescribing information", "product label",
    # Aegis / check_warnings auto-generated citations
    "clinical best practice", "aegis safety policy",
    "pregnancy and lactation labeling", "controlled substances act",
    "pediatric prescribing",
)


def _is_valid_citation(text: str) -> bool:
    """Tier 1 (non-empty) + Tier 2 (recognizable source token). Passes if
    the citation contains any known source token or citation-shape marker."""
    t = (text or "").strip().lower()
    if not t:
        return False
    return any(tok in t for tok in _VALID_CITATION_TOKENS)


# Teacher sometimes emits severity as a word ("high", "severe") instead of
# the schema-required int 1-5. Coerce gracefully here — JSON-shape strictness
# is the job of validate_aegis_response, not this safety-semantics validator.
_SEVERITY_WORDS: dict[str, int] = {
    "low": 1, "minor": 1, "mild": 1, "none": 0,
    "medium": 3, "moderate": 3,
    "high": 4, "severe": 4, "major": 4,
    "critical": 5, "contraindicated": 5, "extreme": 5,
}


def _coerce_severity(v: Any) -> int:
    """Map a teacher's severity value to an int in 0-5. Unknown shapes
    return 0 (no-signal) — the coherence check still fires for numeric
    ``>= 4`` and string ``"high"``/``"severe"``/``"critical"``, so real
    incoherence is caught despite schema slop."""
    if isinstance(v, bool):
        return 0
    if isinstance(v, (int, float)):
        return max(0, min(5, int(v)))
    if isinstance(v, str):
        s = v.strip().lower()
        if s in _SEVERITY_WORDS:
            return _SEVERITY_WORDS[s]
        try:
            return max(0, min(5, int(s)))
        except ValueError:
            return 0
    return 0


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


_LEADING_TOOL_RESULT_RE = re.compile(r"^\s*(?:<\|tool_result>.*?<tool_result\|>\s*)+", re.DOTALL)


def validate_final_turn_aegis_response(conversation: list[dict[str, Any]]) -> tuple[bool, str | None]:
    """Final assistant turn must be a clean AegisResponse JSON envelope.

    Allows leading hallucinated <|tool_result>...<tool_result|> blocks (the model
    is trained to emit these as part of its own turn). Forbids any <|tool_call>
    in what should be the final answer.

    Returns (ok, reason). Reason is None on success, a short string on failure.
    """
    if not conversation:
        return False, "empty conversation"
    last = next(
        (m for m in reversed(conversation) if isinstance(m, dict) and m.get("role") in ("model", "assistant")),
        None,
    )
    if last is None:
        return False, "no model turn found"
    text = last.get("content", "")
    if not isinstance(text, str):
        return False, "final turn content is not a string"
    text = _LEADING_TOOL_RESULT_RE.sub("", text)
    if "<|tool_call>" in text:
        return False, "final turn contains <|tool_call> fragment"
    text = text.strip()
    if not text:
        return False, "final turn is empty after stripping tool_result blocks"
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return False, "final turn is not valid JSON (likely prose)"
    if not isinstance(parsed, dict):
        return False, "final turn JSON is not an object"
    if not validate_aegis_response(parsed):
        return False, "final turn JSON failed AegisResponse schema"
    # Tier 2: every envelope flag must carry a recognizable source citation.
    # Mirrors the citation gate validate_kb_facts applies to check_warnings
    # tool_result flags, but lifted up to the user-facing AegisResponse.
    for i, flag in enumerate(parsed.get("flags", [])):
        if not _is_valid_citation(str(flag.get("citation", ""))):
            return False, f"flag #{i} has weak/missing citation: {flag.get('citation', '')!r}"
    return True, None


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
    """Ensure every ``<|tool_call>`` block contains valid JSON with name+arguments."""
    for turn in conversation:
        if not isinstance(turn, dict):
            return False
        content = turn.get("content")
        # Some teachers emit content as a list of blocks instead of a string —
        # reject those explicitly so the regex doesn't crash the batch.
        if not isinstance(content, str):
            return False
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


def _extract_check_warnings_pairs(
    conversation: list[dict[str, str]],
) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    """Return (arguments, teacher_result) pairs for every check_warnings
    call in the conversation. Unpaired calls / malformed JSON are skipped.
    """
    blob = "\n".join(
        t["content"] for t in conversation
        if isinstance(t, dict) and t.get("role") == "model"
        and isinstance(t.get("content"), str)
    )
    pairs: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for call_match in TOOL_CALL_RE.finditer(blob):
        try:
            call = json.loads(call_match.group(1))
        except json.JSONDecodeError:
            continue
        if call.get("name") != "check_warnings":
            continue
        # Look for the next tool_result with matching name AFTER this call.
        for res_match in TOOL_RESULT_RE.finditer(blob, call_match.end()):
            try:
                res = json.loads(res_match.group(1))
            except json.JSONDecodeError:
                continue
            if res.get("name") != "check_warnings":
                continue
            pairs.append((call.get("arguments", {}) or {}, res.get("result", {}) or {}))
            break
    return pairs


def validate_kb_facts(
    conversation: list[dict[str, str]],
    db_path: str = DEFAULT_KB_PATH,
) -> bool:
    """Reject teacher outputs that show a safety regression or internal
    incoherence against the real check_warnings tool.

    The gate is intentionally narrow: we do NOT punish the teacher for
    knowing more than the KB (e.g., flagging a serotonergic tramadol +
    SSRI interaction the `interactions` table doesn't have). Our KB is
    known-incomplete (B3 DDInter was skipped), and strict subset checks
    produced a ~26% reject rate on legacy data — half of which are
    clinically-correct teacher reasoning beyond KB coverage.

    Reject only when:
      1. truth.defer_to_professional is True but teacher's is False
         — a real safety regression (polypharmacy, pregnancy, pediatric,
         controlled substance, or unknown drugs that the KB would defer on
         but the teacher claims are safe).
      2. teacher claims a max severity >= 4 but does NOT defer
         — internal incoherence (says "severe" but doesn't recommend a
         professional). This rejects bad training signal regardless of
         what the KB says.

    Accept on:
      - No check_warnings calls in the conversation.
      - KB missing / tool import fails (degrade gracefully).
      - Teacher over-cautions (defers when truth doesn't) — safety-positive.
      - Teacher flags clinical concerns the KB doesn't have — if the
        teacher also defers appropriately, this is accepted.
    """
    pairs = _extract_check_warnings_pairs(conversation)
    if not pairs:
        return True

    try:
        from tools.tools.check_warnings import check_warnings
    except Exception as exc:
        log.info("validate_kb_facts: tool import failed (%s); skipping", exc)
        return True

    if not Path(db_path).exists():
        log.info("validate_kb_facts: KB missing at %s; skipping", db_path)
        return True

    for args, teacher_result in pairs:
        drug_list = args.get("drug_list") or args.get("drugs") or []
        if not isinstance(drug_list, list) or not drug_list:
            continue
        age = args.get("age")
        conditions = args.get("conditions") or []
        if not isinstance(conditions, list):
            conditions = []

        try:
            truth = check_warnings(
                [str(d) for d in drug_list],
                age=age if isinstance(age, int) else None,
                conditions=[str(c) for c in conditions],
                db_path=db_path,
            )
        except Exception as exc:
            log.info("validate_kb_facts: check_warnings raised (%s); skipping", exc)
            continue

        t_defer = bool(teacher_result.get("defer_to_professional"))
        r_defer = bool(truth.get("defer_to_professional"))

        if r_defer and not t_defer:
            log.info(
                "validate_kb_facts: safety regression — truth defers, "
                "teacher does not (args=%s)", args,
            )
            return False

        teacher_flags = teacher_result.get("flags") or []
        if not isinstance(teacher_flags, list):
            teacher_flags = []
        t_sev = max(
            (_coerce_severity(f.get("severity", 0)) for f in teacher_flags
             if isinstance(f, dict)),
            default=0,
        )
        if t_sev >= 4 and not t_defer:
            log.info(
                "validate_kb_facts: incoherent — teacher claims severity "
                "%d but does not defer (args=%s)", t_sev, args,
            )
            return False

        # Citation validation: every flag must have a non-empty, recognizable
        # source citation. Aligns with CLAUDE.md hard constraint that no
        # medical output may be hallucinated without a citation.
        for flag in teacher_flags:
            if not isinstance(flag, dict):
                continue
            if not _is_valid_citation(str(flag.get("citation", ""))):
                log.info(
                    "validate_kb_facts: missing or unrecognizable citation "
                    "in flag %r (args=%s)", flag.get("citation"), args,
                )
                return False

    return True


def reject_and_log(example: dict[str, Any], reason: str) -> None:
    """Append a rejected example with its rejection reason to the reject log."""
    REJECTED_LOG.parent.mkdir(parents=True, exist_ok=True)
    entry = {"reason": reason, "example": example}
    with open(REJECTED_LOG, "a") as f:
        f.write(json.dumps(entry) + "\n")
    log.warning("Rejected example: %s", reason)
