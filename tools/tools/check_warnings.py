"""Core interaction-checking and contraindication engine.

Evaluates a list of drugs for:
  - drug x drug interactions
  - drug x condition contraindications
  - special population risks (elderly, pregnancy, pediatric)

Auto-defers to a healthcare professional when safety thresholds are crossed.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

from tools.tools.schemas import AegisResponse, Citation, Flag

DEFAULT_DB = "kb/output/aegis_kb.sqlite"

_DEFER_THRESHOLD_DRUG_COUNT = 5
_ELDERLY_AGE = 65
_PEDIATRIC_AGE = 12

# Suffixes stripped when normalising supplement names for fuzzy lookup.
_SUPPLEMENT_SUFFIXES = (
    " extract", " preparation", " root extract", " seed meal",
    " berry", " root", " seed", " leaf", " oil",
)


def _normalise_supplement_name(name: str) -> str:
    """Lowercase + strip common botanical suffixes for supplements table matching."""
    n = name.lower().strip()
    # remove trailing punctuation variants ("st. john's wort" → "st john's wort")
    n = n.replace(".", "")
    for suffix in _SUPPLEMENT_SUFFIXES:
        if n.endswith(suffix):
            n = n[: -len(suffix)].strip()
    return n


def _build_error_response(msg: str) -> dict:
    return AegisResponse(
        flags=[],
        citations=[],
        confidence=0.0,
        defer_to_professional=True,
        explanation=msg,
    ).model_dump()


def check_warnings(
    drug_list: list[str],
    age: int | None = None,
    conditions: list[str] | None = None,
    db_path: str = DEFAULT_DB,
) -> dict:
    """Check a set of drugs for interactions, contraindications, and population risks.

    Auto-defers when: controlled substances present, pregnancy, pediatric + Rx,
    unknown drugs found, or 5+ drugs in the list.
    """
    if not drug_list:
        return _build_error_response("No drugs provided for analysis")

    db = Path(db_path)
    if not db.exists():
        return _build_error_response(f"Knowledge base not found at {db_path}")

    drug_list = [str(drug) for drug in drug_list if drug is not None]
    conditions = [str(condition) for condition in (conditions or []) if condition is not None]
    flags: list[Flag] = []
    citations: list[Citation] = []
    defer = False
    unknown_drugs: list[str] = []

    # Auto-defer: too many drugs
    if len(drug_list) >= _DEFER_THRESHOLD_DRUG_COUNT:
        defer = True
        flags.append(Flag(
            severity=4,
            description=(
                f"Polypharmacy detected: {len(drug_list)} drugs. "
                "Complex regimens require professional review."
            ),
            citation="Clinical best practice",
        ))

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # --- Resolve all drugs and gather metadata ---
        drug_records: dict[str, dict] = {}
        for drug_name in drug_list:
            cleaned = drug_name.strip().lower()
            if not cleaned:
                continue
            cur.execute(
                """
                SELECT d.drug_name AS name, d.rxcui,
                       '' AS drug_class,
                       COALESCE(r.category, 'Rx') AS category
                FROM drugs d
                LEFT JOIN rxnorm_lookup r ON r.rxcui = d.rxcui
                WHERE LOWER(d.drug_name) = ?
                LIMIT 1
                """,
                (cleaned,),
            )
            row = cur.fetchone()
            if row:
                drug_records[cleaned] = dict(row)
            else:
                # Try rxnorm_lookup for brand names
                cur.execute(
                    """
                    SELECT generic_name, rxcui,
                           COALESCE(category, 'Rx') AS category
                    FROM rxnorm_lookup
                    WHERE LOWER(brand_name) = ? OR LOWER(generic_name) = ?
                    LIMIT 1
                    """,
                    (cleaned, cleaned),
                )
                alt = cur.fetchone()
                if alt:
                    drug_records[cleaned] = {
                        "name": alt["generic_name"],
                        "rxcui": alt["rxcui"],
                        "category": alt["category"],
                        "drug_class": "",
                    }
                else:
                    unknown_drugs.append(drug_name)

        # Auto-defer: unknown drugs
        if unknown_drugs:
            defer = True
            flags.append(Flag(
                severity=3,
                description=(
                    f"Unknown drug(s): {', '.join(unknown_drugs)}. "
                    "Cannot verify safety without identification."
                ),
                citation="Aegis safety policy",
            ))

        # Auto-defer: controlled substances
        for dname, rec in drug_records.items():
            if rec.get("category", "").lower() == "controlled":
                defer = True
                flags.append(Flag(
                    severity=4,
                    description=(
                        f"'{rec['name']}' is a controlled substance. "
                        "Use must be supervised by a prescriber."
                    ),
                    citation="DEA Controlled Substances Act",
                ))

        # --- Special population checks ---
        is_pregnant = any(
            c.lower() in ("pregnancy", "pregnant") for c in conditions
        )
        is_elderly = age is not None and age >= _ELDERLY_AGE
        is_pediatric = age is not None and age < _PEDIATRIC_AGE

        if is_pregnant:
            defer = True
            flags.append(Flag(
                severity=5,
                description=(
                    "Pregnancy detected. All medication use during pregnancy "
                    "requires direct medical supervision."
                ),
                citation="FDA Pregnancy and Lactation Labeling Rule",
            ))

        if is_pediatric:
            for dname, rec in drug_records.items():
                if rec.get("category", "").lower() == "rx":
                    defer = True
                    flags.append(Flag(
                        severity=4,
                        description=(
                            f"Pediatric patient (age {age}) with prescription drug "
                            f"'{rec['name']}'. Pediatric dosing requires professional guidance."
                        ),
                        citation="AAP Pediatric Prescribing Guidelines",
                    ))

        if is_elderly:
            flags.append(Flag(
                severity=3,
                description=(
                    f"Patient age {age} is ≥65. Elderly patients may need dose "
                    "adjustments and have increased sensitivity to drug effects."
                ),
                citation="AGS Beers Criteria",
            ))
            citations.append(Citation(
                source="AGS Beers Criteria",
                text="American Geriatrics Society Beers Criteria for Potentially Inappropriate Medication Use in Older Adults",
            ))

        # --- Drug x Drug interactions ---
        resolved_names = list(drug_records.keys())
        pair_had_direct: set[frozenset[str]] = set()
        for i, drug_a in enumerate(resolved_names):
            for drug_b in resolved_names[i + 1:]:
                rxcui_a = drug_records[drug_a].get("rxcui", "")
                rxcui_b = drug_records[drug_b].get("rxcui", "")

                cur.execute(
                    """
                    SELECT severity, description, source AS citation
                    FROM interactions
                    WHERE (LOWER(drug_name_1) = ? AND LOWER(drug_name_2) = ?)
                       OR (LOWER(drug_name_1) = ? AND LOWER(drug_name_2) = ?)
                       OR (drug_rxcui_1 = ? AND drug_rxcui_2 = ?)
                       OR (drug_rxcui_1 = ? AND drug_rxcui_2 = ?)
                    """,
                    (
                        drug_a, drug_b,
                        drug_b, drug_a,
                        rxcui_a, rxcui_b,
                        rxcui_b, rxcui_a,
                    ),
                )
                direct_rows = cur.fetchall()
                if direct_rows:
                    pair_had_direct.add(frozenset({drug_a, drug_b}))
                for row in direct_rows:
                    sev = min(max(int(row["severity"]), 1), 5)
                    flags.append(Flag(
                        severity=sev,
                        description=row["description"],
                        citation=row["citation"],
                    ))
                    if sev >= 4:
                        defer = True

        # --- Class-level interactions (fills gaps in pairwise table) ---
        # Fetch class memberships per drug (one query each) then match any
        # unchecked pair against curated class_interactions rules.
        classes_by_drug: dict[str, set[str]] = {}
        try:
            for dname, rec in drug_records.items():
                rxcui = rec.get("rxcui", "")
                if not rxcui:
                    continue
                cur.execute(
                    "SELECT class_id FROM drug_classes WHERE rxcui = ?",
                    (rxcui,),
                )
                classes_by_drug[dname] = {r["class_id"] for r in cur.fetchall()}
        except sqlite3.OperationalError:
            classes_by_drug = {}

        if classes_by_drug:
            for i, drug_a in enumerate(resolved_names):
                for drug_b in resolved_names[i + 1:]:
                    if frozenset({drug_a, drug_b}) in pair_had_direct:
                        continue
                    class_ids_a = classes_by_drug.get(drug_a, set())
                    class_ids_b = classes_by_drug.get(drug_b, set())
                    if not class_ids_a or not class_ids_b:
                        continue
                    ph_a = ",".join("?" * len(class_ids_a))
                    ph_b = ",".join("?" * len(class_ids_b))
                    try:
                        cur.execute(
                            f"""
                            SELECT DISTINCT severity, description, source AS citation
                            FROM class_interactions
                            WHERE (class_id_1 IN ({ph_a}) AND class_id_2 IN ({ph_b}))
                               OR (class_id_2 IN ({ph_a}) AND class_id_1 IN ({ph_b}))
                            """,
                            (*class_ids_a, *class_ids_b, *class_ids_a, *class_ids_b),
                        )
                        class_rows = cur.fetchall()
                    except sqlite3.OperationalError:
                        class_rows = []
                    seen_descs: set[str] = set()
                    name_a = drug_records[drug_a]["name"]
                    name_b = drug_records[drug_b]["name"]
                    for row in class_rows:
                        desc = row["description"]
                        if desc in seen_descs:
                            continue
                        seen_descs.add(desc)
                        sev = min(max(int(row["severity"]), 1), 5)
                        flags.append(Flag(
                            severity=sev,
                            description=f"{name_a} + {name_b}: {desc}",
                            citation=row["citation"],
                        ))
                        if sev >= 4:
                            defer = True

        # --- Drug x Condition contraindications ---
        # Historical KBs had a dedicated `contraindications` table; current
        # builds fold these into `warnings WHERE warning_type='contraindication'`.
        # Wrap each query so a missing table doesn't crash the whole function
        # (matches the defensive pattern used for supplements below).
        for dname, rec in drug_records.items():
            for cond in conditions:
                cond_lower = cond.strip().lower()
                try:
                    cur.execute(
                        """
                        SELECT severity, description, citation
                        FROM contraindications
                        WHERE (LOWER(drug_name) = ? OR rxcui = ?)
                          AND LOWER(condition) = ?
                        """,
                        (dname, rec.get("rxcui", ""), cond_lower),
                    )
                    direct_rows = cur.fetchall()
                except sqlite3.OperationalError:
                    direct_rows = []
                for row in direct_rows:
                    sev = min(max(int(row["severity"]), 1), 5)
                    flags.append(Flag(
                        severity=sev,
                        description=row["description"],
                        citation=row["citation"],
                    ))
                    if sev >= 4:
                        defer = True

                # Fallback path: warnings table with warning_type='contraindication'.
                try:
                    cur.execute(
                        """
                        SELECT severity, description, source AS citation
                        FROM warnings
                        WHERE warning_type = 'contraindication'
                          AND (LOWER(drug_name) = ? OR drug_rxcui = ?)
                          AND LOWER(COALESCE(population, '')) LIKE ?
                        """,
                        (dname, rec.get("rxcui", ""), f"%{cond_lower}%"),
                    )
                    warn_rows = cur.fetchall()
                except sqlite3.OperationalError:
                    warn_rows = []
                for row in warn_rows:
                    sev_val = row["severity"] if row["severity"] is not None else 3
                    sev = min(max(int(sev_val), 1), 5)
                    flags.append(Flag(
                        severity=sev,
                        description=row["description"],
                        citation=row["citation"],
                    ))
                    if sev >= 4:
                        defer = True

        # --- Supplement x Drug interactions ---
        # Check every input name against the supplements table, which stores
        # botanical/dietary supplement interactions keyed by common name.
        # Matching is Python-side to handle name variants ("st john's wort"
        # vs "St. John's Wort") without requiring exact SQL equality.
        all_input_norms = {
            _normalise_supplement_name(n): n.strip().lower()
            for n in drug_list if n.strip()
        }
        try:
            cur.execute(
                "SELECT supplement_name, interacting_drug, severity, description, source "
                "FROM supplements"
            )
            suppl_rows = cur.fetchall()
        except sqlite3.OperationalError:
            suppl_rows = []
        for row in suppl_rows:
            supp_norm = _normalise_supplement_name(row["supplement_name"])
            if supp_norm not in all_input_norms:
                continue
            supp_input_lower = all_input_norms[supp_norm]
            # Does the interacting_drug match any *other* drug in the list?
            interacting_lower = row["interacting_drug"].strip().lower()
            matched = any(
                interacting_lower in n or n in interacting_lower
                for n in all_input_norms.values()
                if n != supp_input_lower
            ) or any(
                interacting_lower in rec["name"].lower()
                for rec in drug_records.values()
            )
            if not matched:
                continue
            sev = min(max(int(row["severity"]), 1), 5)
            flags.append(Flag(
                severity=sev,
                description=row["description"],
                citation=row["source"],
            ))
            if sev >= 4:
                defer = True

        conn.close()

        # Compute confidence
        if unknown_drugs:
            confidence = 0.3
        elif flags:
            max_sev = max(f.severity for f in flags)
            confidence = max(0.5, 1.0 - (max_sev * 0.1))
        else:
            confidence = 0.95

        explanation_parts: list[str] = []
        if not flags:
            explanation_parts.append(
                "No known interactions or contraindications found for the provided drugs."
            )
        else:
            explanation_parts.append(
                f"Found {len(flags)} warning(s) across the provided drug list."
            )
        if defer:
            explanation_parts.append(
                "This combination should be reviewed by a healthcare professional."
            )

        return AegisResponse(
            flags=flags,
            citations=citations,
            confidence=confidence,
            defer_to_professional=defer,
            explanation=" ".join(explanation_parts),
        ).model_dump()

    except sqlite3.Error as e:
        return _build_error_response(f"Database error: {e}")
