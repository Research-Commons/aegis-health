"""Contamination firewall for synthetic-data generation.

Loads every drug pair and single-drug scenario from the eval anchor cases
(dev + held-out) and exposes helpers that the teacher sampler calls before
emitting a seed. The goal is a strict guarantee that no training example
shares its drug pair with a case the model will be scored against.

Usage pattern:

    guard = ContaminationGuard.load()
    pair = guard.safe_sample(DRUG_POOL, k=2, rng=random)

    for drug in sampled_single_drugs:
        if guard.is_contaminated_singleton(drug):
            ...  # skip / resample

    # Post-hoc CI audit:
    report = guard.audit_jsonl("datagen/output/combined_sft.jsonl")
    assert report.contaminated_count == 0, report.summary()
"""
from __future__ import annotations

import json
import logging
import random
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

log = logging.getLogger(__name__)

# Default anchor-file locations resolved relative to the repo root.
_REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ANCHOR_PATHS: tuple[Path, ...] = (
    _REPO_ROOT / "eval" / "eval" / "anchor_cases.json",
    _REPO_ROOT / "eval" / "eval" / "anchor_cases_heldout.json",
)


@dataclass
class AuditReport:
    """Result of scanning a training-data file for contamination."""

    total_examples: int = 0
    contaminated_count: int = 0
    contaminated_ids: list[str] = field(default_factory=list)
    reasons: dict[str, str] = field(default_factory=dict)

    def summary(self) -> str:
        pct = (self.contaminated_count / self.total_examples * 100
               if self.total_examples else 0.0)
        lines = [
            f"Audit: {self.contaminated_count} / {self.total_examples} "
            f"examples contaminated ({pct:.1f}%)",
        ]
        for cid in self.contaminated_ids[:20]:
            lines.append(f"  - {cid}: {self.reasons.get(cid, '?')}")
        if self.contaminated_count > 20:
            lines.append(f"  ... {self.contaminated_count - 20} more")
        return "\n".join(lines)


class ContaminationGuard:
    """Immutable snapshot of anchor-case drug pairs + singletons.

    Build once at process startup via ``ContaminationGuard.load()``. All
    subsequent lookups are O(1) set operations.
    """

    def __init__(
        self,
        pairs: set[frozenset[str]],
        singletons: set[str],
        consent_texts: set[str],
    ) -> None:
        self.pairs = pairs
        self.singletons = singletons
        self.consent_texts = consent_texts

    # ── Construction ──────────────────────────────────────────────────────

    @classmethod
    def load(
        cls, anchor_paths: Sequence[Path] | None = None,
    ) -> "ContaminationGuard":
        paths = tuple(anchor_paths) if anchor_paths else DEFAULT_ANCHOR_PATHS
        pairs: set[frozenset[str]] = set()
        singletons: set[str] = set()
        consent: set[str] = set()
        for path in paths:
            if not path.exists():
                log.warning("ContaminationGuard: anchor file missing: %s", path)
                continue
            cases = json.loads(path.read_text(encoding="utf-8"))
            for case in cases:
                drugs = [
                    d.lower().strip()
                    for d in case.get("drug_list", []) or []
                    if d
                ]
                if len(drugs) == 1:
                    singletons.add(drugs[0])
                elif len(drugs) >= 2:
                    pairs.add(frozenset(drugs))
                if case.get("mode") == "consentreader":
                    consent.add(case.get("input", "").lower().strip())
        log.info(
            "ContaminationGuard: loaded %d drug-pairs, %d singletons, %d consent texts "
            "from %d anchor file(s).",
            len(pairs), len(singletons), len(consent), len(paths),
        )
        return cls(pairs=pairs, singletons=singletons, consent_texts=consent)

    # ── Lookup helpers ────────────────────────────────────────────────────

    def is_contaminated_pair(self, drugs: Iterable[str]) -> bool:
        """True if the given drug set (as an unordered frozenset) appears in
        any anchor case — either as the exact pair or as a subset of a
        larger multi-drug anchor combination.
        """
        candidate = frozenset(d.lower().strip() for d in drugs if d)
        if len(candidate) < 2:
            return False
        if candidate in self.pairs:
            return True
        # Also flag any subset hit — e.g. [aspirin, warfarin] would collide
        # with the polypharmacy anchor {warfarin, aspirin, digoxin, ...}.
        for anchor in self.pairs:
            if candidate.issubset(anchor):
                return True
        return False

    def is_contaminated_singleton(self, drug: str) -> bool:
        return (drug or "").lower().strip() in self.singletons

    def is_contaminated_consent(self, text: str) -> bool:
        return (text or "").lower().strip() in self.consent_texts

    # ── Safe sampling ─────────────────────────────────────────────────────

    def safe_sample(
        self,
        pool: Sequence[str],
        k: int,
        rng: random.Random | None = None,
        max_tries: int = 50,
    ) -> list[str] | None:
        """Return ``k`` distinct drugs from ``pool`` whose set is not
        contaminated. Returns None if no clean combination found in
        ``max_tries`` attempts (caller should fall back or skip)."""
        rng = rng or random
        if k <= 0 or k > len(pool):
            return None
        for _ in range(max_tries):
            pick = rng.sample(list(pool), k)
            if k == 1:
                if not self.is_contaminated_singleton(pick[0]):
                    return pick
            else:
                if not self.is_contaminated_pair(pick):
                    return pick
        return None

    # ── Post-hoc JSONL audit ──────────────────────────────────────────────

    # We recognize drug mentions in user turns by case-insensitive whole-word
    # match against any drug name present in the anchor forbidden surface.
    # That surface is small (≤100 unique drugs across dev + held-out), so
    # a compiled alternation is fast and good enough for CI-scale audits.
    def _drug_name_regex(self) -> re.Pattern[str]:
        names: set[str] = set(self.singletons)
        for p in self.pairs:
            names.update(p)
        escaped = sorted((re.escape(n) for n in names if n),
                         key=len, reverse=True)
        if not escaped:
            return re.compile(r"(?!x)x")  # match-nothing sentinel
        return re.compile(
            r"(?<![\w])(?:" + "|".join(escaped) + r")(?![\w])",
            re.IGNORECASE,
        )

    def audit_jsonl(
        self, path: str | Path, use_seed: bool = True,
    ) -> AuditReport:
        """Scan a JSONL datagen output file for contamination.

        If ``use_seed`` is True and examples carry a ``seed`` dict (produced
        by the guarded teacher.py), the audit inspects seed-level fields
        (``drug_a``, ``drug_b``, ``drugs``) for exact-pair contamination.

        In either case, we ALSO scan the user-turn content with a drug-name
        regex built from the anchor drug surface. Any example whose user
        content mentions ≥2 anchor drugs in a single utterance is flagged.
        """
        path = Path(path)
        report = AuditReport()
        if not path.exists():
            return report
        rx = self._drug_name_regex()

        with path.open(encoding="utf-8") as f:
            for idx, line in enumerate(f):
                line = line.strip()
                if not line:
                    continue
                try:
                    ex = json.loads(line)
                except json.JSONDecodeError:
                    continue
                report.total_examples += 1
                ex_id = ex.get("id") or f"line-{idx + 1}"
                reason: str | None = None

                # (1) Seed-level check (strict and precise)
                if use_seed and isinstance(ex.get("seed"), dict):
                    seed = ex["seed"]
                    candidate_drugs: list[str] = []
                    if "drug_a" in seed and "drug_b" in seed:
                        candidate_drugs = [str(seed["drug_a"]), str(seed["drug_b"])]
                    elif isinstance(seed.get("drugs"), list):
                        candidate_drugs = [str(d) for d in seed["drugs"]]
                    if len(candidate_drugs) == 1 and self.is_contaminated_singleton(
                        candidate_drugs[0]
                    ):
                        reason = f"seed singleton {candidate_drugs[0]} in anchor set"
                    elif len(candidate_drugs) >= 2 and self.is_contaminated_pair(
                        candidate_drugs
                    ):
                        reason = (
                            f"seed pair {sorted(d.lower() for d in candidate_drugs)} "
                            f"matches anchor pair"
                        )

                # (2) Content-level regex check (catches legacy examples
                # without seed metadata and belt-and-suspenders for seeded runs).
                if reason is None:
                    user_turns = [
                        t.get("content", "")
                        for t in ex.get("conversation", [])
                        if t.get("role") == "user"
                    ]
                    for turn in user_turns:
                        mentions = set(m.lower() for m in rx.findall(turn))
                        if len(mentions) >= 2:
                            if self.is_contaminated_pair(mentions):
                                reason = (
                                    "user-turn mentions anchor pair: "
                                    f"{sorted(mentions)}"
                                )
                                break

                if reason is not None:
                    report.contaminated_count += 1
                    report.contaminated_ids.append(ex_id)
                    report.reasons[ex_id] = reason

        return report
