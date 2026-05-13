"""Curated tumor-marker / genetic / pathology-grade auto-defer list (D-11).

Single source of truth for both the Python reference parser
(tools/parsers/lab_report_parser.py) and the Kotlin production parser
(android/.../reportreader/RangeEvaluator.kt). Both read the
`auto_defer_tests` KB row at canonical-name lookup time; drift between
languages is therefore structurally impossible.

Per-row `category` short-code matches the D-12 defer_reason suffix:
  tumor_marker | genetic | pathology

Sources referenced below (copy these short-codes verbatim into the per-row
citation field via the URL or short-code):
  AAFP-TM   : American Academy of Family Physicians Serum Tumor Markers
              (https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html)
  NCI-PSA   : NCI / NIH PSA test guidance
              (https://www.cancer.gov/types/prostate/psa-fact-sheet)
  NLM-BRCA1 : NLM MedlinePlus Genetics BRCA1 entry
              (https://medlineplus.gov/genetics/gene/brca1/)
  NLM-BRCA2 : NLM MedlinePlus Genetics BRCA2 entry
              (https://medlineplus.gov/genetics/gene/brca2/)
  NLM-KRAS  : NLM MedlinePlus Genetics KRAS entry
              (https://medlineplus.gov/genetics/gene/kras/)
  AAFP-PATH : AAFP statement on pathology grade results being
              clinician-to-clinician (https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html)
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)

CURATED_AUTO_DEFER: list[dict] = [
    # Tumor markers (mirror _AUTO_DEFER_CANONICAL in lab_report_parser.py)
    dict(canonical_name="CA-125",  category="tumor_marker",
         citation="AAFP Serum Tumor Markers; https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html"),
    dict(canonical_name="CA 19-9", category="tumor_marker",
         citation="AAFP Serum Tumor Markers; https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html"),
    dict(canonical_name="PSA",     category="tumor_marker",
         citation="NCI PSA fact sheet; https://www.cancer.gov/types/prostate/psa-fact-sheet"),
    dict(canonical_name="AFP",     category="tumor_marker",
         citation="AAFP Serum Tumor Markers; https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html"),
    dict(canonical_name="CEA",     category="tumor_marker",
         citation="AAFP Serum Tumor Markers; https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html"),

    # Genetic
    dict(canonical_name="BRCA1",   category="genetic",
         citation="NLM MedlinePlus Genetics BRCA1 entry; https://medlineplus.gov/genetics/gene/brca1/"),
    dict(canonical_name="BRCA2",   category="genetic",
         citation="NLM MedlinePlus Genetics BRCA2 entry; https://medlineplus.gov/genetics/gene/brca2/"),
    dict(canonical_name="KRAS",    category="genetic",
         citation="NLM MedlinePlus Genetics KRAS entry; https://medlineplus.gov/genetics/gene/kras/"),

    # Pathology grades — patient-facing interpretation is clinician-to-clinician
    # only (per AAFP / 21st Century Cures Act commentary).
    dict(canonical_name="Gleason score",  category="pathology",
         citation="AAFP; https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html"),
    dict(canonical_name="Bethesda grade", category="pathology",
         citation="AAFP; https://www.aafp.org/pubs/afp/issues/2003/0915/p1075.html"),
]


def build(db_path: str) -> int:
    """Populate auto_defer_tests from CURATED_AUTO_DEFER.

    Mirrors the natural-key-precheck idempotency pattern of curated_lab_ranges.
    Returns the number of rows inserted (0 on re-run against a fully-populated KB).
    """
    log.info("Curated auto-defer tests: inserting %d rows", len(CURATED_AUTO_DEFER))
    conn = sqlite3.connect(db_path)
    inserted = 0
    try:
        for r in CURATED_AUTO_DEFER:
            try:
                existing = conn.execute(
                    "SELECT 1 FROM auto_defer_tests WHERE canonical_name = ? LIMIT 1",
                    (r["canonical_name"],),
                ).fetchone()
                if existing:
                    continue
                conn.execute(
                    "INSERT OR IGNORE INTO auto_defer_tests "
                    "(canonical_name, category, citation) VALUES (?, ?, ?)",
                    (r["canonical_name"], r["category"], r["citation"]),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated auto-defer: insert error on %s: %s",
                          r["canonical_name"], exc)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    log.info("Curated auto-defer tests: inserted %d rows", inserted)
    return inserted
