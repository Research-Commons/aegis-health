"""Tests for the datagen contamination firewall."""
from __future__ import annotations

import json
import random
from pathlib import Path

import pytest

from datagen.contamination_guard import AuditReport, ContaminationGuard


def _write_anchor(path: Path, cases: list[dict]) -> None:
    path.write_text(json.dumps(cases), encoding="utf-8")


def _tiny_guard(tmp_path: Path) -> ContaminationGuard:
    dev = tmp_path / "dev.json"
    held = tmp_path / "held.json"
    _write_anchor(dev, [
        {"id": "d-1", "mode": "drugsafe",
         "drug_list": ["warfarin", "ibuprofen"], "expected": {}},
        {"id": "d-2", "mode": "drugsafe",
         "drug_list": ["codeine"], "expected": {}},
        {"id": "d-3", "mode": "consentreader",
         "input": "Explain this clause:  foo  bar", "expected": {}},
    ])
    _write_anchor(held, [
        {"id": "h-1", "mode": "drugsafe",
         "drug_list": ["amoxicillin", "metronidazole"], "expected": {}},
        {"id": "h-2", "mode": "drugsafe",
         "drug_list": ["warfarin", "aspirin", "digoxin"], "expected": {}},
    ])
    return ContaminationGuard.load([dev, held])


def test_load_counts(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    assert len(g.pairs) == 3
    assert frozenset(["warfarin", "ibuprofen"]) in g.pairs
    assert "codeine" in g.singletons
    assert any("explain this clause" in t for t in g.consent_texts)


def test_is_contaminated_exact_pair(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    assert g.is_contaminated_pair(["warfarin", "ibuprofen"])
    assert g.is_contaminated_pair(["Ibuprofen", "Warfarin"])   # order + case
    assert not g.is_contaminated_pair(["warfarin", "atorvastatin"])


def test_is_contaminated_subset_of_polypharmacy(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    # Anchor set is {warfarin, aspirin, digoxin}; asking about just
    # warfarin+aspirin should still be flagged as a leak.
    assert g.is_contaminated_pair(["warfarin", "aspirin"])
    assert g.is_contaminated_pair(["aspirin", "digoxin"])
    # Non-subset: new drug paired with one anchor drug → OK
    assert not g.is_contaminated_pair(["warfarin", "garlic"])


def test_is_contaminated_singleton(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    assert g.is_contaminated_singleton("codeine")
    assert g.is_contaminated_singleton("CODEINE ")
    assert not g.is_contaminated_singleton("tramadol")


def test_safe_sample_retries_until_clean(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    pool = ["warfarin", "ibuprofen", "atorvastatin", "metformin"]
    rng = random.Random(0)
    for _ in range(20):
        pick = g.safe_sample(pool, k=2, rng=rng)
        assert pick is not None
        assert not g.is_contaminated_pair(pick)


def test_safe_sample_returns_none_when_pool_all_contaminated(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    # Pool only contains anchor pair drugs → every combination contaminated.
    pool = ["warfarin", "ibuprofen"]
    pick = g.safe_sample(pool, k=2, rng=random.Random(0), max_tries=10)
    assert pick is None


def test_safe_sample_singleton(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    pool = ["codeine", "tramadol", "acetaminophen"]
    # codeine is a forbidden singleton; should return one of the others.
    for _ in range(20):
        pick = g.safe_sample(pool, k=1, rng=random.Random())
        assert pick is not None and pick[0] != "codeine"


def test_audit_jsonl_flags_seed_pair_collision(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    training = tmp_path / "train.jsonl"
    training.write_text(
        json.dumps({
            "id": "ex-1",
            "seed": {"drug_a": "warfarin", "drug_b": "ibuprofen"},
            "conversation": [{"role": "user", "content": "Hi"}],
        }) + "\n" +
        json.dumps({
            "id": "ex-2",
            "seed": {"drug_a": "metformin", "drug_b": "atorvastatin"},
            "conversation": [{"role": "user", "content": "Hi"}],
        }) + "\n",
        encoding="utf-8",
    )
    report = g.audit_jsonl(training)
    assert report.total_examples == 2
    assert report.contaminated_count == 1
    assert "ex-1" in report.contaminated_ids
    assert "seed pair" in report.reasons["ex-1"]


def test_audit_jsonl_flags_user_content_without_seed(tmp_path: Path):
    """Legacy examples without a seed dict must still be caught by the
    user-content regex."""
    g = _tiny_guard(tmp_path)
    training = tmp_path / "train.jsonl"
    training.write_text(
        json.dumps({
            "id": "legacy-1",
            "conversation": [
                {"role": "user",
                 "content": "I take warfarin and also ibuprofen. OK?"},
            ],
        }) + "\n",
        encoding="utf-8",
    )
    report = g.audit_jsonl(training)
    assert report.contaminated_count == 1
    assert "legacy-1" in report.contaminated_ids


def test_audit_jsonl_handles_missing_file(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    report = g.audit_jsonl(tmp_path / "nope.jsonl")
    assert report.total_examples == 0
    assert report.contaminated_count == 0


def test_audit_summary_truncates_after_20(tmp_path: Path):
    g = _tiny_guard(tmp_path)
    rows = []
    for i in range(25):
        rows.append(json.dumps({
            "id": f"ex-{i}",
            "seed": {"drug_a": "warfarin", "drug_b": "ibuprofen"},
            "conversation": [{"role": "user", "content": "x"}],
        }))
    path = tmp_path / "bulk.jsonl"
    path.write_text("\n".join(rows), encoding="utf-8")
    report = g.audit_jsonl(path)
    assert report.contaminated_count == 25
    summary = report.summary()
    assert "25 / 25" in summary
    assert "5 more" in summary  # truncation marker
