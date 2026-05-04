"""Geriatric Potentially Inappropriate Medications (PIM) source.

Curated warnings for drugs that should be avoided or used with extra caution
in older adults (age >=65). Drug selection is informed by the 2023 AGS Beers
Criteria, but entries are independently derived - each citation points to
the primary FDA label, FDA Drug Safety Communication, or peer-reviewed
clinical pharmacology source rather than the Beers publication itself,
which is copyrighted by the American Geriatrics Society.

Why these rules matter: older adults have reduced renal clearance, altered
pharmacodynamics, more polypharmacy, and higher fall risk. A drug that is
safe for a 40-year-old may cause delirium, falls, cardiac events, or
hospitalization in a 75-year-old at the same dose.

Severity scale (1-5), matching schema.sql:
  5 - Avoid entirely in older adults
  4 - Avoid unless no alternative; close monitoring required
  3 - Use with caution; dose reduction or monitoring
  2 - Population consideration, low absolute risk
  1 - Informational
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)

WARNING_TYPE = "geriatric"
POPULATION = "elderly (>=65)"

# Each entry: (generic_name_lower, severity, description, citation).
# The rxcui is looked up from rxnorm_lookup at build time.
GERIATRIC_PIMS: list[tuple[str, int, str, str]] = [

    # -- First-generation anticholinergics (cumulative anticholinergic burden) --
    ("diphenhydramine", 4,
     "First-generation antihistamine with strong anticholinergic effects. "
     "In older adults it causes confusion, urinary retention, constipation, "
     "dry mouth, blurred vision, and increased fall risk. Non-sedating "
     "alternatives (loratadine, cetirizine, fexofenadine) are preferred.",
     "FDA diphenhydramine label (geriatric use section); Kolanowski A et al., J Am Geriatr Soc 2016 (anticholinergic burden)"),

    ("hydroxyzine", 4,
     "Highly anticholinergic; associated with confusion, delirium, and falls "
     "in older adults. Avoid as first-line anxiolytic or sleep aid.",
     "FDA hydroxyzine label §8.5 (geriatric use)"),

    ("chlorpheniramine", 4,
     "Strong anticholinergic effects in older adults: cognitive impairment, "
     "urinary retention, constipation. Second-generation antihistamines preferred.",
     "FDA chlorpheniramine OTC monograph; Cai X et al., Pharmacotherapy 2013"),

    # -- First-generation antipsychotics and high-anticholinergic agents ------
    ("amitriptyline", 5,
     "Tricyclic antidepressant with strong anticholinergic and orthostatic "
     "hypotension effects. High risk of sedation, falls, cardiac conduction "
     "problems, and cognitive impairment. Avoid in older adults; SSRIs or "
     "SNRIs are preferred.",
     "FDA amitriptyline label §8.5; Coupland CA et al., BMJ 2011"),

    ("nortriptyline", 3,
     "Tricyclic antidepressant with lower anticholinergic burden than "
     "amitriptyline but still causes orthostasis and falls in older adults. "
     "Use lower doses and monitor for cardiac conduction.",
     "FDA nortriptyline label §8.5"),

    # -- Benzodiazepines (long-acting and short-acting) ----------------------
    ("diazepam", 5,
     "Long-acting benzodiazepine with active metabolites that accumulate in "
     "older adults, causing prolonged sedation, cognitive impairment, delirium, "
     "falls, and hip fractures. Avoid in older adults.",
     "FDA diazepam label §8.5; Cumming RG & Le Couteur DG, CNS Drugs 2003"),

    ("alprazolam", 5,
     "Short-acting benzodiazepine but still substantially increases falls, "
     "fractures, cognitive impairment, and motor vehicle accidents in older "
     "adults. Avoid or use lowest dose for shortest period.",
     "FDA alprazolam label §8.5"),

    ("lorazepam", 5,
     "Benzodiazepine; major risk of falls, hip fractures, delirium, and "
     "cognitive decline in older adults. If truly needed (e.g., ethanol "
     "withdrawal), use lowest effective dose briefly.",
     "FDA lorazepam label §8.5; Stone KL et al., JAMA Intern Med 2008"),

    ("clonazepam", 5,
     "Long-acting benzodiazepine; persistent sedation, fall risk, cognitive "
     "decline in older adults. Avoid.",
     "FDA clonazepam label §8.5"),

    ("temazepam", 4,
     "Benzodiazepine hypnotic; fall and fracture risk in older adults. "
     "Non-pharmacologic sleep interventions preferred.",
     "FDA temazepam label §8.5"),

    # -- Z-drugs (non-benzodiazepine hypnotics) ------------------------------
    ("zolpidem", 4,
     "Non-benzodiazepine hypnotic but carries similar fall, fracture, and "
     "cognitive risks as benzodiazepines in older adults. FDA recommends "
     "reduced doses (5 mg IR, 6.25 mg CR) in older adults.",
     "FDA Drug Safety Communication Jan 2013 (dose reduction in elderly); zolpidem label §2.3"),

    # -- Muscle relaxants ---------------------------------------------------
    ("cyclobenzaprine", 4,
     "Strongly anticholinergic and sedating; poorly tolerated in older adults. "
     "High risk of confusion, falls, and limited clinical benefit for "
     "chronic use.",
     "FDA cyclobenzaprine label §8.5"),

    ("methocarbamol", 3,
     "CNS depressant with limited efficacy evidence and notable sedation "
     "in older adults.",
     "FDA methocarbamol label §8.5"),

    # -- NSAIDs in older adults ---------------------------------------------
    ("ibuprofen", 4,
     "Chronic NSAID use in adults >=65 substantially increases GI bleeding, "
     "acute kidney injury, heart failure, and cardiovascular events. Use "
     "lowest dose for shortest duration; consider acetaminophen first.",
     "FDA ibuprofen label §8.5; Roumie CL et al., Arch Intern Med 2008"),

    ("naproxen", 4,
     "Long half-life NSAID; cumulative GI, renal, and CV risk in older "
     "adults. Minimize chronic use; use gastroprotection if required.",
     "FDA naproxen label §8.5"),

    ("indomethacin", 5,
     "Most CNS-active NSAID; high risk of confusion, headache, and GI "
     "bleeding in older adults. Avoid.",
     "FDA indomethacin label §8.5"),

    ("ketorolac", 5,
     "Systemic ketorolac in older adults carries high risk of GI bleeding "
     "and acute kidney injury. Limit to 5 days maximum, lower doses.",
     "FDA ketorolac label §8.5 (boxed warning)"),

    # -- Sulfonylureas ------------------------------------------------------
    ("glyburide", 5,
     "Long-acting sulfonylurea; prolonged hypoglycemia in older adults "
     "due to reduced renal clearance. Avoid; use shorter-acting glipizide "
     "or alternative agents (DPP-4 inhibitors, metformin, SGLT2 inhibitors).",
     "FDA glyburide label §8.5; ADA Standards of Medical Care in Diabetes"),

    ("chlorpropamide", 5,
     "Very long half-life sulfonylurea; prolonged hypoglycemia and SIADH "
     "risk in older adults. Avoid.",
     "FDA chlorpropamide label §8.5"),

    # -- Cardiovascular PIMs ------------------------------------------------
    ("digoxin", 3,
     "Narrow therapeutic index; reduced renal clearance in older adults "
     "increases toxicity risk (nausea, visual disturbance, arrhythmia). "
     "Use doses <=0.125 mg/day and monitor serum levels.",
     "FDA digoxin label §8.5; Rathore SS et al., NEJM 2002"),

    ("amiodarone", 3,
     "High risk of thyroid, pulmonary, hepatic, and QT prolongation "
     "adverse effects; older adults have more comorbid conditions that "
     "magnify risk. Avoid as first-line antiarrhythmic when alternatives exist.",
     "FDA amiodarone label (boxed warning)"),

    ("nifedipine", 3,
     "Short-acting nifedipine causes reflex tachycardia and hypotension "
     "with risk of myocardial ischemia in older adults. Avoid immediate-"
     "release formulation.",
     "FDA Cardizem (diltiazem) label vs. short-acting nifedipine literature; Furberg CD et al., Circulation 1995"),

    ("doxazosin", 3,
     "Alpha-1 blocker; orthostatic hypotension and fall risk, especially "
     "with first dose. Avoid as first-line antihypertensive in older adults.",
     "FDA doxazosin label §8.5; ALLHAT study"),

    # -- Opioids specific caution in older adults ---------------------------
    ("meperidine", 5,
     "Opioid analgesic with neurotoxic metabolite (normeperidine) that "
     "accumulates with reduced renal clearance. Delirium, seizure, and "
     "tremor risk in older adults. Avoid.",
     "FDA meperidine label §8.5"),

    ("pentazocine", 5,
     "Mixed opioid agonist-antagonist; CNS adverse effects (confusion, "
     "hallucinations) common in older adults. Avoid.",
     "FDA pentazocine label §8.5"),

    # -- CNS / psychiatric -------------------------------------------------
    ("chlorpromazine", 4,
     "Typical antipsychotic with strong anticholinergic and sedative "
     "effects; orthostatic hypotension and extrapyramidal symptoms are "
     "pronounced in older adults.",
     "FDA chlorpromazine label §8.5"),

    ("promethazine", 4,
     "Strongly anticholinergic phenothiazine; sedation, confusion, and "
     "fall risk in older adults. Non-sedating antiemetics preferred.",
     "FDA promethazine label §8.5"),

    ("meclizine", 3,
     "Anticholinergic antihistamine; sedation, cognitive impairment, and "
     "fall risk in older adults. Limit chronic use.",
     "FDA meclizine label §8.5"),

    # -- GI agents ---------------------------------------------------------
    ("dicyclomine", 4,
     "Anticholinergic antispasmodic; confusion, constipation, urinary "
     "retention, and fall risk. Avoid in older adults.",
     "FDA dicyclomine label §8.5"),

    ("hyoscyamine", 4,
     "Anticholinergic; all typical anticholinergic risks in older adults. "
     "Avoid.",
     "FDA hyoscyamine label §8.5"),

    # -- Urologic ----------------------------------------------------------
    ("oxybutynin", 4,
     "Anticholinergic used for overactive bladder; causes confusion, "
     "constipation, falls in older adults. Extended-release or newer "
     "beta-3 agonists (mirabegron) are preferred when possible.",
     "FDA oxybutynin label §8.5; Kay GG et al., Eur Urol 2006"),

    ("tolterodine", 3,
     "Anticholinergic for overactive bladder with lower CNS penetration "
     "than oxybutynin but still carries cognitive risk in older adults.",
     "FDA tolterodine label §8.5"),

    # -- Endocrine ---------------------------------------------------------
    ("desmopressin", 3,
     "High risk of hyponatremia in older adults, especially with "
     "concurrent SSRIs or diuretics. Monitor sodium.",
     "FDA desmopressin label §5 warnings; Thomson P et al., J Am Geriatr Soc 2016"),

    # -- Miscellaneous -----------------------------------------------------
    ("orphenadrine", 4,
     "Anticholinergic muscle relaxant; no efficacy advantage and poor "
     "tolerability in older adults. Avoid.",
     "FDA orphenadrine label §8.5"),

    ("carisoprodol", 4,
     "Muscle relaxant metabolized to meprobamate (a controlled sedative) "
     "with high addiction and falls potential in older adults. Avoid.",
     "FDA carisoprodol label §8.5"),

    # -- Antiarrhythmics ---------------------------------------------------
    ("disopyramide", 5,
     "Strong anticholinergic and negative inotropic effects; heart failure "
     "risk in older adults. Avoid.",
     "FDA disopyramide label §8.5"),

    ("flecainide", 3,
     "Negative inotrope and proarrhythmic in structural heart disease, "
     "which is more common in older adults. Use only with careful "
     "cardiology guidance.",
     "FDA flecainide label §8.5; CAST trial"),

    # -- Polypharmacy considerations (placeholder rows for common combos) --
    ("tramadol", 3,
     "Opioid with serotonergic activity; confusion, falls, and seizure "
     "risk elevated in older adults, especially on other serotonergic "
     "drugs or with renal impairment.",
     "FDA tramadol label §5.7; Barbosa J et al., J Clin Med 2016"),
]


def build(db_path: str) -> int:
    """Insert curated geriatric PIM warnings. Returns rows inserted."""
    log.info("Geriatric PIM: starting build")
    conn = sqlite3.connect(db_path)
    inserted = 0
    skipped = 0

    try:
        for generic, severity, desc, citation in GERIATRIC_PIMS:
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
                log.debug("Geriatric PIM: no rxcui for '%s' - skipping", generic)
                skipped += 1
                continue

            rxcui, canonical = row
            try:
                conn.execute(
                    "INSERT INTO warnings "
                    "(drug_rxcui, drug_name, warning_type, population, "
                    " description, severity, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    (rxcui, canonical, WARNING_TYPE, POPULATION,
                     desc, severity, citation),
                )
                inserted += 1
            except sqlite3.IntegrityError as exc:
                log.warning("Geriatric PIM: insert error for %s: %s", generic, exc)

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info(
        "Geriatric PIM: inserted %d rows, skipped %d entries without rxnorm mapping",
        inserted, skipped,
    )
    return inserted
