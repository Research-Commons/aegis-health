"""Post-hoc contamination audit of the generated training data.

Runs on every `pytest` invocation. Skips gracefully when the datagen
output hasn't been produced yet (so a fresh clone doesn't fail).

Once the v2 dataset exists (generated through the firewall), the
"strict" assertion kicks in: zero contamination tolerated.

Until then, legacy datasets produced before the firewall was built may
contain a small number of anchor-pair mentions; those are logged but
don't fail the test.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

from datagen.contamination_guard import ContaminationGuard

REPO_ROOT    = Path(__file__).resolve().parents[2]
COMBINED_V2  = REPO_ROOT / "datagen" / "output" / "combined_sft_v2.jsonl"
COMBINED_CUR = REPO_ROOT / "datagen" / "output" / "combined_sft.jsonl"


def _skip_if_missing(path: Path) -> None:
    if not path.exists():
        pytest.skip(f"{path.name} not present — run `make data` first")


def test_v2_has_zero_contamination():
    """The v2 dataset — generated through the contamination firewall —
    MUST have zero overlap with anchor cases. Fails hard if any example
    leaks through.

    Skips if v2 hasn't been generated yet; promotion to the canonical
    combined_sft.jsonl happens only after this passes.
    """
    _skip_if_missing(COMBINED_V2)
    guard = ContaminationGuard.load()
    report = guard.audit_jsonl(COMBINED_V2)
    assert report.contaminated_count == 0, report.summary()


def test_current_combined_has_known_legacy_floor():
    """The live combined_sft.jsonl may still carry pre-firewall examples
    with small residual contamination. Audit it so we can see the count,
    but only fail if it regresses beyond the known floor."""
    _skip_if_missing(COMBINED_CUR)
    guard = ContaminationGuard.load()
    report = guard.audit_jsonl(COMBINED_CUR)
    # Current known floor: 4/203 legacy examples mention an anchor pair
    # in user content. If generation adds new contaminated examples,
    # this test regresses and alerts us.
    MAX_TOLERATED = int(os.environ.get("AEGIS_CONTAMINATION_FLOOR", "4"))
    if report.contaminated_count > MAX_TOLERATED:
        pytest.fail(report.summary())
    elif report.contaminated_count > 0:
        # Informational — print to captured output, do not fail.
        print(f"\nLegacy contamination tolerated (≤{MAX_TOLERATED}):")
        print(report.summary())


def test_guard_loads_nonempty_anchor_surface():
    """Sanity-check: the guard must actually have drug pairs loaded. If
    this drops to zero the audit becomes a no-op and will silently pass
    contaminated data — worth a positive assertion."""
    guard = ContaminationGuard.load()
    assert len(guard.pairs) > 0
    assert len(guard.singletons) > 0
