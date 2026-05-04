"""Smoke + contract tests for datagen.kb_sampler.

Skipped when ``kb/output/aegis_kb.sqlite`` is absent so CI on a fresh
clone passes. All tests open a read-only connection to the real KB —
the sampler is small and deterministic under a seeded rng, so we
verify contracts rather than enumerate every row.
"""
from __future__ import annotations

import random
import sqlite3
import sys
from collections import Counter
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_REPO_ROOT / "datagen"))

from datagen.contamination_guard import ContaminationGuard  # noqa: E402
from datagen.kb_sampler import (  # noqa: E402
    sample_class_interaction,
    sample_food_scenario,
    sample_healthpartner_profile,
    sample_healthy_baseline_pair,
    sample_immunization_profile,
    sample_lactation_scenario,
    sample_pair_interaction,
    sample_pgx_scenario,
    sample_pim_scenario,
)

KB_PATH = _REPO_ROOT / "kb" / "output" / "aegis_kb.sqlite"

pytestmark = pytest.mark.skipif(
    not KB_PATH.exists(), reason="KB not built — run `make kb`"
)


@pytest.fixture(scope="module")
def conn():
    c = sqlite3.connect(f"file:{KB_PATH}?mode=ro", uri=True)
    yield c
    c.close()


@pytest.fixture(scope="module")
def guard():
    return ContaminationGuard.load()


@pytest.fixture
def rng():
    return random.Random(1337)


# ── Shape contracts (one call each) ──────────────────────────────────────

def test_pair_interaction_shape(conn, guard, rng):
    r = sample_pair_interaction(conn, guard, rng=rng)
    assert r is not None
    assert {"drug_a", "drug_b", "severity", "description", "seed"} <= set(r)
    assert r["seed"] == {"drug_a": r["drug_a"], "drug_b": r["drug_b"]}
    assert r["drug_a"] != r["drug_b"]
    assert 1 <= r["severity"] <= 5


def test_class_interaction_expands_to_concrete_drugs(conn, guard, rng):
    r = sample_class_interaction(conn, guard, rng=rng)
    assert r is not None
    assert r["drug_a"] and r["drug_b"]
    assert r["drug_a"] != r["drug_b"]
    assert r["class_name_a"] and r["class_name_b"]
    assert 1 <= r["severity"] <= 5


def test_lactation_shape(conn, guard, rng):
    r = sample_lactation_scenario(conn, guard, rng=rng)
    assert r is not None
    assert r["drug"]
    assert 1 <= r["infant_age_months"] <= 18
    assert r["seed"] == {"drugs": [r["drug"]]}


def test_pim_shape(conn, guard, rng):
    r = sample_pim_scenario(conn, guard, rng=rng)
    assert r is not None
    assert r["drug"]
    assert 65 <= r["age"] <= 85


def test_food_shape(conn, guard, rng):
    r = sample_food_scenario(conn, guard, rng=rng)
    assert r is not None
    assert r["drug"]
    assert r["description"]


def test_pgx_shape(conn, guard, rng):
    r = sample_pgx_scenario(conn, guard, rng=rng)
    assert r is not None
    assert r["drug"]
    assert r["gene_phenotype"]


def test_immunization_shape(conn, guard, rng):
    r = sample_immunization_profile(conn, guard, rng=rng)
    assert r is not None
    assert r["sex"] in ("male", "female")
    assert r["vaccine_title"]
    assert r["recommendation_id"]
    assert isinstance(r["risk_based"], bool)


def test_healthpartner_profile_shape(conn, guard, rng):
    r = sample_healthpartner_profile(conn, guard, rng=rng)
    assert r is not None
    assert 30 <= r["age"] <= 85
    assert r["sex"] in ("male", "female")
    assert isinstance(r["conditions"], list)
    assert isinstance(r["medications"], list)
    assert len(r["medications"]) <= 2


def test_healthy_baseline_has_no_known_interaction(conn, guard, rng):
    r = sample_healthy_baseline_pair(conn, guard, rng=rng)
    assert r is not None
    hit = conn.execute(
        "SELECT 1 FROM interactions "
        "WHERE (drug_name_1 = ? AND drug_name_2 = ?) "
        "   OR (drug_name_1 = ? AND drug_name_2 = ?) LIMIT 1",
        (r["drug_a"], r["drug_b"], r["drug_b"], r["drug_a"]),
    ).fetchone()
    assert hit is None, f"baseline pair {r['drug_a']}+{r['drug_b']} has a KB interaction"


# ── Contamination guard integration ──────────────────────────────────────

def test_pair_interaction_never_emits_anchor_pair(conn, guard, rng):
    """Run 200 draws; no emitted pair should collide with the anchor surface."""
    rng = random.Random(0)
    for _ in range(200):
        r = sample_pair_interaction(conn, guard, rng=rng)
        assert r is not None
        assert not guard.is_contaminated_pair([r["drug_a"], r["drug_b"]])


def test_single_drug_samplers_never_emit_anchor_singleton(conn, guard):
    rng = random.Random(7)
    for fn in (sample_lactation_scenario, sample_pim_scenario,
               sample_food_scenario, sample_pgx_scenario):
        for _ in range(100):
            r = fn(conn, guard, rng=rng)
            if r is None:
                continue  # small pool may legitimately exhaust
            assert not guard.is_contaminated_singleton(r["drug"]), (
                f"{fn.__name__} emitted contaminated singleton: {r['drug']}"
            )


# ── Severity weighting (≥3 should be over-represented vs naive base rate) ─

def test_pair_interaction_severity_weighting_biases_severe(conn, guard):
    """Over 300 draws, severity>=3 should land ~85%+ of the time, clearly
    above the 83% base rate in the 3,330-row KB (sev>=3 = 2,978/3,330 ≈
    89.4% at 2× weight: severe=2978*2=5956, mild=352 → 94.4% expected)."""
    rng = random.Random(42)
    counts = Counter()
    for _ in range(300):
        r = sample_pair_interaction(conn, guard, rng=rng)
        assert r is not None
        counts["severe" if r["severity"] >= 3 else "mild"] += 1
    severe_pct = counts["severe"] / 300
    # Base rate would be ~89%; weighted should clear 92%.
    assert severe_pct >= 0.92, f"expected >=92% severe, got {severe_pct:.2%}"
