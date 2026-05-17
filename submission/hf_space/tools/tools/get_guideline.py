"""Retrieve USPSTF preventive-care guidelines matching patient demographics.

Reads against the canonical schema in kb/schema.sql:
  id, recommendation_id, title, grade,
  population_age_min, population_age_max, population_sex,
  description, clinical_url, source

The external contract the LLM receives (fields in each recommendation dict)
is kept stable: {title, grade, description, population, citation}.
- ``population`` is synthesized from the three population_* columns
  (e.g., "Men 65+", "Women 50-74", "Adults 40-75").
- ``citation`` maps to the ``source`` column (e.g., "USPSTF (2024 curated snapshot)").
"""

from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"


def _synthesize_population(age_min: int | None, age_max: int | None, sex: str | None) -> str:
    """Turn the three population_* columns into a human-readable label."""
    s = (sex or "all").strip().lower()
    # Choose a noun that matches sex, adjusting for pediatric ranges.
    pediatric = age_max is not None and age_max <= 18
    if s == "male":
        noun = "Boys" if pediatric else "Men"
    elif s == "female":
        noun = "Girls" if pediatric else "Women"
    else:
        noun = "Children" if pediatric else "Adults"

    if age_min is not None and age_max is not None:
        return f"{noun} {age_min}-{age_max}"
    if age_min is not None:
        return f"{noun} {age_min}+"
    if age_max is not None:
        return f"{noun} under {age_max + 1}"
    return f"All {noun.lower()}"


def _row_to_rec(row: sqlite3.Row) -> dict:
    return {
        "title": row["title"],
        "grade": row["grade"],
        "description": row["description"],
        "population": _synthesize_population(
            row["population_age_min"], row["population_age_max"], row["population_sex"]
        ),
        "citation": row["source"] or "USPSTF",
    }


def get_guideline(
    age: int,
    sex: str,
    conditions: list[str] | None = None,
    db_path: str = DEFAULT_DB,
) -> dict:
    """Query USPSTF guidelines that apply to the given patient profile.

    Matches by age range (inclusive; NULL means "any") and sex (exact match
    or 'all'). When ``conditions`` is provided, also returns any additional
    Grade A/B recommendations whose title or description substring-matches
    any provided condition (case-insensitive).
    """
    try:
        age = int(age)
    except (TypeError, ValueError):
        return {"error": "Age must be an integer"}

    sex = "" if sex is None else str(sex)
    if not sex.strip() or sex.strip().lower() not in ("male", "female", "m", "f"):
        return {"error": "Sex must be one of: male, female, m, f"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    sex_normalized = sex.strip().lower()
    if sex_normalized == "m":
        sex_normalized = "male"
    elif sex_normalized == "f":
        sex_normalized = "female"

    conditions = [str(c).strip().lower() for c in (conditions or []) if c is not None and str(c).strip()]

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # Primary match: age + sex. NULL age bounds mean "any age" on that
        # side. Excludes risk-only ACIP rows — those require an explicit
        # condition match in phase 2 below.
        has_risk_only = bool(conn.execute(
            "SELECT 1 FROM pragma_table_info('guidelines') WHERE name = 'risk_only'"
        ).fetchone())
        risk_only_filter = (
            " AND (risk_only IS NULL OR risk_only = 0)" if has_risk_only else ""
        )
        cur.execute(
            f"""
            SELECT title, grade, description,
                   population_age_min, population_age_max, population_sex,
                   source
            FROM guidelines
            WHERE grade IN ('A', 'B', 'I')
              AND (population_age_min IS NULL OR population_age_min <= ?)
              AND (population_age_max IS NULL OR population_age_max >= ?)
              AND (LOWER(COALESCE(population_sex, 'all')) = ?
                   OR LOWER(COALESCE(population_sex, 'all')) = 'all')
              {risk_only_filter}
            ORDER BY grade, title
            """,
            (age, age, sex_normalized),
        )
        recommendations = [_row_to_rec(row) for row in cur.fetchall()]
        seen_titles = {r["title"] for r in recommendations}

        # Secondary match: condition keywords substring-matched against
        # title or description.  Only returns rows not already in the
        # demographic match so we don't duplicate.
        if conditions:
            like_clause = " OR ".join(
                ["LOWER(title) LIKE ? OR LOWER(description) LIKE ?"] * len(conditions)
            )
            params: list = []
            for term in conditions:
                pat = f"%{term}%"
                params.extend([pat, pat])
            cur.execute(
                f"""
                SELECT title, grade, description,
                       population_age_min, population_age_max, population_sex,
                       source
                FROM guidelines
                WHERE grade IN ('A', 'B', 'I')
                  AND ({like_clause})
                ORDER BY grade, title
                """,
                params,
            )
            for row in cur.fetchall():
                if row["title"] in seen_titles:
                    continue
                recommendations.append(_row_to_rec(row))
                seen_titles.add(row["title"])

        conn.close()
        return {"recommendations": recommendations}
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
