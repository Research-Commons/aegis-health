"""Tests for datagen.validators.validate_kb_facts.

The validator is meant to reject teacher outputs that fabricate safety
facts: inventing flags, inflating severity, or dropping a deferral that
the real tool would produce. Tests use the real KB so assertions about
truth come from ``check_warnings()`` directly.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO_ROOT / "datagen"))
sys.path.insert(0, str(_REPO_ROOT))  # expose `tools` top-level package

from datagen.validators import (  # noqa: E402
    _extract_check_warnings_pairs,
    validate_kb_facts,
)

KB_PATH = _REPO_ROOT / "kb" / "output" / "aegis_kb.sqlite"


def _wrap(args: dict, result: dict) -> list[dict]:
    """Build a minimal valid-shape conversation containing one
    check_warnings call + result pair."""
    call_blob = json.dumps({"name": "check_warnings", "arguments": args})
    result_blob = json.dumps({"name": "check_warnings", "result": result})
    return [
        {"role": "system", "content": "<SYS>"},
        {"role": "user", "content": "test prompt"},
        {"role": "model", "content": (
            f"<|tool_call>{call_blob}<tool_call|>\n"
            f"<|tool_result>{result_blob}<tool_result|>"
        )},
    ]


# ── Extraction helper ────────────────────────────────────────────────────

def test_extract_pairs_empty_conversation():
    assert _extract_check_warnings_pairs([]) == []


def test_extract_pairs_ignores_other_tools():
    conv = [
        {"role": "model", "content": (
            '<|tool_call>{"name": "normalize_drug", "arguments": {"name": "aspirin"}}<tool_call|>'
            '<|tool_result>{"name": "normalize_drug", "result": {"generic": "aspirin"}}<tool_result|>'
        )}
    ]
    assert _extract_check_warnings_pairs(conv) == []


def test_extract_pairs_picks_check_warnings():
    args = {"drug_list": ["aspirin"], "age": 70}
    result = {"flags": [], "defer_to_professional": False}
    pairs = _extract_check_warnings_pairs(_wrap(args, result))
    assert len(pairs) == 1
    assert pairs[0][0] == args


# ── Skip paths (should accept) ───────────────────────────────────────────

def test_no_check_warnings_calls_accepted():
    conv = [
        {"role": "system", "content": "<SYS>"},
        {"role": "user", "content": "hi"},
        {"role": "model", "content": "Hello!"},
    ]
    assert validate_kb_facts(conv) is True


def test_missing_kb_accepted_gracefully(tmp_path):
    fake_kb = tmp_path / "nope.sqlite"
    conv = _wrap(
        {"drug_list": ["warfarin", "ibuprofen"]},
        {"flags": [{"severity": 5, "description": "x", "citation": "y"}],
         "defer_to_professional": True},
    )
    assert validate_kb_facts(conv, db_path=str(fake_kb)) is True


# ── Behavioral checks (require real KB) ──────────────────────────────────

pytestmark_kb = pytest.mark.skipif(
    not KB_PATH.exists(), reason="KB not built — run `make kb`"
)


@pytestmark_kb
def test_accepts_matching_truth():
    """A teacher claim at or below the real severity ceiling is accepted."""
    # 5+ drugs triggers polypharmacy auto-defer (severity 4).
    args = {
        "drug_list": ["aspirin", "metformin", "atorvastatin", "lisinopril", "losartan"],
        "age": 60, "conditions": [],
    }
    # Truth: at least 1 flag (polypharmacy), defers.
    result = {
        "flags": [{"severity": 4, "description": "Polypharmacy", "citation": "Clinical best practice"}],
        "defer_to_professional": True,
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is True


@pytestmark_kb
def test_rejects_missed_deferral():
    """Truth defers (5-drug polypharmacy) but teacher says defer=False — rejected."""
    args = {
        "drug_list": ["aspirin", "metformin", "atorvastatin", "lisinopril", "losartan"],
        "age": 60, "conditions": [],
    }
    result = {
        "flags": [{"severity": 3, "description": "mild", "citation": "x"}],
        "defer_to_professional": False,
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is False


@pytestmark_kb
def test_rejects_incoherent_high_severity_without_defer():
    """Teacher claims severity 5 but says defer=False — internal incoherence,
    bad training signal regardless of what the KB says."""
    args = {"drug_list": ["acetaminophen"], "age": 40, "conditions": []}
    result = {
        "flags": [{"severity": 5, "description": "dangerous", "citation": "FDA"}],
        "defer_to_professional": False,
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is False


@pytestmark_kb
def test_accepts_over_deferral():
    """Teacher defers when truth doesn't — safety-positive, must accept."""
    args = {"drug_list": ["acetaminophen"], "age": 40, "conditions": []}
    result = {
        "flags": [],
        "defer_to_professional": True,  # over-cautious but safe
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is True


@pytestmark_kb
def test_accepts_extra_flags_when_teacher_defers():
    """Teacher flags clinical concerns the KB doesn't have AND defers —
    accepted. Our KB is incomplete by design; punishing clinical knowledge
    beyond KB coverage was calibrated to ~26% reject rate on legacy data."""
    args = {"drug_list": ["acetaminophen"], "age": 40, "conditions": []}
    result = {
        "flags": [
            {"severity": 4, "description": "extra clinical concern",
             "citation": "FDA acetaminophen label"},
            {"severity": 3, "description": "another",
             "citation": "AGS Beers Criteria"},
        ],
        "defer_to_professional": True,
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is True


@pytestmark_kb
def test_rejects_empty_citation_tier_1():
    """Tier 1: a flag with no citation fails the CLAUDE.md hard constraint
    that every medical output must be grounded in a citation."""
    args = {"drug_list": ["acetaminophen"], "age": 40, "conditions": []}
    result = {
        "flags": [{"severity": 2, "description": "mild concern", "citation": ""}],
        "defer_to_professional": True,
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is False


@pytestmark_kb
def test_rejects_unrecognizable_citation_tier_2():
    """Tier 2: citation that doesn't name any known source fails — catches
    vague citations like 'common knowledge' or 'general medical practice'."""
    args = {"drug_list": ["acetaminophen"], "age": 40, "conditions": []}
    result = {
        "flags": [{"severity": 2, "description": "mild concern",
                   "citation": "common knowledge"}],
        "defer_to_professional": True,
    }
    assert validate_kb_facts(_wrap(args, result), db_path=str(KB_PATH)) is False
