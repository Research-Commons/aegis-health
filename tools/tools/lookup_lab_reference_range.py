"""Look up adult/pediatric reference ranges for common lab tests."""
from __future__ import annotations

import sqlite3
from pathlib import Path

DEFAULT_DB = "kb/output/aegis_kb.sqlite"

_PEDIATRIC_AGE = 18


def lookup_lab_reference_range(
    test_name: str,
    age: int | None = None,
    sex: str | None = None,
    db_path: str = DEFAULT_DB,
) -> dict:
    """Return reference range for a lab test, age/sex-aware when available.

    Returns a dict with keys: test_name, ref_low, ref_high, units,
    population, source, citation. Returns {"error": "..."} if not found.
    Never raises.

    Pregnancy is a separate caller-driven path: use _lookup_pregnancy_range
    when the caller has already determined pregnancy state. This tool's
    signature deliberately omits a pregnancy parameter (D-08).

    SAFETY-03: on every success path the returned dict carries `source` and
    `citation` so the system-prompt rule "model never emits a range without
    an explicit source" is mechanically supported.
    """
    test_name = "" if test_name is None else str(test_name)
    if not test_name.strip():
        return {"error": "Empty test_name provided"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    population = _classify_population(age, sex)

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()

        # Pediatric requests query the pediatric table first; fall back to
        # adult-default if no pediatric row exists for the analyte.
        if population == "pediatric":
            cur.execute(
                """
                SELECT test_name, ref_low, ref_high, units, citation
                FROM reference_ranges_pediatric
                WHERE LOWER(test_name) = LOWER(?)
                  AND (age_low IS NULL OR ? IS NULL OR age_low <= ?)
                  AND (age_high IS NULL OR ? IS NULL OR age_high >= ?)
                  AND (sex = 'all' OR ? IS NULL OR sex = LOWER(?))
                LIMIT 1
                """,
                (test_name.strip(), age, age, age, age, sex, sex or ""),
            )
            row = cur.fetchone()
            if row:
                conn.close()
                return {
                    "test_name": row["test_name"],
                    "ref_low": row["ref_low"],
                    "ref_high": row["ref_high"],
                    "units": row["units"],
                    "population": "pediatric",
                    "source": "kb",
                    "citation": row["citation"],
                }
            # Pediatric miss — fall through to adult-default below. Rebind the
            # population so the adult fallback query is satisfiable (otherwise
            # the IN (?, 'all') filter would exclude every adult-population row
            # and the fallback would always return an error).
            population = "adult"

        cur.execute(
            """
            SELECT test_name, ref_low, ref_high, units, population, citation
            FROM lab_reference_ranges
            WHERE LOWER(test_name) = LOWER(?)
              AND population IN (?, 'all')
            ORDER BY CASE population WHEN ? THEN 0 ELSE 1 END
            LIMIT 1
            """,
            (test_name.strip(), population, population),
        )
        row = cur.fetchone()
        conn.close()

        if not row:
            return {"error": f"No reference range for '{test_name}' in KB"}

        return {
            "test_name": row["test_name"],
            "ref_low": row["ref_low"],
            "ref_high": row["ref_high"],
            "units": row["units"],
            "population": row["population"],
            "source": "kb",
            "citation": row["citation"],
        }
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}


def _classify_population(age: int | None, sex: str | None) -> str:
    """Map (age, sex) to one of pediatric / adult_female / adult_male / adult.

    Age takes priority over sex.
    """
    if age is not None and age < _PEDIATRIC_AGE:
        return "pediatric"
    sx = (sex or "").strip().lower()
    if sx in ("f", "female"):
        return "adult_female"
    if sx in ("m", "male"):
        return "adult_male"
    return "adult"


def _lookup_pregnancy_range(
    test_name: str,
    trimester: int | None = None,
    db_path: str = DEFAULT_DB,
) -> dict:
    """Caller-driven pregnancy KB query (D-08 separate path).

    Not exposed as a tool — used by Kotlin RangeEvaluator and by eval fixtures
    when the report carries pregnancy markers.
    """
    test_name = "" if test_name is None else str(test_name)
    if not test_name.strip():
        return {"error": "Empty test_name provided"}
    if trimester is not None and trimester not in (1, 2, 3):
        return {"error": f"Invalid trimester '{trimester}'; expected 1, 2, or 3"}

    db = Path(db_path)
    if not db.exists():
        return {"error": f"Knowledge base not found at {db_path}"}

    try:
        conn = sqlite3.connect(str(db))
        conn.row_factory = sqlite3.Row
        cur = conn.cursor()
        cur.execute(
            """
            SELECT test_name, trimester, ref_low, ref_high, units, citation
            FROM reference_ranges_pregnancy
            WHERE LOWER(test_name) = LOWER(?)
              AND (trimester = ? OR trimester IS NULL)
            ORDER BY CASE WHEN trimester = ? THEN 0 ELSE 1 END
            LIMIT 1
            """,
            (test_name.strip(), trimester, trimester),
        )
        row = cur.fetchone()
        conn.close()
        if not row:
            return {"error": f"No pregnancy reference range for '{test_name}' in KB"}
        return {
            "test_name": row["test_name"],
            "trimester": row["trimester"],
            "ref_low": row["ref_low"],
            "ref_high": row["ref_high"],
            "units": row["units"],
            "population": "pregnant",
            "source": "kb",
            "citation": row["citation"],
        }
    except sqlite3.Error as e:
        return {"error": f"Database error: {e}"}
