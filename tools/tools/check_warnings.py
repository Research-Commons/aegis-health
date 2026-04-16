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

    conditions = conditions or []
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
                SELECT name, rxcui, category, drug_class
                FROM drugs
                WHERE LOWER(name) = ?
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
                    SELECT generic_name, rxcui, category
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
        for i, drug_a in enumerate(resolved_names):
            for drug_b in resolved_names[i + 1:]:
                rxcui_a = drug_records[drug_a].get("rxcui", "")
                rxcui_b = drug_records[drug_b].get("rxcui", "")

                cur.execute(
                    """
                    SELECT severity, description, citation
                    FROM interactions
                    WHERE (LOWER(drug_a) = ? AND LOWER(drug_b) = ?)
                       OR (LOWER(drug_a) = ? AND LOWER(drug_b) = ?)
                       OR (rxcui_a = ? AND rxcui_b = ?)
                       OR (rxcui_a = ? AND rxcui_b = ?)
                    """,
                    (
                        drug_a, drug_b,
                        drug_b, drug_a,
                        rxcui_a, rxcui_b,
                        rxcui_b, rxcui_a,
                    ),
                )
                for row in cur.fetchall():
                    sev = min(max(int(row["severity"]), 1), 5)
                    flags.append(Flag(
                        severity=sev,
                        description=row["description"],
                        citation=row["citation"],
                    ))
                    if sev >= 4:
                        defer = True

        # --- Drug x Condition contraindications ---
        for dname, rec in drug_records.items():
            for cond in conditions:
                cond_lower = cond.strip().lower()
                cur.execute(
                    """
                    SELECT severity, description, citation
                    FROM contraindications
                    WHERE (LOWER(drug_name) = ? OR rxcui = ?)
                      AND LOWER(condition) = ?
                    """,
                    (dname, rec.get("rxcui", ""), cond_lower),
                )
                for row in cur.fetchall():
                    sev = min(max(int(row["severity"]), 1), 5)
                    flags.append(Flag(
                        severity=sev,
                        description=row["description"],
                        citation=row["citation"],
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
