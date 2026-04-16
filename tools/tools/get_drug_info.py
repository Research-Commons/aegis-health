"""Retrieve full drug record from the knowledge base by RxCUI."""

from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"


def get_drug_info(rxcui: str, db_path: str = DEFAULT_DB) -> dict:
    """Look up a drug by its RxCUI identifier.

    Returns a dict with keys: name, drug_class, category, warnings_summary, citation.
    """
    if not rxcui or not rxcui.strip():
        return {"error": "Empty RxCUI provided"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute(
            """
            SELECT name, drug_class, category, warnings_summary, citation
            FROM drugs
            WHERE rxcui = ?
            LIMIT 1
            """,
            (rxcui.strip(),),
        )
        row = cur.fetchone()
        conn.close()

        if not row:
            return {"error": f"Drug with RxCUI '{rxcui}' not found"}

        return {
            "name": row["name"],
            "drug_class": row["drug_class"],
            "category": row["category"],
            "warnings_summary": row["warnings_summary"],
            "citation": row["citation"],
        }
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
