PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS rxnorm_lookup (
    rxcui         TEXT PRIMARY KEY,
    brand_name    TEXT NOT NULL,
    generic_name  TEXT NOT NULL,
    tty           TEXT,              -- term type (SBD, SCD, BPCK …)
    category      TEXT NOT NULL DEFAULT 'Rx',  -- 'Rx', 'OTC', 'Controlled', 'Supplement'
    source        TEXT NOT NULL DEFAULT 'rxnorm'
);
CREATE INDEX IF NOT EXISTS idx_rxnorm_brand   ON rxnorm_lookup(brand_name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_rxnorm_generic ON rxnorm_lookup(generic_name COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS drugs (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    rxcui               TEXT NOT NULL,
    drug_name           TEXT NOT NULL,
    generic_name        TEXT,
    dosage_form         TEXT,
    route               TEXT,
    labeler             TEXT,
    description         TEXT,
    pharm_class         TEXT,
    indication_summary  TEXT,
    source              TEXT NOT NULL DEFAULT 'openfda',
    FOREIGN KEY (rxcui) REFERENCES rxnorm_lookup(rxcui)
);
CREATE INDEX IF NOT EXISTS idx_drugs_rxcui ON drugs(rxcui);
CREATE INDEX IF NOT EXISTS idx_drugs_name  ON drugs(drug_name COLLATE NOCASE);
-- idx_drugs_pharmclass and unique idx_drugs_unique are created by
-- openfda._migrate_drugs_schema() so existing KBs can de-dup first.

CREATE TABLE IF NOT EXISTS drug_ingredients (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_rxcui    TEXT NOT NULL,
    parent_name     TEXT NOT NULL,
    ingredient_name TEXT NOT NULL,
    ingredient_rxcui TEXT,
    strength        TEXT,
    source          TEXT NOT NULL DEFAULT 'dailymed',
    FOREIGN KEY (parent_rxcui) REFERENCES rxnorm_lookup(rxcui)
);
CREATE INDEX IF NOT EXISTS idx_ingredients_parent ON drug_ingredients(parent_rxcui);

CREATE TABLE IF NOT EXISTS interactions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    drug_rxcui_1    TEXT NOT NULL,
    drug_name_1     TEXT NOT NULL,
    drug_rxcui_2    TEXT NOT NULL,
    drug_name_2     TEXT NOT NULL,
    severity        INTEGER NOT NULL CHECK (severity BETWEEN 1 AND 5),
    description     TEXT,
    clinical_effect TEXT,
    source          TEXT NOT NULL DEFAULT 'openfda',
    FOREIGN KEY (drug_rxcui_1) REFERENCES rxnorm_lookup(rxcui),
    FOREIGN KEY (drug_rxcui_2) REFERENCES rxnorm_lookup(rxcui)
);
CREATE INDEX IF NOT EXISTS idx_interact_drug1 ON interactions(drug_rxcui_1);
CREATE INDEX IF NOT EXISTS idx_interact_drug2 ON interactions(drug_rxcui_2);

CREATE TABLE IF NOT EXISTS warnings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    drug_rxcui      TEXT NOT NULL,
    drug_name       TEXT NOT NULL,
    warning_type    TEXT NOT NULL,   -- 'contraindication', 'pregnancy', 'pediatric', 'geriatric', 'boxed'
    population      TEXT,
    description     TEXT NOT NULL,
    severity        INTEGER CHECK (severity BETWEEN 1 AND 5),
    source          TEXT NOT NULL DEFAULT 'openfda',
    FOREIGN KEY (drug_rxcui) REFERENCES rxnorm_lookup(rxcui)
);
CREATE INDEX IF NOT EXISTS idx_warnings_rxcui ON warnings(drug_rxcui);

CREATE TABLE IF NOT EXISTS supplements (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    supplement_name     TEXT NOT NULL,
    interacting_drug    TEXT NOT NULL,
    interaction_type    TEXT,         -- 'pharmacokinetic', 'pharmacodynamic', 'additive', 'antagonistic'
    severity            INTEGER CHECK (severity BETWEEN 1 AND 5),
    description         TEXT,
    mechanism           TEXT,
    recommendation      TEXT,
    source              TEXT NOT NULL DEFAULT 'nih_dsld'
);
CREATE INDEX IF NOT EXISTS idx_suppl_name ON supplements(supplement_name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_suppl_drug ON supplements(interacting_drug COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS terms (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    term            TEXT NOT NULL UNIQUE,
    definition      TEXT NOT NULL,
    category        TEXT,
    url             TEXT,
    source          TEXT NOT NULL DEFAULT 'medlineplus'
);
CREATE INDEX IF NOT EXISTS idx_terms_term ON terms(term COLLATE NOCASE);

CREATE TABLE IF NOT EXISTS guidelines (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    recommendation_id   TEXT UNIQUE,
    title               TEXT NOT NULL,
    grade               TEXT NOT NULL,   -- 'A', 'B', 'C', 'D', 'I'
    population_age_min  INTEGER,
    population_age_max  INTEGER,
    population_sex      TEXT,            -- 'male', 'female', 'all'
    description         TEXT NOT NULL,
    clinical_url        TEXT,
    source              TEXT NOT NULL DEFAULT 'uspstf',
    -- 1 = recommendation only applies when a risk-factor/condition matches
    -- (ACIP risk-based schedules). Demographic-only queries must exclude
    -- these; condition-keyword queries must include them.
    risk_only           INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_guide_grade ON guidelines(grade);

-- Drug class memberships from NLM RxClass API. A single drug can belong to
-- multiple classes (one row per (rxcui, class_id, class_type) triple).
CREATE TABLE IF NOT EXISTS drug_classes (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    rxcui           TEXT NOT NULL,
    drug_name       TEXT NOT NULL,
    class_id        TEXT NOT NULL,      -- e.g., 'N06AB' (ATC4) or 'N0000175696' (MeSH)
    class_name      TEXT NOT NULL,      -- e.g., 'Selective serotonin reuptake inhibitors'
    class_type      TEXT NOT NULL,      -- 'ATC', 'EPC', 'MoA', 'MeSH'
    rela_source     TEXT,               -- RxClass relaSource: 'ATC', 'DAILYMED', 'MEDRT', ...
    source          TEXT NOT NULL DEFAULT 'rxclass',
    FOREIGN KEY (rxcui) REFERENCES rxnorm_lookup(rxcui),
    UNIQUE (rxcui, class_id, class_type)
);
CREATE INDEX IF NOT EXISTS idx_drug_classes_rxcui ON drug_classes(rxcui);
CREATE INDEX IF NOT EXISTS idx_drug_classes_class ON drug_classes(class_id);

-- Curated class-pair interaction rules. One row expands to every drug pair
-- whose classes match, letting check_warnings catch interactions the
-- pairwise `interactions` table misses (e.g., "any SSRI + any MAOI").
CREATE TABLE IF NOT EXISTS class_interactions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id_1      TEXT NOT NULL,
    class_name_1    TEXT NOT NULL,
    class_type_1    TEXT NOT NULL,
    class_id_2      TEXT NOT NULL,
    class_name_2    TEXT NOT NULL,
    class_type_2    TEXT NOT NULL,
    severity        INTEGER NOT NULL CHECK (severity BETWEEN 1 AND 5),
    description     TEXT NOT NULL,
    mechanism       TEXT,
    source          TEXT NOT NULL DEFAULT 'curated_class'
);
CREATE INDEX IF NOT EXISTS idx_class_interactions_c1 ON class_interactions(class_id_1);
CREATE INDEX IF NOT EXISTS idx_class_interactions_c2 ON class_interactions(class_id_2);

-- ---------------------------------------------------------------------------
-- Phase 1 — ReportReader: lab reference ranges (adult-default + per-population)
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS lab_reference_ranges (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name   TEXT NOT NULL,
    ref_low     REAL,
    ref_high    REAL,
    units       TEXT NOT NULL,
    population  TEXT NOT NULL DEFAULT 'adult',
    citation    TEXT NOT NULL,
    source      TEXT NOT NULL DEFAULT 'curated_labs'
);
CREATE INDEX IF NOT EXISTS idx_lab_ref_name ON lab_reference_ranges(test_name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_lab_ref_pop  ON lab_reference_ranges(population);

-- Clinical decision thresholds (A1C 5.7/6.5, fasting glucose 100/126, LDL
-- 100/130/160/190, eGFR 60/45/30/15, TSH 4.0/10.0, etc).
-- threshold_tier values are free-form short codes (e.g., 'prediabetes', 'diabetes',
-- 'borderline_high', 'high', 'very_high', 'stage_2', 'stage_3a').
CREATE TABLE IF NOT EXISTS clinical_thresholds (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name       TEXT NOT NULL,
    threshold_tier  TEXT NOT NULL,
    low_cutoff      REAL,
    high_cutoff     REAL,
    units           TEXT NOT NULL,
    citation        TEXT NOT NULL,
    source          TEXT NOT NULL DEFAULT 'curated_thresholds'
);
CREATE INDEX IF NOT EXISTS idx_clin_thr_name ON clinical_thresholds(test_name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_clin_thr_tier ON clinical_thresholds(threshold_tier);

-- CLIA / vendor "panic" values used to flag URGENT-tier rows in RangeEvaluator.
-- direction values: 'low' (value <= cutoff) or 'high' (value >= cutoff).
CREATE TABLE IF NOT EXISTS critical_values (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name       TEXT NOT NULL,
    direction       TEXT NOT NULL CHECK (direction IN ('low', 'high')),
    cutoff          REAL NOT NULL,
    units           TEXT NOT NULL,
    citation        TEXT NOT NULL,
    source          TEXT NOT NULL DEFAULT 'curated_critical'
);
CREATE INDEX IF NOT EXISTS idx_crit_val_name ON critical_values(test_name COLLATE NOCASE);

-- Pediatric reference ranges. age_low/age_high are inclusive year bands
-- (NULL = unbounded); sex is 'all' | 'male' | 'female'.
CREATE TABLE IF NOT EXISTS reference_ranges_pediatric (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name   TEXT NOT NULL,
    age_low     INTEGER,
    age_high    INTEGER,
    sex         TEXT NOT NULL DEFAULT 'all',
    ref_low     REAL,
    ref_high    REAL,
    units       TEXT NOT NULL,
    citation    TEXT NOT NULL,
    source      TEXT NOT NULL DEFAULT 'curated_peds'
);
CREATE INDEX IF NOT EXISTS idx_peds_ref_name ON reference_ranges_pediatric(test_name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_peds_ref_age  ON reference_ranges_pediatric(age_low, age_high);

-- Pregnancy reference ranges. trimester values: 1, 2, 3, or NULL ('all').
CREATE TABLE IF NOT EXISTS reference_ranges_pregnancy (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    test_name   TEXT NOT NULL,
    trimester   INTEGER CHECK (trimester IS NULL OR trimester BETWEEN 1 AND 3),
    ref_low     REAL,
    ref_high    REAL,
    units       TEXT NOT NULL,
    citation    TEXT NOT NULL,
    source      TEXT NOT NULL DEFAULT 'curated_preg'
);
CREATE INDEX IF NOT EXISTS idx_preg_ref_name ON reference_ranges_pregnancy(test_name COLLATE NOCASE);
CREATE INDEX IF NOT EXISTS idx_preg_ref_tri  ON reference_ranges_pregnancy(trimester);