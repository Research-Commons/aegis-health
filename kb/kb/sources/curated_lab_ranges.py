"""Curated public-domain lab reference ranges, thresholds, critical values, and population-stratified ranges.

Every row here is derived from a primary public-domain source (NIH NHLBI, ADA, CDC, KDIGO,
ATA, Mayo Clinic Laboratories' public pediatric values, NCBI Bookshelf / PMC open-access
papers, Cleveland Clinic / LabCorp public critical-value lists). Per-row `source` column
records the short-code; per-row `citation` carries the URL or short-code-anchored reference.

Mirrors the per-row citation discipline of curated_ddi.py exactly — no row without a citation.

Sources referenced below (copy these short-codes verbatim into the per-row `source` field):
  NIH-LDL      : NIH NHLBI / NLM cholesterol reference range materials
  NHLBI-LIPID  : NIH NHLBI ATP III adult lipid panel reference framework
  ADA-A1C      : American Diabetes Association A1C cutoffs (5.7 prediabetes, 6.5 diabetes)
  CDC-FBG      : CDC fasting glucose cutoffs (100 prediabetes, 126 diabetes)
  KDIGO-EGFR   : KDIGO eGFR CKD stages (60, 45, 30, 15)
  ATA-TSH      : American Thyroid Association TSH framework (4.0, 10.0)
  Mayo-PEDS    : Mayo Clinic Laboratories pediatric reference values (public-facing)
  PMC-PREG     : NCBI PMC open-access pregnancy lab reference papers
  Cleveland-CV : Cleveland Clinic Laboratories critical-and-urgent-values list
  LabCorp-CV   : LabCorp public critical-values reference
  NIH-CBC      : NIH NHLBI / MedlinePlus CBC reference framework
  MedlinePlus  : MedlinePlus health-topic page for plain-language anchor
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Lab reference ranges (adult-default — 30+ analytes)
# ---------------------------------------------------------------------------
# Each entry: dict(test_name, ref_low, ref_high, units, population, citation, source)
# population values: 'adult' | 'adult_male' | 'adult_female' | 'all'

CURATED_LAB_REFERENCE_RANGES: list[dict] = [
    # Lipid panel (NHLBI ATP III framework)
    dict(test_name="LDL cholesterol",       ref_low=None,  ref_high=100,  units="mg/dL", population="adult", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="HDL cholesterol",       ref_low=40,    ref_high=None, units="mg/dL", population="adult_male",   citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="HDL cholesterol",       ref_low=50,    ref_high=None, units="mg/dL", population="adult_female", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="total cholesterol",     ref_low=None,  ref_high=200,  units="mg/dL", population="adult", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="triglycerides",         ref_low=None,  ref_high=150,  units="mg/dL", population="adult", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="non-HDL cholesterol",   ref_low=None,  ref_high=130,  units="mg/dL", population="adult", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),

    # A1C + glucose
    dict(test_name="hemoglobin a1c",        ref_low=None,  ref_high=5.6,  units="%",     population="adult", citation="ADA A1C; https://diabetes.org/about-diabetes/a1c", source="ADA-A1C"),
    dict(test_name="fasting glucose",       ref_low=70,    ref_high=99,   units="mg/dL", population="adult", citation="CDC fasting glucose; https://www.cdc.gov/diabetes/diabetes-testing/prediabetes-a1c-test.html", source="CDC-FBG"),

    # CBC (NIH NHLBI / MedlinePlus framework)
    dict(test_name="hemoglobin",            ref_low=13.5,  ref_high=17.5, units="g/dL",       population="adult_male",   citation="MedlinePlus hemoglobin; https://medlineplus.gov/lab-tests/hemoglobin-test/", source="NIH-CBC"),
    dict(test_name="hemoglobin",            ref_low=12.0,  ref_high=15.5, units="g/dL",       population="adult_female", citation="MedlinePlus hemoglobin; https://medlineplus.gov/lab-tests/hemoglobin-test/", source="NIH-CBC"),
    dict(test_name="hematocrit",            ref_low=41,    ref_high=53,   units="%",          population="adult_male",   citation="MedlinePlus hematocrit; https://medlineplus.gov/lab-tests/hematocrit-test/", source="NIH-CBC"),
    dict(test_name="hematocrit",            ref_low=36,    ref_high=46,   units="%",          population="adult_female", citation="MedlinePlus hematocrit; https://medlineplus.gov/lab-tests/hematocrit-test/", source="NIH-CBC"),
    dict(test_name="wbc",                   ref_low=4.5,   ref_high=11.0, units="10^3/uL",    population="adult", citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),
    dict(test_name="platelets",             ref_low=150,   ref_high=450,  units="10^3/uL",    population="adult", citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),
    dict(test_name="rbc",                   ref_low=4.7,   ref_high=6.1,  units="10^6/uL",    population="adult_male",   citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),
    dict(test_name="rbc",                   ref_low=4.2,   ref_high=5.4,  units="10^6/uL",    population="adult_female", citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),
    dict(test_name="mcv",                   ref_low=80,    ref_high=100,  units="fL",         population="adult", citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),
    dict(test_name="mch",                   ref_low=27,    ref_high=33,   units="pg",         population="adult", citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),
    dict(test_name="mchc",                  ref_low=32,    ref_high=36,   units="g/dL",       population="adult", citation="MedlinePlus CBC; https://medlineplus.gov/lab-tests/complete-blood-count-cbc/", source="NIH-CBC"),

    # BMP / CMP (MedlinePlus / NIH)
    dict(test_name="creatinine",            ref_low=0.7,   ref_high=1.3,  units="mg/dL", population="adult_male",   citation="MedlinePlus creatinine; https://medlineplus.gov/lab-tests/creatinine-test/", source="MedlinePlus"),
    dict(test_name="creatinine",            ref_low=0.6,   ref_high=1.1,  units="mg/dL", population="adult_female", citation="MedlinePlus creatinine; https://medlineplus.gov/lab-tests/creatinine-test/", source="MedlinePlus"),
    dict(test_name="egfr",                  ref_low=60,    ref_high=None, units="mL/min/1.73m^2", population="adult", citation="KDIGO 2024 CKD stages; https://kdigo.org/guidelines/ckd-evaluation-and-management/", source="KDIGO-EGFR"),
    dict(test_name="sodium",                ref_low=136,   ref_high=145,  units="mEq/L", population="adult", citation="MedlinePlus sodium; https://medlineplus.gov/lab-tests/sodium-blood-test/", source="MedlinePlus"),
    dict(test_name="potassium",             ref_low=3.5,   ref_high=5.0,  units="mEq/L", population="adult", citation="MedlinePlus potassium; https://medlineplus.gov/lab-tests/potassium-blood-test/", source="MedlinePlus"),
    dict(test_name="chloride",              ref_low=98,    ref_high=107,  units="mEq/L", population="adult", citation="MedlinePlus chloride; https://medlineplus.gov/lab-tests/chloride-blood-test/", source="MedlinePlus"),
    dict(test_name="co2",                   ref_low=23,    ref_high=29,   units="mEq/L", population="adult", citation="MedlinePlus CO2; https://medlineplus.gov/lab-tests/carbon-dioxide-co2-in-blood/", source="MedlinePlus"),
    dict(test_name="bun",                   ref_low=7,     ref_high=20,   units="mg/dL", population="adult", citation="MedlinePlus BUN; https://medlineplus.gov/lab-tests/blood-urea-nitrogen-bun-test/", source="MedlinePlus"),
    dict(test_name="calcium",               ref_low=8.5,   ref_high=10.2, units="mg/dL", population="adult", citation="MedlinePlus calcium; https://medlineplus.gov/lab-tests/calcium-blood-test/", source="MedlinePlus"),
    dict(test_name="albumin",               ref_low=3.4,   ref_high=5.4,  units="g/dL",  population="adult", citation="MedlinePlus albumin; https://medlineplus.gov/lab-tests/albumin-blood-test/", source="MedlinePlus"),
    dict(test_name="total protein",         ref_low=6.0,   ref_high=8.3,  units="g/dL",  population="adult", citation="MedlinePlus total protein; https://medlineplus.gov/lab-tests/total-protein-test/", source="MedlinePlus"),
    dict(test_name="alt",                   ref_low=7,     ref_high=56,   units="U/L",   population="adult", citation="MedlinePlus ALT; https://medlineplus.gov/lab-tests/alt-blood-test/", source="MedlinePlus"),
    dict(test_name="ast",                   ref_low=10,    ref_high=40,   units="U/L",   population="adult", citation="MedlinePlus AST; https://medlineplus.gov/lab-tests/ast-test/", source="MedlinePlus"),
    dict(test_name="alp",                   ref_low=44,    ref_high=147,  units="U/L",   population="adult", citation="MedlinePlus ALP; https://medlineplus.gov/lab-tests/alp-blood-test/", source="MedlinePlus"),
    dict(test_name="total bilirubin",       ref_low=0.1,   ref_high=1.2,  units="mg/dL", population="adult", citation="MedlinePlus bilirubin; https://medlineplus.gov/lab-tests/bilirubin-blood-test/", source="MedlinePlus"),

    # Endocrine
    dict(test_name="tsh",                   ref_low=0.4,   ref_high=4.0,  units="mIU/L", population="adult", citation="ATA TSH guidance; https://www.thyroid.org/patient-thyroid-information/", source="ATA-TSH"),
]


# ---------------------------------------------------------------------------
# Clinical thresholds (research-spec defaults per D-07)
# ---------------------------------------------------------------------------
# threshold_tier: free-form short code; meaning lives in the citation.

CURATED_CLINICAL_THRESHOLDS: list[dict] = [
    dict(test_name="hemoglobin a1c",  threshold_tier="prediabetes", low_cutoff=5.7, high_cutoff=6.4, units="%",     citation="ADA; https://diabetes.org/about-diabetes/a1c", source="ADA-A1C"),
    dict(test_name="hemoglobin a1c",  threshold_tier="diabetes",    low_cutoff=6.5, high_cutoff=None, units="%",    citation="ADA; https://diabetes.org/about-diabetes/a1c", source="ADA-A1C"),
    dict(test_name="fasting glucose", threshold_tier="prediabetes", low_cutoff=100, high_cutoff=125, units="mg/dL", citation="CDC; https://www.cdc.gov/diabetes/diabetes-testing/prediabetes-a1c-test.html", source="CDC-FBG"),
    dict(test_name="fasting glucose", threshold_tier="diabetes",    low_cutoff=126, high_cutoff=None, units="mg/dL", citation="CDC; https://www.cdc.gov/diabetes/diabetes-testing/prediabetes-a1c-test.html", source="CDC-FBG"),
    dict(test_name="LDL cholesterol", threshold_tier="borderline_high", low_cutoff=100, high_cutoff=129, units="mg/dL", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="LDL cholesterol", threshold_tier="high",            low_cutoff=130, high_cutoff=159, units="mg/dL", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="LDL cholesterol", threshold_tier="very_high",       low_cutoff=160, high_cutoff=189, units="mg/dL", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="LDL cholesterol", threshold_tier="extreme",         low_cutoff=190, high_cutoff=None, units="mg/dL", citation="NHLBI ATP III; https://www.nhlbi.nih.gov/health/cholesterol", source="NHLBI-LIPID"),
    dict(test_name="egfr",            threshold_tier="stage_2",         low_cutoff=60, high_cutoff=89,   units="mL/min/1.73m^2", citation="KDIGO 2024 CKD; https://kdigo.org/guidelines/ckd-evaluation-and-management/", source="KDIGO-EGFR"),
    dict(test_name="egfr",            threshold_tier="stage_3a",        low_cutoff=45, high_cutoff=59,   units="mL/min/1.73m^2", citation="KDIGO 2024 CKD; https://kdigo.org/guidelines/ckd-evaluation-and-management/", source="KDIGO-EGFR"),
    dict(test_name="egfr",            threshold_tier="stage_3b",        low_cutoff=30, high_cutoff=44,   units="mL/min/1.73m^2", citation="KDIGO 2024 CKD; https://kdigo.org/guidelines/ckd-evaluation-and-management/", source="KDIGO-EGFR"),
    dict(test_name="egfr",            threshold_tier="stage_4",         low_cutoff=15, high_cutoff=29,   units="mL/min/1.73m^2", citation="KDIGO 2024 CKD; https://kdigo.org/guidelines/ckd-evaluation-and-management/", source="KDIGO-EGFR"),
    dict(test_name="tsh",             threshold_tier="subclinical_hypo",low_cutoff=4.0, high_cutoff=10.0, units="mIU/L",        citation="ATA; https://www.thyroid.org/patient-thyroid-information/", source="ATA-TSH"),
]


# ---------------------------------------------------------------------------
# Critical values (8-12 entries per D-07)
# ---------------------------------------------------------------------------
# direction: 'low' (value <= cutoff is critical) | 'high' (value >= cutoff is critical)

CURATED_CRITICAL_VALUES: list[dict] = [
    dict(test_name="potassium",        direction="low",  cutoff=2.8,  units="mEq/L", citation="Cleveland Clinic critical values; https://clevelandcliniclabs.com/laboratory-resources/policies-procedures/critical-and-urgent-values-results/", source="Cleveland-CV"),
    dict(test_name="potassium",        direction="high", cutoff=6.0,  units="mEq/L", citation="Cleveland Clinic critical values; https://clevelandcliniclabs.com/laboratory-resources/policies-procedures/critical-and-urgent-values-results/", source="Cleveland-CV"),
    dict(test_name="hemoglobin",       direction="low",  cutoff=7.0,  units="g/dL",  citation="LabCorp critical values; https://www.labcorp.com/test-menu/resources/critical-values", source="LabCorp-CV"),
    dict(test_name="fasting glucose",  direction="low",  cutoff=40,   units="mg/dL", citation="Cleveland Clinic critical values; https://clevelandcliniclabs.com/laboratory-resources/policies-procedures/critical-and-urgent-values-results/", source="Cleveland-CV"),
    dict(test_name="fasting glucose",  direction="high", cutoff=500,  units="mg/dL", citation="Cleveland Clinic critical values; https://clevelandcliniclabs.com/laboratory-resources/policies-procedures/critical-and-urgent-values-results/", source="Cleveland-CV"),
    dict(test_name="inr",              direction="high", cutoff=5.0,  units="ratio", citation="LabCorp critical values; https://www.labcorp.com/test-menu/resources/critical-values", source="LabCorp-CV"),
    dict(test_name="troponin",         direction="high", cutoff=0.04, units="ng/mL", citation="LabCorp critical values; https://www.labcorp.com/test-menu/resources/critical-values", source="LabCorp-CV"),
    dict(test_name="calcium",          direction="low",  cutoff=7.0,  units="mg/dL", citation="Cleveland Clinic critical values; https://clevelandcliniclabs.com/laboratory-resources/policies-procedures/critical-and-urgent-values-results/", source="Cleveland-CV"),
    dict(test_name="calcium",          direction="high", cutoff=13.0, units="mg/dL", citation="Cleveland Clinic critical values; https://clevelandcliniclabs.com/laboratory-resources/policies-procedures/critical-and-urgent-values-results/", source="Cleveland-CV"),
    dict(test_name="sodium",           direction="low",  cutoff=120,  units="mEq/L", citation="LabCorp critical values; https://www.labcorp.com/test-menu/resources/critical-values", source="LabCorp-CV"),
    dict(test_name="sodium",           direction="high", cutoff=160,  units="mEq/L", citation="LabCorp critical values; https://www.labcorp.com/test-menu/resources/critical-values", source="LabCorp-CV"),
]


# ---------------------------------------------------------------------------
# Pediatric ranges (Mayo-PEDS + PMC open-access; per-row license review per D-06)
# ---------------------------------------------------------------------------
# age_low / age_high: inclusive year bands. NULL = unbounded.
# Only rows where the source URL is public-domain redistributable land here.

CURATED_PEDIATRIC_RANGES: list[dict] = [
    dict(test_name="hemoglobin", age_low=2,  age_high=6,  sex="all", ref_low=11.5, ref_high=13.5, units="g/dL", citation="Mayo Clinic Laboratories pediatric reference values; https://www.mayocliniclabs.com/test-info/pediatric/refvalues/reference.php", source="Mayo-PEDS"),
    dict(test_name="hemoglobin", age_low=6,  age_high=12, sex="all", ref_low=11.5, ref_high=15.5, units="g/dL", citation="Mayo Clinic Laboratories pediatric reference values; https://www.mayocliniclabs.com/test-info/pediatric/refvalues/reference.php", source="Mayo-PEDS"),
    dict(test_name="wbc",        age_low=2,  age_high=6,  sex="all", ref_low=5.0,  ref_high=15.5, units="10^3/uL", citation="Mayo Clinic Laboratories pediatric reference values; https://www.mayocliniclabs.com/test-info/pediatric/refvalues/reference.php", source="Mayo-PEDS"),
    dict(test_name="wbc",        age_low=6,  age_high=12, sex="all", ref_low=4.5,  ref_high=13.5, units="10^3/uL", citation="Mayo Clinic Laboratories pediatric reference values; https://www.mayocliniclabs.com/test-info/pediatric/refvalues/reference.php", source="Mayo-PEDS"),
    dict(test_name="platelets",  age_low=2,  age_high=12, sex="all", ref_low=150,  ref_high=450,  units="10^3/uL", citation="Mayo Clinic Laboratories pediatric reference values; https://www.mayocliniclabs.com/test-info/pediatric/refvalues/reference.php", source="Mayo-PEDS"),
    dict(test_name="hemoglobin a1c", age_low=10, age_high=18, sex="all", ref_low=None, ref_high=5.6, units="%", citation="ADA pediatric A1C; https://diabetes.org/about-diabetes/a1c", source="ADA-A1C"),
]


# ---------------------------------------------------------------------------
# Pregnancy ranges (PMC open-access per D-06; per-row license review)
# ---------------------------------------------------------------------------
# trimester: 1, 2, 3, or NULL ('all')

CURATED_PREGNANCY_RANGES: list[dict] = [
    dict(test_name="creatinine", trimester=1, ref_low=0.4, ref_high=0.7, units="mg/dL", citation="PMC pregnancy lab values; https://pmc.ncbi.nlm.nih.gov/articles/PMC6295771/", source="PMC-PREG"),
    dict(test_name="creatinine", trimester=2, ref_low=0.4, ref_high=0.8, units="mg/dL", citation="PMC pregnancy lab values; https://pmc.ncbi.nlm.nih.gov/articles/PMC6295771/", source="PMC-PREG"),
    dict(test_name="creatinine", trimester=3, ref_low=0.4, ref_high=0.9, units="mg/dL", citation="PMC pregnancy lab values; https://pmc.ncbi.nlm.nih.gov/articles/PMC6295771/", source="PMC-PREG"),
    dict(test_name="hemoglobin", trimester=1, ref_low=11.6, ref_high=13.9, units="g/dL", citation="PMC pregnancy lab values; https://pmc.ncbi.nlm.nih.gov/articles/PMC6295771/", source="PMC-PREG"),
    dict(test_name="hemoglobin", trimester=2, ref_low=9.7,  ref_high=14.8, units="g/dL", citation="PMC pregnancy lab values; https://pmc.ncbi.nlm.nih.gov/articles/PMC6295771/", source="PMC-PREG"),
    dict(test_name="hemoglobin", trimester=3, ref_low=9.5,  ref_high=15.0, units="g/dL", citation="PMC pregnancy lab values; https://pmc.ncbi.nlm.nih.gov/articles/PMC6295771/", source="PMC-PREG"),
]


# ---------------------------------------------------------------------------
# build()
# ---------------------------------------------------------------------------

def build(db_path: str) -> int:
    """Populate the 5 lab-range tables from the curated lists above.

    Cross-checks `lab_reference_ranges.test_name` against the existing `terms` table
    (populated by medlineplus.py) — warns when a canonical name has no MedlinePlus entry,
    but does NOT fail (terms-table coverage may lag the curated range list).

    All inserts use INSERT OR IGNORE so the function is idempotent on re-run.

    Returns the total number of rows inserted across all 5 tables.
    """
    log.info(
        "Curated lab ranges: inserting %d ranges, %d thresholds, %d critical values, %d pediatric, %d pregnancy",
        len(CURATED_LAB_REFERENCE_RANGES),
        len(CURATED_CLINICAL_THRESHOLDS),
        len(CURATED_CRITICAL_VALUES),
        len(CURATED_PEDIATRIC_RANGES),
        len(CURATED_PREGNANCY_RANGES),
    )
    conn = sqlite3.connect(db_path)
    inserted = 0
    try:
        # Cross-check canonical names against terms (warn-only).
        try:
            cur = conn.cursor()
            cur.execute("SELECT LOWER(term) FROM terms")
            known_terms = {r[0] for r in cur.fetchall()}
        except sqlite3.Error:
            known_terms = set()
        for row in CURATED_LAB_REFERENCE_RANGES:
            if row["test_name"].lower() not in known_terms:
                log.warning(
                    "Curated lab ranges: canonical name '%s' not in terms table",
                    row["test_name"],
                )

        # NOTE on idempotency: the 5 lab-range tables (shipped in Plan 01-01) do not
        # carry UNIQUE constraints, so INSERT OR IGNORE alone would not dedupe on
        # re-run. We instead pre-check existence on the natural-key columns for each
        # table. This delivers the contract documented in the docstring + the threat
        # model T-01-04-03 mitigation ("don't double rows on make-kb re-run") without
        # a schema migration. Using INSERT OR IGNORE on top is a defensive belt-and-
        # braces for the day a UNIQUE constraint does get added.

        # 1) lab_reference_ranges — natural key: (test_name, population, units, ref_low, ref_high)
        for r in CURATED_LAB_REFERENCE_RANGES:
            try:
                existing = conn.execute(
                    "SELECT 1 FROM lab_reference_ranges WHERE "
                    "test_name = ? AND population = ? AND units = ? "
                    "AND IFNULL(ref_low, -1e18) = IFNULL(?, -1e18) "
                    "AND IFNULL(ref_high, 1e18) = IFNULL(?, 1e18) LIMIT 1",
                    (r["test_name"], r["population"], r["units"], r["ref_low"], r["ref_high"]),
                ).fetchone()
                if existing:
                    continue
                conn.execute(
                    "INSERT OR IGNORE INTO lab_reference_ranges "
                    "(test_name, ref_low, ref_high, units, population, citation, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (r["test_name"], r["ref_low"], r["ref_high"], r["units"],
                     r["population"], r["citation"], r["source"]),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated lab ranges: insert error on %s: %s", r["test_name"], exc)

        # 2) clinical_thresholds — natural key: (test_name, threshold_tier, units)
        for r in CURATED_CLINICAL_THRESHOLDS:
            try:
                existing = conn.execute(
                    "SELECT 1 FROM clinical_thresholds WHERE "
                    "test_name = ? AND threshold_tier = ? AND units = ? LIMIT 1",
                    (r["test_name"], r["threshold_tier"], r["units"]),
                ).fetchone()
                if existing:
                    continue
                conn.execute(
                    "INSERT OR IGNORE INTO clinical_thresholds "
                    "(test_name, threshold_tier, low_cutoff, high_cutoff, units, citation, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (r["test_name"], r["threshold_tier"], r["low_cutoff"], r["high_cutoff"],
                     r["units"], r["citation"], r["source"]),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated lab ranges (thresholds): insert error on %s: %s", r["test_name"], exc)

        # 3) critical_values — natural key: (test_name, direction, units)
        for r in CURATED_CRITICAL_VALUES:
            try:
                existing = conn.execute(
                    "SELECT 1 FROM critical_values WHERE "
                    "test_name = ? AND direction = ? AND units = ? LIMIT 1",
                    (r["test_name"], r["direction"], r["units"]),
                ).fetchone()
                if existing:
                    continue
                conn.execute(
                    "INSERT OR IGNORE INTO critical_values "
                    "(test_name, direction, cutoff, units, citation, source) "
                    "VALUES (?, ?, ?, ?, ?, ?)",
                    (r["test_name"], r["direction"], r["cutoff"], r["units"],
                     r["citation"], r["source"]),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated lab ranges (critical): insert error on %s: %s", r["test_name"], exc)

        # 4) reference_ranges_pediatric — natural key: (test_name, age_low, age_high, sex, units)
        for r in CURATED_PEDIATRIC_RANGES:
            try:
                existing = conn.execute(
                    "SELECT 1 FROM reference_ranges_pediatric WHERE "
                    "test_name = ? AND sex = ? AND units = ? "
                    "AND IFNULL(age_low, -1) = IFNULL(?, -1) "
                    "AND IFNULL(age_high, -1) = IFNULL(?, -1) LIMIT 1",
                    (r["test_name"], r["sex"], r["units"], r["age_low"], r["age_high"]),
                ).fetchone()
                if existing:
                    continue
                conn.execute(
                    "INSERT OR IGNORE INTO reference_ranges_pediatric "
                    "(test_name, age_low, age_high, sex, ref_low, ref_high, units, citation, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (r["test_name"], r["age_low"], r["age_high"], r["sex"],
                     r["ref_low"], r["ref_high"], r["units"], r["citation"], r["source"]),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated lab ranges (peds): insert error on %s: %s", r["test_name"], exc)

        # 5) reference_ranges_pregnancy — natural key: (test_name, trimester, units)
        for r in CURATED_PREGNANCY_RANGES:
            try:
                existing = conn.execute(
                    "SELECT 1 FROM reference_ranges_pregnancy WHERE "
                    "test_name = ? AND units = ? "
                    "AND IFNULL(trimester, -1) = IFNULL(?, -1) LIMIT 1",
                    (r["test_name"], r["units"], r["trimester"]),
                ).fetchone()
                if existing:
                    continue
                conn.execute(
                    "INSERT OR IGNORE INTO reference_ranges_pregnancy "
                    "(test_name, trimester, ref_low, ref_high, units, citation, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (r["test_name"], r["trimester"], r["ref_low"], r["ref_high"],
                     r["units"], r["citation"], r["source"]),
                )
                inserted += 1
            except sqlite3.Error as exc:
                log.error("Curated lab ranges (preg): insert error on %s: %s", r["test_name"], exc)

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("Curated lab ranges: inserted %d rows total", inserted)
    return inserted
