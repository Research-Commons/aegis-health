"""Integrity validation for the Aegis Health knowledge base.

Checks:
  1. Foreign-key consistency across all tables
  2. No NULL rxcui values in the drugs table
  3. Severity scores in range [1, 5]
  4. All interactions reference valid drug rxcuis
  5. Reports issues as warnings (recoverable) or errors (data-integrity failures)

Usage:
    python -m kb.validate                   # default path
    python -m kb.validate --db path/to.db
"""
from __future__ import annotations

import argparse
import logging
import os
import sqlite3
import sys

log = logging.getLogger(__name__)

DEFAULT_DB = os.path.join(os.path.dirname(__file__), "..", "output", "aegis_kb.sqlite")


class ValidationResult:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    @property
    def ok(self) -> bool:
        return len(self.errors) == 0

    def error(self, msg: str) -> None:
        self.errors.append(msg)
        log.error("ERROR: %s", msg)

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)
        log.warning("WARN:  %s", msg)

    def info(self, msg: str) -> None:
        log.info("OK:    %s", msg)

    def summary(self) -> str:
        lines = [
            f"Validation: {len(self.errors)} error(s), {len(self.warnings)} warning(s)",
        ]
        if self.errors:
            lines.append("Errors:")
            for e in self.errors:
                lines.append(f"  - {e}")
        if self.warnings:
            lines.append("Warnings:")
            for w in self.warnings:
                lines.append(f"  - {w}")
        return "\n".join(lines)


def _count(conn: sqlite3.Connection, table: str) -> int:
    return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]  # noqa: S608


def validate(db_path: str) -> ValidationResult:
    """Run all validation checks and return a result object."""
    vr = ValidationResult()

    if not os.path.exists(db_path):
        vr.error(f"Database not found: {db_path}")
        return vr

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA foreign_keys = ON")

    try:
        # ── Table existence ────────────────────────────────────
        expected_tables = {
            "rxnorm_lookup", "drugs", "drug_ingredients", "interactions",
            "warnings", "supplements", "terms", "guidelines",
        }
        existing = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        missing = expected_tables - existing
        if missing:
            vr.error(f"Missing tables: {', '.join(sorted(missing))}")
            return vr
        vr.info("All expected tables present")

        # ── Row counts ─────────────────────────────────────────
        for table in sorted(expected_tables):
            cnt = _count(conn, table)
            if cnt == 0:
                vr.warn(f"Table '{table}' is empty")
            else:
                vr.info(f"Table '{table}': {cnt} rows")

        # ── 1. No NULL rxcui in drugs ──────────────────────────
        null_rxcui = conn.execute(
            "SELECT COUNT(*) FROM drugs WHERE rxcui IS NULL OR rxcui = ''"
        ).fetchone()[0]
        if null_rxcui:
            vr.error(f"drugs table has {null_rxcui} rows with NULL/empty rxcui")
        else:
            vr.info("No NULL rxcui values in drugs")

        # ── 2. Severity range [1, 5] in interactions ───────────
        bad_sev_interactions = conn.execute(
            "SELECT COUNT(*) FROM interactions WHERE severity < 1 OR severity > 5"
        ).fetchone()[0]
        if bad_sev_interactions:
            vr.error(f"interactions: {bad_sev_interactions} rows with severity outside [1,5]")
        else:
            vr.info("All interaction severities in [1,5]")

        # ── 3. Severity range [1, 5] in warnings ──────────────
        bad_sev_warnings = conn.execute(
            "SELECT COUNT(*) FROM warnings WHERE severity IS NOT NULL AND (severity < 1 OR severity > 5)"
        ).fetchone()[0]
        if bad_sev_warnings:
            vr.error(f"warnings: {bad_sev_warnings} rows with severity outside [1,5]")
        else:
            vr.info("All warning severities in [1,5]")

        # ── 4. Severity range [1, 5] in supplements ───────────
        bad_sev_suppl = conn.execute(
            "SELECT COUNT(*) FROM supplements WHERE severity IS NOT NULL AND (severity < 1 OR severity > 5)"
        ).fetchone()[0]
        if bad_sev_suppl:
            vr.error(f"supplements: {bad_sev_suppl} rows with severity outside [1,5]")
        else:
            vr.info("All supplement severities in [1,5]")

        # ── 5. FK: drugs.rxcui → rxnorm_lookup ────────────────
        orphan_drugs = conn.execute(
            "SELECT COUNT(*) FROM drugs d "
            "WHERE NOT EXISTS (SELECT 1 FROM rxnorm_lookup r WHERE r.rxcui = d.rxcui)"
        ).fetchone()[0]
        if orphan_drugs:
            vr.warn(f"drugs: {orphan_drugs} rows reference rxcui not in rxnorm_lookup")
        else:
            vr.info("All drugs.rxcui values exist in rxnorm_lookup")

        # ── 6. FK: interactions drug_rxcui_1/2 → rxnorm_lookup ─
        for col in ("drug_rxcui_1", "drug_rxcui_2"):
            orphan = conn.execute(
                f"SELECT COUNT(*) FROM interactions i "
                f"WHERE NOT EXISTS (SELECT 1 FROM rxnorm_lookup r WHERE r.rxcui = i.{col})"
            ).fetchone()[0]
            if orphan:
                vr.warn(f"interactions: {orphan} rows have {col} not in rxnorm_lookup")
            else:
                vr.info(f"All interactions.{col} values valid")

        # ── 7. FK: warnings.drug_rxcui → rxnorm_lookup ────────
        orphan_warn = conn.execute(
            "SELECT COUNT(*) FROM warnings w "
            "WHERE NOT EXISTS (SELECT 1 FROM rxnorm_lookup r WHERE r.rxcui = w.drug_rxcui)"
        ).fetchone()[0]
        if orphan_warn:
            vr.warn(f"warnings: {orphan_warn} rows reference drug_rxcui not in rxnorm_lookup")
        else:
            vr.info("All warnings.drug_rxcui values valid")

        # ── 8. FK: drug_ingredients.parent_rxcui → rxnorm_lookup
        orphan_ing = conn.execute(
            "SELECT COUNT(*) FROM drug_ingredients di "
            "WHERE di.parent_rxcui != 'UNKNOWN' "
            "AND NOT EXISTS (SELECT 1 FROM rxnorm_lookup r WHERE r.rxcui = di.parent_rxcui)"
        ).fetchone()[0]
        if orphan_ing:
            vr.warn(f"drug_ingredients: {orphan_ing} rows have parent_rxcui not in rxnorm_lookup")
        else:
            vr.info("All drug_ingredients.parent_rxcui values valid")

        # ── 9. Guidelines grade values ─────────────────────────
        bad_grade = conn.execute(
            "SELECT COUNT(*) FROM guidelines WHERE grade NOT IN ('A','B','C','D','I')"
        ).fetchone()[0]
        if bad_grade:
            vr.warn(f"guidelines: {bad_grade} rows have unexpected grade values")
        else:
            vr.info("All guideline grades valid")

        # ── 10. No USPSTF error-message rows ───────────────────
        bad_uspstf = conn.execute(
            "SELECT COUNT(*) FROM guidelines "
            "WHERE title LIKE '%API key%' OR title LIKE '%Please contact%' "
            "OR description LIKE '%API key%'"
        ).fetchone()[0]
        if bad_uspstf:
            vr.error(f"guidelines: {bad_uspstf} rows contain USPSTF API-error text")
        else:
            vr.info("No USPSTF error-message rows in guidelines")

        # ── 11. LactMed lactation coverage ─────────────────────
        lactmed_rows = conn.execute(
            "SELECT COUNT(*) FROM warnings WHERE warning_type = 'lactation'"
        ).fetchone()[0]
        if lactmed_rows < 50:
            vr.warn(f"warnings: only {lactmed_rows} lactation rows (expected >=50)")
        else:
            vr.info(f"LactMed lactation rows: {lactmed_rows}")

        # ── 12. Geriatric PIM coverage ─────────────────────────
        geri_rows = conn.execute(
            "SELECT COUNT(*) FROM warnings WHERE warning_type = 'geriatric'"
        ).fetchone()[0]
        if geri_rows < 245:
            vr.warn(f"warnings: only {geri_rows} geriatric rows (baseline 245)")
        else:
            vr.info(f"Geriatric warning rows: {geri_rows}")

        # ── 13. Supplement coverage (distinct supplement names) ─
        distinct_suppl = conn.execute(
            "SELECT COUNT(DISTINCT supplement_name) FROM supplements"
        ).fetchone()[0]
        if distinct_suppl < 30:
            vr.warn(f"supplements: only {distinct_suppl} distinct supplements (expected >=30)")
        else:
            vr.info(f"Distinct supplements covered: {distinct_suppl}")

        # ── 14. pharm_class fill rate on drugs ─────────────────
        drugs_cols = {r[1] for r in conn.execute("PRAGMA table_info(drugs)")}
        if "pharm_class" in drugs_cols:
            total_drugs = conn.execute("SELECT COUNT(*) FROM drugs").fetchone()[0]
            with_class = conn.execute(
                "SELECT COUNT(*) FROM drugs WHERE pharm_class IS NOT NULL AND pharm_class != ''"
            ).fetchone()[0]
            pct = (with_class / total_drugs * 100) if total_drugs else 0
            if pct < 40:
                vr.warn(f"drugs.pharm_class populated on only {pct:.1f}% of rows (expected >=40%)")
            else:
                vr.info(f"drugs.pharm_class populated on {pct:.1f}% of rows")
        else:
            vr.warn("drugs.pharm_class column missing - migration not run")

        # ── 15. USPSTF guideline count ─────────────────────────
        uspstf_rows = conn.execute(
            "SELECT COUNT(*) FROM guidelines "
            "WHERE source LIKE 'USPSTF%' OR source LIKE 'uspstf%'"
        ).fetchone()[0]
        if uspstf_rows < 48:
            vr.warn(f"guidelines: only {uspstf_rows} USPSTF rows (baseline 48)")
        else:
            vr.info(f"USPSTF guideline rows: {uspstf_rows}")

        # ── 17. ACIP immunization coverage (grade='I' rows) ────────────
        acip_rows = conn.execute(
            "SELECT COUNT(*) FROM guidelines WHERE grade = 'I'"
        ).fetchone()[0]
        if acip_rows < 10:
            vr.warn(f"guidelines: only {acip_rows} ACIP grade='I' rows (expected >=10)")
        else:
            vr.info(f"ACIP grade='I' guideline rows: {acip_rows}")

        # ── 16. RxClass coverage (drug_classes + class_interactions) ───
        tables_now = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        if "drug_classes" in tables_now:
            dc_rows = _count(conn, "drug_classes")
            dc_drugs = conn.execute(
                "SELECT COUNT(DISTINCT rxcui) FROM drug_classes"
            ).fetchone()[0]
            if dc_rows < 500:
                vr.warn(
                    f"drug_classes: only {dc_rows} rows across {dc_drugs} drugs "
                    "(expected >=500)"
                )
            else:
                vr.info(f"drug_classes: {dc_rows} rows across {dc_drugs} drugs")
        if "class_interactions" in tables_now:
            ci_rows = _count(conn, "class_interactions")
            if ci_rows < 10:
                vr.warn(f"class_interactions: only {ci_rows} rows (expected >=10)")
            else:
                vr.info(f"class_interactions: {ci_rows} rows")

    finally:
        conn.close()

    return vr


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate the Aegis Health KB")
    parser.add_argument("--db", default=DEFAULT_DB, help="Path to SQLite database")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    result = validate(args.db)
    print("\n" + result.summary())
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
