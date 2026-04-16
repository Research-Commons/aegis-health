"""Master build script – orchestrates all KB sources into a single SQLite database.

Usage:
    python -m kb.build              # build with all sources
    python -m kb.build --only rxnorm openfda   # selective sources
    python -m kb.build --db /path/to/output.sqlite
"""
from __future__ import annotations

import argparse
import logging
import os
import sqlite3
import sys
import time
from pathlib import Path

from kb.sources import rxnorm, openfda, dailymed, nih_dsld, medlineplus, uspstf

log = logging.getLogger(__name__)

DEFAULT_DB = os.path.join(os.path.dirname(__file__), "..", "output", "aegis_kb.sqlite")
SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "schema.sql")

# Sources in dependency order – rxnorm must run first because other
# sources look up rxcui values from rxnorm_lookup.
SOURCES: list[tuple[str, object]] = [
    ("rxnorm",      rxnorm),
    ("openfda",     openfda),
    ("dailymed",    dailymed),
    ("nih_dsld",    nih_dsld),
    ("medlineplus", medlineplus),
    ("uspstf",      uspstf),
]


def _init_db(db_path: str) -> None:
    """Create the database and apply schema.sql."""
    os.makedirs(os.path.dirname(os.path.abspath(db_path)), exist_ok=True)

    with open(SCHEMA_PATH) as f:
        schema_sql = f.read()

    conn = sqlite3.connect(db_path)
    try:
        conn.executescript(schema_sql)
        conn.commit()
        log.info("Schema applied to %s", db_path)
    finally:
        conn.close()


def run(db_path: str, only: list[str] | None = None) -> dict[str, int]:
    """Run the full build pipeline.

    Args:
        db_path: Path to the output SQLite file.
        only: If provided, only run these source names.

    Returns:
        Dict mapping source name → rows inserted.
    """
    _init_db(db_path)
    results: dict[str, int] = {}

    for name, module in SOURCES:
        if only and name not in only:
            log.info("Skipping source: %s (not in --only list)", name)
            continue

        log.info("=" * 60)
        log.info("Building source: %s", name)
        log.info("=" * 60)
        t0 = time.time()

        try:
            rows = module.build(db_path)
            elapsed = time.time() - t0
            results[name] = rows
            log.info("✓ %s: %d rows in %.1fs", name, rows, elapsed)
        except Exception:
            log.exception("✗ %s: build failed", name)
            results[name] = -1

    # Summary
    log.info("=" * 60)
    log.info("Build complete.  Summary:")
    total = 0
    for name, count in results.items():
        status = f"{count} rows" if count >= 0 else "FAILED"
        log.info("  %-15s %s", name, status)
        if count > 0:
            total += count
    log.info("  %-15s %d rows", "TOTAL", total)
    log.info("Database: %s", os.path.abspath(db_path))
    log.info("=" * 60)

    return results


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the Aegis Health knowledge base")
    parser.add_argument(
        "--db", default=DEFAULT_DB,
        help="Path to output SQLite database (default: kb/output/aegis_kb.sqlite)",
    )
    parser.add_argument(
        "--only", nargs="+", choices=[n for n, _ in SOURCES],
        help="Only build these sources",
    )
    parser.add_argument(
        "-v", "--verbose", action="store_true",
        help="Enable debug-level logging",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )

    results = run(args.db, only=args.only)

    if any(v < 0 for v in results.values()):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
