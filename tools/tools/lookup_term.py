"""Look up medical terminology and return plain-language definitions."""

from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"


def lookup_term(term: str, db_path: str = DEFAULT_DB) -> dict:
    """Search the medical terms table for a plain-language definition.

    Uses exact match first, then fuzzy LIKE-based matching.
    Returns a dict with keys: term, plain_language_definition, citation.
    """
    if not term or not term.strip():
        return {"error": "Empty term provided"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    cleaned = term.strip().lower()

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # Exact match
        cur.execute(
            """
            SELECT term, plain_language_definition, citation
            FROM terms
            WHERE LOWER(term) = ?
            LIMIT 1
            """,
            (cleaned,),
        )
        row = cur.fetchone()

        if not row:
            # Fuzzy match: contains the search term
            cur.execute(
                """
                SELECT term, plain_language_definition, citation
                FROM terms
                WHERE LOWER(term) LIKE ?
                LIMIT 1
                """,
                (f"%{cleaned}%",),
            )
            row = cur.fetchone()

        conn.close()

        if not row:
            return {"error": f"Term '{term}' not found in knowledge base"}

        return {
            "term": row["term"],
            "plain_language_definition": row["plain_language_definition"],
            "citation": row["citation"],
        }
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
