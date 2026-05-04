"""Food-drug interactions source (Tier 3a).

Curated warnings for clinically significant food-drug interactions. Entries
target dietary components that either alter drug metabolism (CYP enzyme
inhibition, chelation) or produce additive pharmacodynamic effects
(tyramine + MAOI, alcohol + CNS depressants).

Stored in the existing `warnings` table with `warning_type='food_interaction'`
and `population` set to the food/dietary class name (e.g. "grapefruit juice",
"tyramine-rich foods", "vitamin K-rich foods"). This lets `get_drug_info`
surface food warnings alongside pregnancy / geriatric / lactation warnings
without a schema change.

Severity scale (1-5), matching schema.sql:
  5 - Avoid combination; life-threatening (e.g. tyramine + irreversible MAOI)
  4 - Serious; separate administration or monitor closely
  3 - Significant; counsel patient, check clinical response
  2 - Moderate; low absolute risk but worth documenting
  1 - Informational; minor absorption effect

Each entry cites a primary FDA label, FDA Drug Safety Communication, or
peer-reviewed clinical pharmacology source.
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)

WARNING_TYPE = "food_interaction"

# Each entry: (food_name, drug_generic_lower, severity, description, citation).
# drug_generic is looked up against rxnorm_lookup.generic_name at build time;
# entries whose drug isn't in the KB are skipped cleanly.
FOOD_INTERACTIONS: list[tuple[str, str, int, str, str]] = [

    # ── Grapefruit juice + CYP3A4 substrates ─────────────────────────────
    # Mechanism: furanocoumarins in grapefruit irreversibly inhibit intestinal
    # CYP3A4, raising serum levels of orally administered CYP3A4 substrates
    # 2-5× for 24-72 hours per glass. One glass can affect the next several doses.
    ("grapefruit juice", "simvastatin", 5,
     "Grapefruit juice inhibits intestinal CYP3A4, raising simvastatin "
     "levels several-fold and substantially increasing risk of myopathy, "
     "rhabdomyolysis, and acute renal failure. Avoid combination entirely.",
     "FDA simvastatin label §7 (Drug Interactions)"),
    ("grapefruit juice", "lovastatin", 5,
     "Lovastatin is extensively metabolized by intestinal CYP3A4; grapefruit "
     "juice produces a 10-15× increase in peak lovastatin levels and elevated "
     "rhabdomyolysis risk. Avoid combination.",
     "FDA lovastatin label §7"),
    ("grapefruit juice", "atorvastatin", 3,
     "Grapefruit juice moderately increases atorvastatin exposure via CYP3A4 "
     "inhibition. Routine consumption is generally avoided at doses ≥40 mg; "
     "occasional moderate intake is usually acceptable at lower doses.",
     "FDA atorvastatin label §7.5"),
    ("grapefruit juice", "amiodarone", 4,
     "Grapefruit juice increases amiodarone serum levels via CYP3A4 inhibition, "
     "elevating risk of QT prolongation and torsades de pointes. Avoid.",
     "FDA amiodarone label §7"),
    ("grapefruit juice", "nifedipine", 4,
     "Grapefruit juice raises nifedipine serum levels and can cause severe "
     "hypotension and reflex tachycardia. Avoid.",
     "FDA nifedipine label §7.1"),
    ("grapefruit juice", "felodipine", 4,
     "Felodipine bioavailability increases 3-5× with grapefruit juice due to "
     "intestinal CYP3A4 inhibition, causing hypotension and tachycardia. Avoid.",
     "FDA felodipine label §7; Bailey DG et al., Lancet 1991"),
    ("grapefruit juice", "tacrolimus", 4,
     "Grapefruit juice unpredictably increases tacrolimus trough levels, "
     "risking nephrotoxicity and neurotoxicity. Transplant patients should "
     "avoid grapefruit consumption.",
     "FDA tacrolimus label §7.1"),
    ("grapefruit juice", "cyclosporine", 4,
     "Grapefruit juice raises cyclosporine serum levels via CYP3A4 inhibition, "
     "increasing nephrotoxicity risk. Avoid.",
     "FDA cyclosporine label §7"),
    ("grapefruit juice", "sildenafil", 3,
     "Grapefruit juice modestly raises sildenafil exposure; symptomatic "
     "hypotension has been reported. Prefer not to co-ingest.",
     "FDA sildenafil label §7.1"),

    # ── Tyramine-rich foods + MAOIs ──────────────────────────────────────
    # Mechanism: dietary tyramine is normally degraded by intestinal MAO-A.
    # With an irreversible MAOI on board, tyramine enters circulation and
    # displaces noradrenaline from sympathetic terminals, causing hypertensive
    # crisis (severe headache, stroke, MI). Aged cheese, cured meats,
    # fermented soy (soy sauce, miso), draft beer, and red wine are high-risk.
    ("tyramine-rich foods", "phenelzine", 5,
     "Tyramine in aged cheese, cured meats, fermented soy (soy sauce, miso), "
     "draft beer, red wine, and overripe fruit can precipitate hypertensive "
     "crisis in patients taking irreversible MAOIs. Strict low-tyramine diet "
     "is required throughout therapy and for 2 weeks after discontinuation.",
     "FDA phenelzine label §5.1 and §17 (patient counseling)"),
    ("tyramine-rich foods", "tranylcypromine", 5,
     "Same mechanism as phenelzine: tyramine-induced hypertensive crisis. "
     "Strict dietary restriction throughout therapy and for 2 weeks after "
     "discontinuation.",
     "FDA tranylcypromine label §5.1"),
    ("tyramine-rich foods", "selegiline", 3,
     "At low doses (selective MAO-B), dietary restrictions are generally not "
     "required; at higher doses (>10 mg/day oral, or transdermal patches "
     "≥9 mg/24 h), selegiline loses MAO-B selectivity and tyramine precautions "
     "apply.",
     "FDA selegiline label §5.1 and §2 (dosage)"),

    # ── Vitamin K-rich foods + warfarin ──────────────────────────────────
    ("vitamin K-rich foods", "warfarin", 3,
     "Large or inconsistent intake of vitamin K-rich foods (kale, spinach, "
     "collard greens, broccoli, Brussels sprouts) antagonizes warfarin and "
     "lowers INR, increasing clot risk. Patients should keep vitamin K intake "
     "consistent from week to week rather than eliminating these foods.",
     "FDA warfarin label §7.3 and §17"),

    # ── Dairy products (calcium chelation) + tetracyclines / fluoroquinolones ──
    ("dairy products", "doxycycline", 3,
     "Calcium in milk and dairy chelates doxycycline and reduces absorption "
     "by 20-30%. Separate doses by at least 2 hours from dairy, antacids, "
     "iron, or calcium supplements.",
     "FDA doxycycline label §7.1"),
    ("dairy products", "tetracycline", 3,
     "Calcium chelates tetracycline and substantially reduces absorption. "
     "Take tetracycline 1 hour before or 2 hours after dairy, antacids, "
     "iron, or calcium-containing products.",
     "FDA tetracycline label §7.1"),
    ("dairy products", "ciprofloxacin", 3,
     "Divalent cations in dairy (calcium) chelate ciprofloxacin, reducing "
     "absorption by up to 50%. Take ciprofloxacin 2 hours before or 6 hours "
     "after dairy. Dairy taken as part of a full meal has less effect than "
     "dairy alone.",
     "FDA ciprofloxacin label §7.1"),
    ("dairy products", "levofloxacin", 3,
     "Like ciprofloxacin, levofloxacin is chelated by calcium. Separate "
     "from dairy by 2+ hours.",
     "FDA levofloxacin label §7.3"),
    ("dairy products", "alendronate", 4,
     "Calcium, antacids, and other minerals markedly reduce alendronate "
     "absorption. Must be taken with plain water on an empty stomach at least "
     "30 minutes before the first food, beverage, or medication of the day.",
     "FDA alendronate label §2 and §17"),

    # ── Alcohol (ethanol) + CNS depressants / sensitising drugs ─────────
    ("alcohol", "metronidazole", 4,
     "Concurrent alcohol with metronidazole has been associated with a "
     "disulfiram-like reaction (flushing, nausea, vomiting, tachycardia), "
     "though contemporary evidence suggests this is less consistent than "
     "previously thought. Avoid alcohol during therapy and for 72 hours "
     "after the last dose.",
     "FDA metronidazole label §5.2"),
    ("alcohol", "acetaminophen", 3,
     "Chronic heavy alcohol use depletes hepatic glutathione and upregulates "
     "CYP2E1, increasing acetaminophen hepatotoxicity risk. Chronic heavy "
     "drinkers should limit acetaminophen to ≤2 g/day and avoid higher doses.",
     "FDA acetaminophen OTC label (Alcohol Warning per 21 CFR 201.322)"),
    ("alcohol", "oxycodone", 5,
     "Alcohol potentiates opioid-induced respiratory depression and sedation, "
     "with documented fatalities. Patients on opioids must avoid alcohol.",
     "FDA oxycodone label boxed warning"),
    ("alcohol", "alprazolam", 5,
     "Alcohol compounds benzodiazepine-induced sedation and respiratory "
     "depression. Combination can be fatal. Avoid.",
     "FDA alprazolam label §5"),
    ("alcohol", "glipizide", 3,
     "Alcohol can cause hypoglycemia with sulfonylureas and (rarely) a "
     "disulfiram-like reaction. Moderate intake with food is generally "
     "tolerable; heavy or fasting intake should be avoided.",
     "FDA glipizide label §5 and §7"),

    # ── High-potassium foods + RAAS blockers / K-sparing diuretics ─────
    ("potassium-rich foods and salt substitutes", "lisinopril", 3,
     "Salt substitutes (typically potassium chloride) and large intake of "
     "potassium-rich foods (bananas, oranges, potatoes, leafy greens, "
     "tomato products) can produce clinically significant hyperkalemia in "
     "patients on ACE inhibitors, particularly those with reduced renal "
     "function. Monitor serum potassium.",
     "FDA lisinopril label §5.3"),
    ("potassium-rich foods and salt substitutes", "losartan", 3,
     "Same mechanism as ACE inhibitors: ARBs plus high dietary potassium or "
     "salt substitutes raise hyperkalemia risk. Monitor serum potassium.",
     "FDA losartan label §5.5"),
    ("potassium-rich foods and salt substitutes", "spironolactone", 4,
     "Spironolactone is a potassium-sparing diuretic; salt substitutes and "
     "high potassium intake can cause severe hyperkalemia with cardiac "
     "conduction effects. Advise against routine salt-substitute use.",
     "FDA spironolactone label §5.1"),

    # ── Caffeine + ciprofloxacin (CYP1A2 inhibition) ─────────────────────
    ("caffeine", "ciprofloxacin", 2,
     "Ciprofloxacin inhibits CYP1A2, slowing caffeine clearance and "
     "prolonging its half-life. Patients may experience heightened "
     "jitteriness, insomnia, and tachycardia with normal caffeine intake. "
     "Consider reducing caffeine during therapy.",
     "FDA ciprofloxacin label §7.1"),

    # ── High-fiber / calcium-rich meals + levothyroxine ──────────────────
    ("calcium / iron / high-fiber foods", "levothyroxine", 3,
     "Calcium, iron supplements, soy products, coffee, and high-fiber foods "
     "reduce levothyroxine absorption. Take levothyroxine on an empty stomach "
     "30-60 minutes before any food, beverage (other than water), or "
     "medication.",
     "FDA levothyroxine label §2 and §17"),

    # ── Licorice (glycyrrhizin) + digoxin, antihypertensives ────────────
    ("black licorice / glycyrrhizin", "digoxin", 3,
     "Glycyrrhizin in real licorice causes pseudo-hyperaldosteronism with "
     "hypokalemia, which sensitizes the heart to digoxin and increases "
     "arrhythmia risk. Limit licorice consumption.",
     "FDA digoxin label §7; Omar HR et al., Am J Cardiol 2012"),
]


def build(db_path: str) -> int:
    """Insert curated food-drug warnings into the warnings table.

    Idempotent: each row's (drug_rxcui, food) key is checked before insert
    so re-running doesn't create duplicates.
    """
    log.info("FoodInteractions: starting build for %d entries", len(FOOD_INTERACTIONS))
    conn = sqlite3.connect(db_path)
    inserted = 0
    skipped_no_rxnorm = 0
    skipped_duplicate = 0

    try:
        for food, generic, severity, description, citation in FOOD_INTERACTIONS:
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
                log.debug("FoodInteractions: no rxcui for %r - skipping", generic)
                continue
            rxcui, canonical = row

            # Idempotency: skip if (rxcui, food) already present.
            exists = conn.execute(
                "SELECT 1 FROM warnings "
                "WHERE drug_rxcui = ? AND warning_type = ? AND population = ? "
                "LIMIT 1",
                (rxcui, WARNING_TYPE, food),
            ).fetchone()
            if exists:
                skipped_duplicate += 1
                continue

            conn.execute(
                "INSERT INTO warnings "
                "(drug_rxcui, drug_name, warning_type, population, "
                " description, severity, source) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (rxcui, canonical, WARNING_TYPE, food,
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
        "FoodInteractions: inserted %d rows, skipped %d (no rxnorm), %d (duplicate)",
        inserted, skipped_no_rxnorm, skipped_duplicate,
    )
    return inserted
