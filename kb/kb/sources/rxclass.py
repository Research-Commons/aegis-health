"""RxClass source – maps drugs (rxcui) to pharmacologic classes.

Uses the NLM RxClass REST API
(https://rxnav.nlm.nih.gov/REST/rxclass/class/byRxcui.json) to populate
`drug_classes`, then seeds `class_interactions` with curated class-pair
safety rules. Together these let `check_warnings` catch "any SSRI +
any MAOI"-style combos that the pairwise `interactions` table misses.

Class sources queried (per RxClass `relaSource` parameter):
  ATC       – WHO Anatomical Therapeutic Chemical classification
  DAILYMED  – FDA Established Pharmacologic Class (EPC) + MoA from SPLs

License: Public domain (NLM).
"""
from __future__ import annotations

import logging
import sqlite3
import time
from typing import Any

import requests

log = logging.getLogger(__name__)

RXCLASS_BASE = "https://rxnav.nlm.nih.gov/REST/rxclass"
REQUEST_TIMEOUT = 15
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0
RATE_LIMIT_SLEEP = 0.12  # ~8 req/s; NLM RxNav allows up to ~20/s.

# Relationship sources to query. ATC gives the WHO hierarchy (best for
# cross-drug class matching); DAILYMED exposes FDA EPC + MoA pulled from
# the SPL labels.
RELA_SOURCES: list[tuple[str, list[str]]] = [
    # (relaSource, class types to retain from the response)
    ("ATC",      ["ATC1-4"]),
    ("DAILYMED", ["EPC", "MoA"]),
]


# ---------------------------------------------------------------------------
# Curated class-pair safety rules.
# Every entry cites a primary regulatory source. ATC codes follow the WHO
# Collaborating Centre for Drug Statistics Methodology hierarchy
# (https://www.whocc.no/atc_ddd_index/).
# ---------------------------------------------------------------------------

CURATED_CLASS_PAIRS: list[dict] = [
    # ── Serotonin syndrome: MAOI + serotonergic agents ────────────────────
    dict(
        class_id_1="N06AF", class_name_1="Monoamine oxidase inhibitors, non-selective",
        class_type_1="ATC",
        class_id_2="N06AB", class_name_2="Selective serotonin reuptake inhibitors",
        class_type_2="ATC",
        severity=5,
        description=(
            "Combining any non-selective MAOI (e.g., phenelzine, tranylcypromine) "
            "with any SSRI risks life-threatening serotonin syndrome. "
            "A washout period of ≥14 days between agents is typically required."
        ),
        mechanism="Excess CNS serotonin from concurrent reuptake inhibition + reduced degradation.",
        source="FDA-SSRI class labels §4 (Contraindications)",
    ),
    dict(
        class_id_1="N06AF", class_name_1="Monoamine oxidase inhibitors, non-selective",
        class_type_1="ATC",
        class_id_2="N06AA", class_name_2="Non-selective monoamine reuptake inhibitors",
        class_type_2="ATC",
        severity=5,
        description=(
            "MAOIs combined with tricyclic antidepressants may precipitate "
            "serotonin syndrome or hypertensive crisis. Contraindicated."
        ),
        mechanism="Serotonergic and noradrenergic excess.",
        source="FDA TCA class labels §4",
    ),
    dict(
        class_id_1="N06AF", class_name_1="Monoamine oxidase inhibitors, non-selective",
        class_type_1="ATC",
        class_id_2="N06AX", class_name_2="Other antidepressants (incl. SNRIs)",
        class_type_2="ATC",
        severity=5,
        description=(
            "MAOIs combined with SNRIs / other serotonergic antidepressants "
            "(venlafaxine, duloxetine, mirtazapine, bupropion) risk serotonin "
            "syndrome or hypertensive reaction. Contraindicated."
        ),
        mechanism="Excess monoamine accumulation.",
        source="FDA SNRI class labels §4",
    ),
    dict(
        class_id_1="N06AG", class_name_1="Monoamine oxidase A inhibitors",
        class_type_1="ATC",
        class_id_2="N06AB", class_name_2="Selective serotonin reuptake inhibitors",
        class_type_2="ATC",
        severity=5,
        description=(
            "Reversible MAO-A inhibitors (e.g., moclobemide, selegiline at "
            "antidepressant doses) plus SSRIs carry serotonin syndrome risk. "
            "Avoid combination."
        ),
        mechanism="Serotonin accumulation from dual inhibition.",
        source="FDA selegiline label §4",
    ),

    # ── Bleeding risk: anticoagulants + antiplatelets + NSAIDs ────────────
    dict(
        class_id_1="B01AA", class_name_1="Vitamin K antagonists",
        class_type_1="ATC",
        class_id_2="M01A",  class_name_2="Anti-inflammatory/antirheumatic products, non-steroids",
        class_type_2="ATC",
        severity=5,
        description=(
            "Any vitamin K antagonist (warfarin) combined with any NSAID "
            "substantially increases serious bleeding risk via platelet inhibition "
            "and GI mucosal injury. Prefer acetaminophen for analgesia."
        ),
        mechanism="Platelet COX-1 inhibition + GI injury in an anticoagulated patient.",
        source="FDA warfarin label §7.1",
    ),
    dict(
        class_id_1="B01AF", class_name_1="Direct factor Xa inhibitors",
        class_type_1="ATC",
        class_id_2="M01A",  class_name_2="Anti-inflammatory/antirheumatic products, non-steroids",
        class_type_2="ATC",
        severity=4,
        description=(
            "Direct-acting oral anticoagulants (apixaban, rivaroxaban, edoxaban) "
            "plus NSAIDs increase GI and CNS bleeding risk. Avoid routine "
            "coadministration."
        ),
        mechanism="Additive bleeding risk.",
        source="FDA apixaban / rivaroxaban labels §7",
    ),
    dict(
        class_id_1="B01AC", class_name_1="Platelet aggregation inhibitors (excl. heparin)",
        class_type_1="ATC",
        class_id_2="M01A",  class_name_2="Anti-inflammatory/antirheumatic products, non-steroids",
        class_type_2="ATC",
        severity=3,
        description=(
            "Antiplatelet agents (clopidogrel, ticagrelor, aspirin) plus NSAIDs "
            "increase GI bleeding risk. Monitor closely if combination is necessary."
        ),
        mechanism="Combined platelet inhibition + GI injury.",
        source="FDA clopidogrel label §7",
    ),

    # ── Respiratory depression: opioids + benzodiazepines ─────────────────
    dict(
        class_id_1="N02A",  class_name_1="Opioids",
        class_type_1="ATC",
        class_id_2="N05BA", class_name_2="Benzodiazepine derivatives (anxiolytics)",
        class_type_2="ATC",
        severity=5,
        description=(
            "Combining any opioid with any benzodiazepine substantially "
            "increases the risk of profound sedation, respiratory depression, "
            "coma, and death. Boxed warning on both classes. Reserve for "
            "patients with no alternatives and use lowest effective doses."
        ),
        mechanism="Synergistic CNS and respiratory depression.",
        source="FDA Drug Safety Communication (Aug 2016)",
    ),
    dict(
        class_id_1="N02A",  class_name_1="Opioids",
        class_type_1="ATC",
        class_id_2="N05CD", class_name_2="Benzodiazepine derivatives (hypnotics/sedatives)",
        class_type_2="ATC",
        severity=5,
        description=(
            "Opioids plus hypnotic benzodiazepines (temazepam, triazolam, "
            "midazolam) carry boxed-warning respiratory depression risk. "
            "Avoid combination when possible."
        ),
        mechanism="Synergistic CNS and respiratory depression.",
        source="FDA Drug Safety Communication (Aug 2016)",
    ),

    # ── Hyperkalemia: RAAS blockers + potassium-sparing agents ────────────
    dict(
        class_id_1="C09AA", class_name_1="ACE inhibitors, plain",
        class_type_1="ATC",
        class_id_2="C09CA", class_name_2="Angiotensin II receptor blockers, plain",
        class_type_2="ATC",
        severity=4,
        description=(
            "Dual RAAS blockade (ACE inhibitor + ARB) increases risk of "
            "hyperkalemia, hypotension, and acute kidney injury without "
            "providing clear cardiovascular benefit for most indications."
        ),
        mechanism="Additive aldosterone suppression.",
        source="FDA Drug Safety Communication; ONTARGET trial",
    ),
    dict(
        class_id_1="C09AA", class_name_1="ACE inhibitors, plain",
        class_type_1="ATC",
        class_id_2="C03DA", class_name_2="Aldosterone antagonists",
        class_type_2="ATC",
        severity=3,
        description=(
            "ACE inhibitors plus aldosterone antagonists (spironolactone, "
            "eplerenone) raise serum potassium. Monitor K+ and renal function; "
            "the combination is intentional in heart failure under supervision."
        ),
        mechanism="Reduced aldosterone-mediated K+ excretion.",
        source="FDA spironolactone label §7",
    ),

    # ── Rhabdomyolysis: statins + strong CYP3A4 inhibitors (macrolides) ────
    dict(
        class_id_1="C10AA", class_name_1="HMG CoA reductase inhibitors",
        class_type_1="ATC",
        class_id_2="J01FA", class_name_2="Macrolides",
        class_type_2="ATC",
        severity=4,
        description=(
            "Macrolides (clarithromycin, erythromycin) inhibit CYP3A4, elevating "
            "serum levels of CYP3A4-metabolized statins (simvastatin, lovastatin, "
            "atorvastatin) and increasing rhabdomyolysis risk. Azithromycin is "
            "the safer alternative."
        ),
        mechanism="CYP3A4 inhibition → reduced statin clearance.",
        source="FDA simvastatin label §7 (June 2011 update)",
    ),

    # ── Serotonin syndrome: SSRI/SNRI + triptans ──────────────────────────
    dict(
        class_id_1="N06AB", class_name_1="Selective serotonin reuptake inhibitors",
        class_type_1="ATC",
        class_id_2="N02CC", class_name_2="Selective serotonin (5HT1) agonists (triptans)",
        class_type_2="ATC",
        severity=3,
        description=(
            "SSRIs plus triptans (sumatriptan, rizatriptan, zolmitriptan) carry "
            "a documented but uncommon risk of serotonin syndrome. Counsel "
            "patients; do not automatically avoid."
        ),
        mechanism="Additive central serotonergic activity.",
        source="FDA Drug Safety Communication (July 2006)",
    ),

    # ── QT prolongation: SSRIs + other QT-prolonging agents ───────────────
    dict(
        class_id_1="N06AB", class_name_1="Selective serotonin reuptake inhibitors",
        class_type_1="ATC",
        class_id_2="M01A",  class_name_2="Anti-inflammatory/antirheumatic products, non-steroids",
        class_type_2="ATC",
        severity=3,
        description=(
            "SSRIs plus NSAIDs increase upper GI bleeding risk via platelet "
            "serotonin depletion combined with COX-1 inhibition. Consider "
            "gastroprotection or alternative analgesia in high-risk patients."
        ),
        mechanism="Platelet serotonin depletion + COX-1 inhibition.",
        source="Anglin R et al., Am J Gastroenterol 2014",
    ),

    # ── MAOI + sympathomimetics: hypertensive crisis ──────────────────────
    dict(
        class_id_1="N06AF", class_name_1="Monoamine oxidase inhibitors, non-selective",
        class_type_1="ATC",
        class_id_2="R01BA", class_name_2="Sympathomimetics (nasal decongestants)",
        class_type_2="ATC",
        severity=5,
        description=(
            "Non-selective MAOIs plus indirect sympathomimetics (pseudoephedrine, "
            "phenylephrine) can precipitate hypertensive crisis via unopposed "
            "noradrenaline release. Contraindicated."
        ),
        mechanism="Indirect sympathomimetic release + MAO inhibition.",
        source="FDA phenelzine label §4",
    ),
]


def _get_json(url: str, params: dict[str, Any] | None = None) -> dict | None:
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as exc:
            log.warning(
                "RxClass request failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc,
            )
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _fetch_classes_for_rxcui(
    rxcui: str, rela_source: str, allowed_types: list[str],
) -> list[dict]:
    """Return a list of {class_id, class_name, class_type} for a single drug.

    allowed_types filters RxClass `classType` values (e.g., 'ATC1-4',
    'EPC', 'MoA') so we drop noisy classifications.
    """
    data = _get_json(
        f"{RXCLASS_BASE}/class/byRxcui.json",
        {"rxcui": rxcui, "relaSource": rela_source},
    )
    if not data:
        return []

    rows: list[dict] = []
    group = data.get("rxclassDrugInfoList", {}) or {}
    infos = group.get("rxclassDrugInfo", []) or []
    for info in infos:
        concept = info.get("rxclassMinConceptItem", {}) or {}
        class_id = concept.get("classId")
        class_name = concept.get("className")
        raw_type = concept.get("classType")
        if not class_id or not class_name or not raw_type:
            continue
        if raw_type not in allowed_types:
            continue
        # Normalise ATC subclass type label
        class_type = "ATC" if raw_type.startswith("ATC") else raw_type
        rows.append({
            "class_id": class_id,
            "class_name": class_name,
            "class_type": class_type,
        })
    return rows


def _seed_curated_class_interactions(conn: sqlite3.Connection) -> int:
    inserted = 0
    for row in CURATED_CLASS_PAIRS:
        try:
            cur = conn.execute(
                "INSERT INTO class_interactions "
                "(class_id_1, class_name_1, class_type_1, "
                " class_id_2, class_name_2, class_type_2, "
                " severity, description, mechanism, source) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    row["class_id_1"], row["class_name_1"], row["class_type_1"],
                    row["class_id_2"], row["class_name_2"], row["class_type_2"],
                    row["severity"], row["description"],
                    row.get("mechanism"), row.get("source", "curated_class"),
                ),
            )
            inserted += cur.rowcount
        except sqlite3.IntegrityError:
            continue
    return inserted


def build(db_path: str) -> int:
    """Populate drug_classes from RxClass API + seed curated class_interactions.

    Returns total rows inserted across both tables.
    """
    conn = sqlite3.connect(db_path)
    total_inserted = 0

    try:
        # Pull every (rxcui, generic_name) once. RxClass returns the same
        # classes for SBD/SCD/IN rxcuis of the same substance, so we query
        # per distinct rxcui.
        rows = list(conn.execute(
            "SELECT rxcui, generic_name FROM rxnorm_lookup"
        ))
        log.info("RxClass: querying %d drugs across %d sources",
                 len(rows), len(RELA_SOURCES))

        classes_inserted = 0
        for i, (rxcui, generic) in enumerate(rows):
            for rela_source, allowed_types in RELA_SOURCES:
                classes = _fetch_classes_for_rxcui(rxcui, rela_source, allowed_types)
                for cls in classes:
                    try:
                        cur = conn.execute(
                            "INSERT OR IGNORE INTO drug_classes "
                            "(rxcui, drug_name, class_id, class_name, "
                            " class_type, rela_source, source) "
                            "VALUES (?, ?, ?, ?, ?, ?, 'rxclass')",
                            (rxcui, generic,
                             cls["class_id"], cls["class_name"],
                             cls["class_type"], rela_source),
                        )
                        classes_inserted += cur.rowcount
                    except sqlite3.IntegrityError:
                        pass
                time.sleep(RATE_LIMIT_SLEEP)

            if (i + 1) % 50 == 0:
                log.info("RxClass: processed %d / %d drugs", i + 1, len(rows))
                conn.commit()

        conn.commit()
        log.info("RxClass: inserted %d rows into drug_classes", classes_inserted)
        total_inserted += classes_inserted

        # Curated class_interactions
        pairs_inserted = _seed_curated_class_interactions(conn)
        conn.commit()
        log.info("RxClass: seeded %d curated class_interactions rows", pairs_inserted)
        total_inserted += pairs_inserted

    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    return total_inserted
