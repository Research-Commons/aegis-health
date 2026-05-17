"""Normalize a drug name to its generic equivalent using the RxNorm lookup table."""

from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"

# Common misspelling corrections applied before querying
_MISSPELLINGS: dict[str, str] = {
    "tylenol": "tylenol",
    "tylanol": "tylenol",
    "tylnol": "tylenol",
    "advil": "advil",
    "addvil": "advil",
    "ibuprofin": "ibuprofen",
    "ibuprophen": "ibuprofen",
    "aspirn": "aspirin",
    "asprin": "aspirin",
    "acetaminophin": "acetaminophen",
    "acetamenophen": "acetaminophen",
    "acetominophen": "acetaminophen",
    "amoxacillin": "amoxicillin",
    "amoxicilin": "amoxicillin",
    "metforman": "metformin",
    "lisinipril": "lisinopril",
    "lisiopril": "lisinopril",
    "atorvastain": "atorvastatin",
    "omeprazol": "omeprazole",
    "losartan potassium": "losartan",
    "allegra": "fexofenadine",
    "claritin": "loratadine",
    "zyrtec": "cetirizine",
    "mucinex": "guaifenesin",
    "benadryl": "diphenhydramine",
    "pepcid": "famotidine",
    "prilosec": "omeprazole",
    "nexium": "esomeprazole",
    "lipitor": "atorvastatin",
    "zocor": "simvastatin",
    "crestor": "rosuvastatin",
    "metoprolol succinate": "metoprolol",
    "metoprolol tartrate": "metoprolol",
}


def _correct_spelling(name: str) -> str:
    name = "" if name is None else str(name)
    lowered = name.strip().lower()
    return _MISSPELLINGS.get(lowered, lowered)


def normalize_drug(name: str, db_path: str = DEFAULT_DB) -> dict:
    """Resolve a drug name (brand or misspelled) to its canonical generic form.

    Returns a dict with keys: generic_name, rxcui, category.
    On lookup failure returns an error dict.
    """
    name = "" if name is None else str(name)
    if not name.strip():
        return {"error": "Empty drug name provided"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    corrected = _correct_spelling(name)

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # Try exact match (case-insensitive) on brand_name or generic_name
        cur.execute(
            """
            SELECT generic_name, rxcui, category
            FROM rxnorm_lookup
            WHERE LOWER(brand_name) = ? OR LOWER(generic_name) = ?
            LIMIT 1
            """,
            (corrected, corrected),
        )
        row = cur.fetchone()

        if row:
            return {
                "generic_name": row["generic_name"],
                "rxcui": row["rxcui"],
                "category": row["category"],
            }

        # Fallback: LIKE prefix search
        cur.execute(
            """
            SELECT generic_name, rxcui, category
            FROM rxnorm_lookup
            WHERE LOWER(brand_name) LIKE ? OR LOWER(generic_name) LIKE ?
            LIMIT 1
            """,
            (f"{corrected}%", f"{corrected}%"),
        )
        row = cur.fetchone()

        if row:
            return {
                "generic_name": row["generic_name"],
                "rxcui": row["rxcui"],
                "category": row["category"],
            }

        conn.close()
        return {"error": f"Drug '{name}' not found in knowledge base"}
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
