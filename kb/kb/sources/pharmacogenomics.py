"""Pharmacogenomic warnings source (Tier 3b — CPIC).

Curated gene-drug warnings derived from CPIC (Clinical Pharmacogenetics
Implementation Consortium) guidelines. Each entry flags a drug whose
safety or efficacy is strongly modified by a specific genetic variant.

These rows don't fire automatically from `check_warnings` because the
user's genotype is never an input to the current tools. Instead they
surface through `get_drug_info(rxcui)` as informational context — the
model can mention them when the user asks about the drug, or when the
user volunteers a genotype (e.g. "I'm a CYP2D6 poor metabolizer").

Stored in the existing `warnings` table with `warning_type='pharmacogenomic'`
and `population` set to the variant phenotype (e.g. "CYP2D6 poor
metabolizer", "HLA-B*5701 positive"), so no schema change is needed.

Source notes:
  CPIC guidelines are CC-BY 4.0 (https://cpicpgx.org); we cite the
  guideline's PMID or DOI in the `source` field. Only guidelines
  rated Level A or B are included — these have strong evidence AND
  dosing recommendations. Level C/D pairs are intentionally excluded
  because their clinical actionability is limited.

Severity scale (1-5), matching schema.sql:
  5 - Avoid drug entirely in the listed genotype (e.g. abacavir +
      HLA-B*5701 — fatal hypersensitivity)
  4 - Alternative drug strongly preferred; use only with close monitoring
      and altered dose
  3 - Dose adjustment or closer monitoring required
  2 - Consider adjustment; efficacy or mild-to-moderate toxicity concern
  1 - Informational; low absolute risk
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)

WARNING_TYPE = "pharmacogenomic"

# Each entry: (gene_phenotype, drug_generic_lower, severity, description, citation).
CPIC_WARNINGS: list[tuple[str, str, int, str, str]] = [

    # ── HLA — severe cutaneous / hypersensitivity reactions (FDA-mandated) ──
    ("HLA-B*5701 positive", "abacavir", 5,
     "Abacavir is contraindicated in HLA-B*5701-positive patients due to "
     "life-threatening hypersensitivity reaction. FDA requires screening "
     "before initiation. A negative test does not fully eliminate the risk "
     "of hypersensitivity but reduces it substantially.",
     "CPIC guideline for abacavir and HLA-B (PMID 24561393); FDA abacavir label §5.1 (boxed warning)"),
    ("HLA-B*1502 positive", "carbamazepine", 5,
     "Carbamazepine carries a significantly higher risk of Stevens-Johnson "
     "syndrome / toxic epidermal necrolysis (SJS/TEN) in HLA-B*1502 carriers "
     "(common in East and Southeast Asian populations). FDA recommends screening "
     "before starting in high-prevalence ancestries and avoiding the drug in "
     "carriers unless benefit clearly outweighs risk.",
     "CPIC guideline for carbamazepine/oxcarbazepine and HLA-B (PMID 29392710); FDA carbamazepine label boxed warning"),
    ("HLA-B*1502 positive", "oxcarbazepine", 5,
     "Same HLA-B*1502-associated SJS/TEN risk as carbamazepine. Avoid in "
     "carriers; use alternative antiepileptic.",
     "CPIC carbamazepine/oxcarbazepine guideline (PMID 29392710)"),
    ("HLA-B*5801 positive", "allopurinol", 5,
     "HLA-B*5801 carriers (notably Han Chinese, Thai, Korean populations) "
     "have substantially elevated risk of allopurinol-induced severe cutaneous "
     "reactions (SJS/TEN/DRESS). Screening is recommended before initiation "
     "in high-prevalence ancestries.",
     "CPIC guideline for allopurinol and HLA-B (PMID 26094938)"),

    # ── CYP2D6 — opioid analgesia and antidepressants ────────────────────
    ("CYP2D6 ultra-rapid metabolizer", "codeine", 5,
     "Ultra-rapid metabolizers convert codeine to morphine very rapidly; "
     "even standard doses can produce life-threatening respiratory depression. "
     "FDA has a boxed warning against codeine use in children and nursing "
     "mothers for this reason. Avoid codeine in ultra-rapid metabolizers.",
     "CPIC guideline for CYP2D6 and codeine (PMID 33387367); FDA codeine boxed warning (April 2017)"),
    ("CYP2D6 poor metabolizer", "codeine", 4,
     "Poor metabolizers cannot convert codeine to morphine, so codeine "
     "provides little or no analgesia. Use an alternative non-codeine opioid "
     "such as morphine or hydromorphone.",
     "CPIC CYP2D6/codeine guideline (PMID 33387367)"),
    ("CYP2D6 ultra-rapid metabolizer", "tramadol", 5,
     "Similar to codeine: ultra-rapid CYP2D6 metabolism generates dangerous "
     "levels of O-desmethyl-tramadol and has been linked to fatal respiratory "
     "depression. Use an alternative analgesic.",
     "CPIC guideline for CYP2D6 and tramadol (PMID 33387367)"),
    ("CYP2D6 poor metabolizer", "tramadol", 3,
     "Poor metabolizers have reduced analgesia from tramadol. Consider a "
     "non-CYP2D6-dependent opioid.",
     "CPIC CYP2D6/tramadol guideline (PMID 33387367)"),
    ("CYP2D6 poor metabolizer", "amitriptyline", 3,
     "CYP2D6 poor metabolizers have elevated plasma concentrations of "
     "tricyclic antidepressants. Consider a 50% dose reduction and monitor "
     "for side effects, or choose a non-CYP2D6-metabolized alternative.",
     "CPIC guideline for tricyclic antidepressants and CYP2C19/CYP2D6 (PMID 27997040)"),
    ("CYP2D6 poor metabolizer", "nortriptyline", 3,
     "Increased nortriptyline levels in poor metabolizers; consider 50% "
     "initial dose reduction and therapeutic drug monitoring.",
     "CPIC TCA guideline (PMID 27997040)"),
    ("CYP2D6 poor metabolizer", "paroxetine", 3,
     "CYP2D6 poor metabolizers have 2-5× higher paroxetine exposure. "
     "Consider starting at half the standard dose and titrating slowly.",
     "CPIC guideline for CYP2D6, CYP2C19, and SSRIs (PMID 37032077)"),
    ("CYP2D6 poor metabolizer", "fluoxetine", 3,
     "Fluoxetine and its active metabolite norfluoxetine accumulate in CYP2D6 "
     "poor metabolizers. Monitor for adverse effects; consider dose reduction.",
     "CPIC SSRI guideline (PMID 37032077)"),
    ("CYP2D6 poor metabolizer", "atomoxetine", 3,
     "Atomoxetine exposure is ~10× higher in CYP2D6 poor metabolizers, "
     "with increased cardiovascular and GI adverse effects. Reduce starting "
     "dose and titrate cautiously.",
     "CPIC guideline for CYP2D6 and atomoxetine (PMID 30801677); FDA atomoxetine label §2.4"),
    ("CYP2D6 poor metabolizer", "tamoxifen", 4,
     "Tamoxifen is activated by CYP2D6 to endoxifen. Poor metabolizers have "
     "reduced endoxifen levels and may have reduced breast-cancer recurrence "
     "protection. Avoid strong CYP2D6 inhibitors; consider an aromatase "
     "inhibitor when appropriate.",
     "CPIC guideline for CYP2D6 and tamoxifen (PMID 29385237)"),

    # ── CYP2C19 — clopidogrel, PPIs, SSRIs ───────────────────────────────
    ("CYP2C19 poor metabolizer", "clopidogrel", 4,
     "Clopidogrel is a prodrug activated by CYP2C19; poor metabolizers have "
     "reduced antiplatelet effect and higher rates of stent thrombosis and "
     "major cardiovascular events after ACS/PCI. Use prasugrel or ticagrelor "
     "instead.",
     "CPIC guideline for CYP2C19 and clopidogrel (PMID 22190063); FDA clopidogrel boxed warning"),
    ("CYP2C19 intermediate metabolizer", "clopidogrel", 3,
     "Intermediate metabolizers have a measurable reduction in clopidogrel "
     "activation and elevated cardiovascular risk after PCI. Alternative "
     "antiplatelet therapy may be preferred.",
     "CPIC CYP2C19/clopidogrel guideline (PMID 22190063)"),
    ("CYP2C19 poor metabolizer", "citalopram", 3,
     "Citalopram is metabolized by CYP2C19; poor metabolizers have higher "
     "plasma levels and increased QT-prolongation risk. Consider a 50% dose "
     "reduction and monitor ECG, or choose an alternative SSRI.",
     "CPIC SSRI guideline (PMID 37032077); FDA citalopram safety communication"),
    ("CYP2C19 poor metabolizer", "escitalopram", 3,
     "Escitalopram (S-enantiomer of citalopram) is similarly affected. "
     "Reduce dose and monitor for QT prolongation.",
     "CPIC SSRI guideline (PMID 37032077)"),
    ("CYP2C19 rapid or ultra-rapid metabolizer", "omeprazole", 2,
     "Rapid metabolizers clear omeprazole faster and may have reduced acid "
     "suppression; consider alternative PPI or increased dose.",
     "CPIC guideline for CYP2C19 and PPIs (PMID 32770699)"),
    ("CYP2C19 rapid or ultra-rapid metabolizer", "esomeprazole", 2,
     "Similar to omeprazole: reduced H. pylori eradication and acid-suppression "
     "efficacy. Consider dose titration.",
     "CPIC PPI guideline (PMID 32770699)"),

    # ── CYP2C9 + VKORC1 — warfarin ───────────────────────────────────────
    ("CYP2C9 and/or VKORC1 variants", "warfarin", 4,
     "Warfarin dose requirements vary up to 10× based on CYP2C9 (*2, *3) and "
     "VKORC1 (-1639G>A) variants. Genotype-guided initial dosing, when "
     "available, can reduce time-to-therapeutic-INR and bleeding events. "
     "CPIC provides dose-calculation tables by genotype.",
     "CPIC guideline for CYP2C9, VKORC1, and warfarin (PMID 28198005); FDA warfarin label §8.7"),

    # ── SLCO1B1 — statin myopathy ───────────────────────────────────────
    ("SLCO1B1 poor function", "simvastatin", 4,
     "SLCO1B1 c.521T>C (rs4149056) carriers have reduced hepatic statin "
     "uptake and 2-5× higher myopathy risk with simvastatin, particularly "
     "at doses ≥40 mg. Use lower doses or switch to pravastatin or "
     "rosuvastatin.",
     "CPIC guideline for SLCO1B1 and simvastatin (PMID 35152405)"),

    # ── TPMT / NUDT15 — thiopurine myelosuppression ─────────────────────
    ("TPMT poor metabolizer", "azathioprine", 5,
     "TPMT-deficient patients accumulate toxic thioguanine nucleotides and "
     "develop severe, potentially fatal myelosuppression at standard doses. "
     "Start at 10% of standard dose or avoid thiopurines entirely. FDA label "
     "recommends pre-treatment genotyping.",
     "CPIC guideline for TPMT, NUDT15, and thiopurines (PMID 30447069); FDA azathioprine label §5.1"),
    ("TPMT poor metabolizer", "mercaptopurine", 5,
     "Same TPMT-dependent myelosuppression as azathioprine. Drastic dose "
     "reduction or alternative therapy is required.",
     "CPIC thiopurine guideline (PMID 30447069)"),
    ("NUDT15 poor metabolizer", "azathioprine", 5,
     "NUDT15 loss-of-function variants (common in East Asian populations) "
     "cause severe azathioprine/mercaptopurine myelosuppression independent "
     "of TPMT status. Genotyping is recommended before initiation in "
     "high-prevalence ancestries.",
     "CPIC thiopurine guideline (PMID 30447069)"),

    # ── DPYD — fluoropyrimidine toxicity ────────────────────────────────
    ("DPYD poor metabolizer", "fluorouracil", 5,
     "DPYD-deficient patients cannot clear 5-fluorouracil and develop "
     "life-threatening myelosuppression, mucositis, diarrhea, and "
     "hand-foot syndrome. Genotyping is strongly recommended; carriers "
     "should avoid or drastically reduce 5-FU / capecitabine dosing.",
     "CPIC guideline for fluoropyrimidines and DPYD (PMID 32918756); EMA/FDA fluorouracil labels"),
    ("DPYD poor metabolizer", "capecitabine", 5,
     "Capecitabine is metabolized to 5-fluorouracil in vivo; DPYD-deficient "
     "patients have the same severe toxicity risk. Reduce dose ~50% or use "
     "an alternative regimen.",
     "CPIC fluoropyrimidine guideline (PMID 32918756)"),

    # ── G6PD — oxidative hemolysis ─────────────────────────────────────
    ("G6PD deficient", "rasburicase", 5,
     "Rasburicase causes severe hemolytic anemia in G6PD-deficient patients. "
     "Contraindicated; G6PD testing is required before administration in "
     "high-prevalence populations.",
     "FDA rasburicase label §4 (contraindication); CPIC G6PD guideline status"),
    ("G6PD deficient", "dapsone", 4,
     "Dapsone can precipitate hemolysis in G6PD-deficient patients. Screen "
     "before starting, particularly in patients of African, Mediterranean, "
     "Middle Eastern, or Southeast Asian ancestry.",
     "FDA dapsone label §5.2"),
    ("G6PD deficient", "nitrofurantoin", 4,
     "Hemolytic anemia risk in G6PD deficiency. Avoid in known carriers.",
     "FDA nitrofurantoin label §5.2"),

    # ── CYP2D6 — antiemetic efficacy ───────────────────────────────────
    ("CYP2D6 ultra-rapid metabolizer", "ondansetron", 2,
     "Ultra-rapid CYP2D6 metabolizers clear ondansetron faster and may have "
     "reduced antiemetic efficacy. Consider an alternative 5-HT3 antagonist "
     "such as granisetron (not CYP2D6-dependent).",
     "CPIC guideline for CYP2D6 and ondansetron/tropisetron (PMID 28002639)"),
]


def build(db_path: str) -> int:
    """Insert curated pharmacogenomic warnings. Idempotent on (rxcui, population)."""
    log.info("PGx: starting build for %d entries", len(CPIC_WARNINGS))
    conn = sqlite3.connect(db_path)
    inserted = 0
    skipped_no_rxnorm = 0
    skipped_duplicate = 0

    try:
        for phenotype, generic, severity, description, citation in CPIC_WARNINGS:
            row = conn.execute(
                "SELECT rxcui, generic_name FROM rxnorm_lookup "
                "WHERE LOWER(generic_name) = LOWER(?) LIMIT 1",
                (generic,),
            ).fetchone()
            if row is None:
                row = conn.execute(
                    "SELECT rxcui, generic_name FROM rxnorm_lookup "
                    "WHERE LOWER(generic_name) LIKE LOWER(?) LIMIT 1",
                    (f"%{generic}%",),
                ).fetchone()
            if row is None:
                skipped_no_rxnorm += 1
                log.debug("PGx: no rxcui for %r - skipping", generic)
                continue
            rxcui, canonical = row

            exists = conn.execute(
                "SELECT 1 FROM warnings "
                "WHERE drug_rxcui = ? AND warning_type = ? AND population = ? "
                "LIMIT 1",
                (rxcui, WARNING_TYPE, phenotype),
            ).fetchone()
            if exists:
                skipped_duplicate += 1
                continue

            conn.execute(
                "INSERT INTO warnings "
                "(drug_rxcui, drug_name, warning_type, population, "
                " description, severity, source) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (rxcui, canonical, WARNING_TYPE, phenotype,
                 description, severity, citation),
            )
            inserted += 1

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info(
        "PGx: inserted %d rows, skipped %d (no rxnorm), %d (duplicate)",
        inserted, skipped_no_rxnorm, skipped_duplicate,
    )
    return inserted
