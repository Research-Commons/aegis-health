"""Decompose a combination product into its individual active ingredients."""

from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"


def decompose_product(product_name: str, db_path: str = DEFAULT_DB) -> dict:
    """Break a combination product into its component active ingredients.

    Example: NyQuil -> [acetaminophen, dextromethorphan, doxylamine]

    Returns a dict with keys: product, ingredients (list of {name, rxcui}), citation.
    """
    if not product_name or not product_name.strip():
        return {"error": "Empty product name provided"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    cleaned = product_name.strip().lower()

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        cur.execute(
            """
            SELECT ingredient_name, rxcui
            FROM drug_ingredients
            WHERE LOWER(product_name) = ?
            ORDER BY ingredient_name
            """,
            (cleaned,),
        )
        rows = cur.fetchall()

        if not rows:
            # Fallback: LIKE prefix match
            cur.execute(
                """
                SELECT ingredient_name, rxcui, product_name
                FROM drug_ingredients
                WHERE LOWER(product_name) LIKE ?
                ORDER BY ingredient_name
                """,
                (f"%{cleaned}%",),
            )
            rows = cur.fetchall()

        conn.close()

        if not rows:
            return {"error": f"Product '{product_name}' not found in knowledge base"}

        ingredients = [
            {"name": row["ingredient_name"], "rxcui": row["rxcui"]}
            for row in rows
        ]

        return {
            "product": product_name.strip(),
            "ingredients": ingredients,
            "citation": "RxNorm – National Library of Medicine",
        }
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
