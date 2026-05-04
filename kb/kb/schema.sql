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