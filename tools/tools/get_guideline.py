"""Retrieve USPSTF preventive-care guidelines matching patient demographics."""

from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"


def get_guideline(
    age: int,
    sex: str,
    conditions: list[str] | None = None,
    db_path: str = DEFAULT_DB,
) -> dict:
    """Query USPSTF guidelines that apply to the given patient profile.

    Filters by age range, sex, and optionally by relevant conditions.
    Only returns grade A and B recommendations.

    Returns a dict with key: recommendations (list of recommendation dicts).
    """
    if not sex or sex.strip().lower() not in ("male", "female", "m", "f"):
        return {"error": "Sex must be one of: male, female, m, f"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    sex_normalized = sex.strip().lower()
    if sex_normalized == "m":
        sex_normalized = "male"
    elif sex_normalized == "f":
        sex_normalized = "female"

    conditions = conditions or []

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # Retrieve guidelines where patient age falls within [min_age, max_age]
        # and sex matches (or guideline applies to "all")
        cur.execute(
            """
            SELECT title, grade, description, population, citation
            FROM guidelines
            WHERE grade IN ('A', 'B')
              AND min_age <= ?
              AND max_age >= ?
              AND (LOWER(sex) = ? OR LOWER(sex) = 'all')
            ORDER BY grade, title
            """,
            (age, age, sex_normalized),
        )
        rows = cur.fetchall()

        recommendations = [
            {
                "title": row["title"],
                "grade": row["grade"],
                "description": row["description"],
                "population": row["population"],
                "citation": row["citation"],
            }
            for row in rows
        ]

        # If conditions provided, also fetch condition-specific guidelines
        if conditions:
            placeholders = ",".join("?" for _ in conditions)
            cur.execute(
                f"""
                SELECT title, grade, description, population, citation
                FROM guidelines
                WHERE grade IN ('A', 'B')
                  AND LOWER(condition) IN ({placeholders})
                  AND title NOT IN (
                      SELECT title FROM guidelines
                      WHERE grade IN ('A', 'B')
                        AND min_age <= ? AND max_age >= ?
                        AND (LOWER(sex) = ? OR LOWER(sex) = 'all')
                  )
                ORDER BY grade, title
                """,
                [c.strip().lower() for c in conditions] + [age, age, sex_normalized],
            )
            for row in cur.fetchall():
                recommendations.append({
                    "title": row["title"],
                    "grade": row["grade"],
                    "description": row["description"],
                    "population": row["population"],
                    "citation": row["citation"],
                })

        conn.close()

        return {"recommendations": recommendations}
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
