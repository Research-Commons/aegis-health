"""KB-grounded sampling for the synthetic-data teacher.

Each ``sample_*`` function opens no connections of its own — the caller
passes a ``sqlite3.Connection`` (read-only is fine) and a
``ContaminationGuard``. Returns a dict suitable for dropping straight
into a Jinja2 template, plus a ``seed`` key that the teacher pipeline
attaches to every emitted JSONL row for post-hoc contamination audits.

Rows with clinically interesting severity (``severity >= 3``) are
up-weighted 2× during random selection so training data skews toward
cases the model actually needs to learn. Samplers return ``None`` if
the contamination guard rejects every candidate in ``max_tries`` —
callers should either fall back or skip that example.
"""
from __future__ import annotations

import random
import sqlite3
from typing import Any

from .contamination_guard import ContaminationGuard

SamplerResult = dict[str, Any] | None

# Rows with severity >= 3 get this multiplicative weight during random.choices.
_SEVERE_WEIGHT = 2.0
_MILD_WEIGHT = 1.0

# Shared condition pool for profile samplers (kept small + realistic).
_CONDITIONS_POOL = (
    "hypertension", "diabetes type 2", "asthma", "GERD", "CKD stage 3",
    "atrial fibrillation", "heart failure", "COPD", "osteoarthritis",
    "depression", "anxiety", "hypothyroidism", "hyperlipidemia",
    "obesity", "chronic pain",
)


def _weights(rows: list[tuple], sev_index: int) -> list[float]:
    return [
        _SEVERE_WEIGHT if (r[sev_index] or 0) >= 3 else _MILD_WEIGHT
        for r in rows
    ]


# ── DrugSafe: direct pair interactions ────────────────────────────────────

def sample_pair_interaction(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    rows = conn.execute(
        "SELECT drug_name_1, drug_name_2, severity, description, source, "
        "       clinical_effect "
        "FROM interactions"
    ).fetchall()
    if not rows:
        return None
    weights = _weights(rows, sev_index=2)
    for _ in range(max_tries):
        row = rng.choices(rows, weights=weights, k=1)[0]
        drug_a = row[0].lower().strip()
        drug_b = row[1].lower().strip()
        if drug_a == drug_b:
            continue
        if guard.is_contaminated_pair([drug_a, drug_b]):
            continue
        return {
            "drug_a": drug_a,
            "drug_b": drug_b,
            "severity": int(row[2]),
            "description": row[3] or "",
            "clinical_effect": row[5] or "",
            "source": row[4] or "openfda",
            "seed": {"drug_a": drug_a, "drug_b": drug_b},
        }
    return None


# ── DrugSafe: class-level interactions (B1 unlock) ────────────────────────

def sample_class_interaction(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    rules = conn.execute(
        "SELECT class_id_1, class_name_1, class_id_2, class_name_2, "
        "       severity, description, mechanism "
        "FROM class_interactions"
    ).fetchall()
    if not rules:
        return None
    weights = _weights(rules, sev_index=4)
    for _ in range(max_tries):
        rule = rng.choices(rules, weights=weights, k=1)[0]
        class1, cname1, class2, cname2, sev, desc, mech = rule
        m1 = [
            r[0].lower().strip() for r in conn.execute(
                "SELECT DISTINCT drug_name FROM drug_classes WHERE class_id = ?",
                (class1,),
            ).fetchall() if r[0]
        ]
        m2 = [
            r[0].lower().strip() for r in conn.execute(
                "SELECT DISTINCT drug_name FROM drug_classes WHERE class_id = ?",
                (class2,),
            ).fetchall() if r[0]
        ]
        if not m1 or not m2:
            continue
        drug_a = rng.choice(m1)
        drug_b = rng.choice(m2)
        if drug_a == drug_b:
            continue
        if guard.is_contaminated_pair([drug_a, drug_b]):
            continue
        return {
            "drug_a": drug_a,
            "drug_b": drug_b,
            "class_name_a": cname1,
            "class_name_b": cname2,
            "severity": int(sev),
            "description": desc or "",
            "mechanism": mech or "",
            "seed": {"drug_a": drug_a, "drug_b": drug_b},
        }
    return None


# ── DrugSafe: population-specific warning scenarios ───────────────────────

def _sample_warning_by_type(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    warning_type: str,
    rng: random.Random,
    max_tries: int,
) -> list[tuple] | None:
    rows = conn.execute(
        "SELECT drug_name, severity, population, description, source "
        "FROM warnings WHERE warning_type = ?",
        (warning_type,),
    ).fetchall()
    if not rows:
        return None
    weights = _weights(rows, sev_index=1)
    for _ in range(max_tries):
        row = rng.choices(rows, weights=weights, k=1)[0]
        drug = (row[0] or "").lower().strip()
        if not drug:
            continue
        if guard.is_contaminated_singleton(drug):
            continue
        return [row, drug]
    return None


def sample_lactation_scenario(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    hit = _sample_warning_by_type(conn, guard, "lactation", rng, max_tries)
    if hit is None:
        return None
    row, drug = hit
    return {
        "drug": drug,
        "severity": int(row[1] or 0),
        "population": row[2] or "breastfeeding",
        "description": row[3] or "",
        "source": row[4] or "nlm_lactmed",
        "infant_age_months": rng.randint(1, 18),
        "seed": {"drugs": [drug]},
    }


def sample_pim_scenario(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    hit = _sample_warning_by_type(conn, guard, "geriatric", rng, max_tries)
    if hit is None:
        return None
    row, drug = hit
    return {
        "drug": drug,
        "severity": int(row[1] or 0),
        "population": row[2] or "elderly",
        "description": row[3] or "",
        "source": row[4] or "openfda",
        "age": rng.randint(65, 85),
        "seed": {"drugs": [drug]},
    }


def sample_food_scenario(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    hit = _sample_warning_by_type(conn, guard, "food_interaction", rng, max_tries)
    if hit is None:
        return None
    row, drug = hit
    return {
        "drug": drug,
        "severity": int(row[1] or 0),
        "population": row[2] or "all",
        "description": row[3] or "",
        "source": row[4] or "curated_food",
        "seed": {"drugs": [drug]},
    }


def sample_pgx_scenario(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    hit = _sample_warning_by_type(conn, guard, "pharmacogenomic", rng, max_tries)
    if hit is None:
        return None
    row, drug = hit
    return {
        "drug": drug,
        "severity": int(row[1] or 0),
        "gene_phenotype": row[2] or "",
        "description": row[3] or "",
        "source": row[4] or "cpic",
        "seed": {"drugs": [drug]},
    }


# ── HealthPartner: immunization profile (B5 unlock) ───────────────────────

def sample_immunization_profile(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 20,
) -> SamplerResult:
    rng = rng or random
    rows = conn.execute(
        "SELECT title, population_age_min, population_age_max, population_sex, "
        "       description, recommendation_id, risk_only "
        "FROM guidelines WHERE grade = 'I'"
    ).fetchall()
    if not rows:
        return None
    for _ in range(max_tries):
        row = rng.choice(rows)
        title, age_min, age_max, sex, desc, rec_id, risk_only = row
        age_min = int(age_min) if age_min is not None else 18
        age_max = int(age_max) if age_max is not None else 85
        if age_max < age_min:
            continue
        age = rng.randint(age_min, age_max)
        sex_pick = sex if sex in ("male", "female") else rng.choice(["male", "female"])
        profile = {
            "age": age,
            "sex": sex_pick,
            "risk_based": bool(risk_only),
            "vaccine_title": title,
            "vaccine_description": (desc or "")[:400],
            "recommendation_id": rec_id,
            "seed": {"vaccine_id": rec_id, "age": age, "sex": sex_pick},
        }
        return profile
    return None


# ── HealthPartner: general patient profile ────────────────────────────────

def sample_healthpartner_profile(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
) -> SamplerResult:
    rng = rng or random
    rx_rows = conn.execute(
        "SELECT generic_name FROM rxnorm_lookup "
        "WHERE category IN ('Rx', 'OTC') AND generic_name IS NOT NULL"
    ).fetchall()
    pool = sorted({(r[0] or "").lower().strip() for r in rx_rows if r[0]})
    pool = [d for d in pool if d]
    n_meds = rng.randint(0, 2)
    meds: list[str] = []
    if n_meds > 0 and pool:
        picked = guard.safe_sample(pool, k=n_meds, rng=rng)
        if picked is not None:
            meds = [d.lower() for d in picked]
    n_conds = rng.randint(0, 3)
    conds = rng.sample(list(_CONDITIONS_POOL), min(n_conds, len(_CONDITIONS_POOL)))
    return {
        "age": rng.randint(30, 85),
        "sex": rng.choice(["male", "female"]),
        "conditions": conds,
        "medications": meds,
        "family_history": rng.sample(
            ["colon cancer", "breast cancer", "heart disease",
             "diabetes", "lung cancer", "stroke"],
            rng.randint(0, 2),
        ),
        "seed": {"drugs": meds} if meds else {"drugs": []},
    }


# ── DrugSafe: no-interaction baseline pair ────────────────────────────────

def sample_healthy_baseline_pair(
    conn: sqlite3.Connection,
    guard: ContaminationGuard,
    rng: random.Random | None = None,
    max_tries: int = 50,
) -> SamplerResult:
    rng = rng or random
    pool_rows = conn.execute(
        "SELECT generic_name FROM rxnorm_lookup "
        "WHERE category IN ('Rx', 'OTC') AND generic_name IS NOT NULL"
    ).fetchall()
    pool = sorted({(r[0] or "").lower().strip() for r in pool_rows if r[0]})
    pool = [d for d in pool if d]
    if len(pool) < 2:
        return None
    interaction_pairs = {
        frozenset(((a or "").lower().strip(), (b or "").lower().strip()))
        for a, b in conn.execute(
            "SELECT drug_name_1, drug_name_2 FROM interactions"
        ).fetchall()
    }
    for _ in range(max_tries):
        pick = rng.sample(pool, 2)
        pair_set = frozenset(pick)
        if pair_set in interaction_pairs:
            continue
        if guard.is_contaminated_pair(pick):
            continue
        return {
            "drug_a": pick[0],
            "drug_b": pick[1],
            "seed": {"drug_a": pick[0], "drug_b": pick[1]},
        }
    return None
