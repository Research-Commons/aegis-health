"""NIH Office of Dietary Supplements (ODS) fact-sheet expansion source.

Complements the existing nih_dsld source by adding supplement-drug
interactions for ~30 additional supplements (vitamins, minerals, common
herbals) and fact-sheet summaries in the terms table.

All data is derived from NIH Office of Dietary Supplements Fact Sheets
(ods.od.nih.gov/factsheets) and cross-referenced with the Natural Medicines
Comprehensive Database and clinical pharmacology literature. Attribution
is surfaced via the ``source`` column.

Severity scale (1-5), matching schema.sql and sibling sources:
  5 - Contraindicated / avoid (e.g., red yeast rice + statin)
  4 - Serious / avoid unless monitored (e.g., vitamin K + warfarin)
  3 - Significant / monitor
  2 - Moderate / dose timing or mild additive
  1 - Minor / informational
"""
from __future__ import annotations

import logging
import sqlite3

log = logging.getLogger(__name__)

INTERACTION_SOURCE = "NIH Office of Dietary Supplements (ODS) Fact Sheet"
TERMS_CATEGORY = "supplement_info"

# Additional supplement-drug interactions sourced from NIH ODS fact sheets
# and peer-reviewed clinical pharmacology references.
ODS_INTERACTIONS: list[dict] = [

    # -- Vitamin K ---------------------------------------------------
    {"supplement": "Vitamin K", "drug": "Warfarin", "type": "antagonistic", "severity": 4,
     "desc": "Vitamin K is the direct antagonist of warfarin; sudden changes in "
             "vitamin K intake (supplements or leafy greens) destabilize INR.",
     "mechanism": "Vitamin K cycle - restores clotting factor synthesis warfarin blocks",
     "rec": "Keep vitamin K intake consistent; avoid starting or stopping K-rich supplements without discussing with clinician."},

    # -- Vitamin E (high dose) ---------------------------------------
    {"supplement": "Vitamin E", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "High-dose vitamin E (>400 IU/day) inhibits vitamin K-dependent "
             "carboxylation and has antiplatelet effects; may increase bleeding risk.",
     "mechanism": "Inhibition of vitamin K-dependent clotting factor carboxylation",
     "rec": "Avoid doses >400 IU/day or monitor INR closely."},
    {"supplement": "Vitamin E", "drug": "Aspirin", "type": "additive", "severity": 2,
     "desc": "Additive antiplatelet effect at high doses.",
     "mechanism": "Platelet aggregation inhibition",
     "rec": "Monitor for bruising or bleeding."},

    # -- Vitamin D ---------------------------------------------------
    {"supplement": "Vitamin D", "drug": "Digoxin", "type": "pharmacodynamic", "severity": 3,
     "desc": "Excessive vitamin D can cause hypercalcemia, which potentiates "
             "digoxin toxicity (arrhythmia risk).",
     "mechanism": "Hypercalcemia sensitizes myocardium to digoxin",
     "rec": "Avoid doses above UL (4,000 IU/day adults); monitor calcium if supplementing."},
    {"supplement": "Vitamin D", "drug": "Thiazide Diuretic", "type": "additive", "severity": 3,
     "desc": "Thiazides reduce calcium excretion; combined with high-dose vitamin D can cause hypercalcemia.",
     "mechanism": "Reduced renal calcium excretion plus increased absorption",
     "rec": "Monitor serum calcium periodically."},

    # -- Calcium -----------------------------------------------------
    {"supplement": "Calcium", "drug": "Levothyroxine", "type": "pharmacokinetic", "severity": 3,
     "desc": "Calcium binds levothyroxine in the GI tract, reducing absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by at least 4 hours."},
    {"supplement": "Calcium", "drug": "Ciprofloxacin", "type": "pharmacokinetic", "severity": 3,
     "desc": "Calcium chelates fluoroquinolones, reducing absorption and efficacy.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by 2-6 hours."},
    {"supplement": "Calcium", "drug": "Doxycycline", "type": "pharmacokinetic", "severity": 3,
     "desc": "Calcium chelates tetracyclines, reducing antibiotic absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Take tetracyclines 2 hours before or 4 hours after calcium."},
    {"supplement": "Calcium", "drug": "Alendronate", "type": "pharmacokinetic", "severity": 3,
     "desc": "Calcium binds bisphosphonates in GI tract, blocking absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Take bisphosphonate first thing in morning with plain water; wait 30+ minutes before calcium."},

    # -- Magnesium ---------------------------------------------------
    {"supplement": "Magnesium", "drug": "Ciprofloxacin", "type": "pharmacokinetic", "severity": 3,
     "desc": "Magnesium chelates fluoroquinolones, impairing absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Separate by 2-6 hours."},
    {"supplement": "Magnesium", "drug": "Alendronate", "type": "pharmacokinetic", "severity": 3,
     "desc": "Reduces bisphosphonate absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by at least 2 hours."},
    {"supplement": "Magnesium", "drug": "Tetracycline", "type": "pharmacokinetic", "severity": 3,
     "desc": "Magnesium chelates tetracyclines.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by 2-4 hours."},

    # -- Iron --------------------------------------------------------
    {"supplement": "Iron", "drug": "Levothyroxine", "type": "pharmacokinetic", "severity": 3,
     "desc": "Iron reduces levothyroxine absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by at least 4 hours."},
    {"supplement": "Iron", "drug": "Ciprofloxacin", "type": "pharmacokinetic", "severity": 3,
     "desc": "Iron chelates fluoroquinolones.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by 2 hours."},
    {"supplement": "Iron", "drug": "Doxycycline", "type": "pharmacokinetic", "severity": 3,
     "desc": "Iron chelates tetracyclines; reduced antibiotic efficacy.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by 2-4 hours."},

    # -- Zinc --------------------------------------------------------
    {"supplement": "Zinc", "drug": "Ciprofloxacin", "type": "pharmacokinetic", "severity": 3,
     "desc": "Zinc reduces fluoroquinolone absorption.",
     "mechanism": "GI tract chelation",
     "rec": "Separate dosing by 2 hours."},
    {"supplement": "Zinc", "drug": "Doxycycline", "type": "pharmacokinetic", "severity": 3,
     "desc": "Zinc chelates tetracyclines.",
     "mechanism": "GI tract chelation",
     "rec": "Separate by 2-4 hours."},

    # -- Potassium ---------------------------------------------------
    {"supplement": "Potassium", "drug": "Lisinopril", "type": "additive", "severity": 4,
     "desc": "ACE inhibitors increase serum potassium; supplementation risks hyperkalemia and arrhythmia.",
     "mechanism": "Reduced aldosterone + added potassium load",
     "rec": "Avoid supplementation unless under clinician guidance with monitoring."},
    {"supplement": "Potassium", "drug": "Spironolactone", "type": "additive", "severity": 5,
     "desc": "Spironolactone is potassium-sparing; combining with potassium supplements can cause severe hyperkalemia, cardiac arrest.",
     "mechanism": "Aldosterone antagonism + potassium load",
     "rec": "Avoid combination unless clinician-directed with close lab monitoring."},
    {"supplement": "Potassium", "drug": "Losartan", "type": "additive", "severity": 4,
     "desc": "ARB-associated potassium retention plus supplementation increases hyperkalemia risk.",
     "mechanism": "Reduced aldosterone + potassium load",
     "rec": "Avoid unless clinician-directed."},

    # -- Melatonin ---------------------------------------------------
    {"supplement": "Melatonin", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "Some reports suggest melatonin may increase INR.",
     "mechanism": "Possible platelet and coagulation effects",
     "rec": "Monitor INR when starting."},
    {"supplement": "Melatonin", "drug": "Alprazolam", "type": "additive", "severity": 3,
     "desc": "Additive sedation and CNS depression.",
     "mechanism": "CNS depression",
     "rec": "Use with caution; avoid driving."},
    {"supplement": "Melatonin", "drug": "Cyclosporine", "type": "pharmacokinetic", "severity": 3,
     "desc": "Melatonin may alter immunosuppressant levels.",
     "mechanism": "Possible CYP modulation",
     "rec": "Consult transplant team before starting."},

    # -- Coenzyme Q10 ------------------------------------------------
    {"supplement": "Coenzyme Q10", "drug": "Warfarin", "type": "antagonistic", "severity": 3,
     "desc": "CoQ10 structurally resembles vitamin K and may reduce warfarin efficacy.",
     "mechanism": "Vitamin K-like activity",
     "rec": "Monitor INR when starting or stopping CoQ10."},
    {"supplement": "Coenzyme Q10", "drug": "Atorvastatin", "type": "additive", "severity": 1,
     "desc": "Statins reduce endogenous CoQ10; supplementation may help mitigate statin-associated myalgia (evidence mixed).",
     "mechanism": "Restoration of mevalonate-pathway intermediate",
     "rec": "No safety concern; discuss benefit with clinician."},

    # -- SAM-e (S-adenosylmethionine) --------------------------------
    {"supplement": "SAM-e", "drug": "Sertraline", "type": "additive", "severity": 4,
     "desc": "Additive serotonergic effect; serotonin syndrome risk with SSRIs.",
     "mechanism": "Serotonin modulation",
     "rec": "Avoid combination."},
    {"supplement": "SAM-e", "drug": "Phenelzine", "type": "additive", "severity": 5,
     "desc": "Severe serotonin syndrome risk with MAOIs.",
     "mechanism": "Additive serotonergic activity",
     "rec": "Contraindicated."},
    {"supplement": "SAM-e", "drug": "Tramadol", "type": "additive", "severity": 4,
     "desc": "Additive serotonergic effects; serotonin syndrome risk.",
     "mechanism": "Serotonin modulation",
     "rec": "Avoid combination."},

    # -- 5-HTP -------------------------------------------------------
    {"supplement": "5-HTP", "drug": "Sertraline", "type": "additive", "severity": 5,
     "desc": "5-HTP is a direct serotonin precursor; combination with SSRIs "
             "carries high serotonin syndrome risk.",
     "mechanism": "Direct serotonin biosynthesis + reuptake inhibition",
     "rec": "Avoid combination."},
    {"supplement": "5-HTP", "drug": "Phenelzine", "type": "additive", "severity": 5,
     "desc": "Severe serotonin syndrome risk with MAOIs; contraindicated.",
     "mechanism": "Direct serotonin precursor + MAO inhibition",
     "rec": "Contraindicated."},

    # -- L-Tryptophan ------------------------------------------------
    {"supplement": "L-Tryptophan", "drug": "Sertraline", "type": "additive", "severity": 4,
     "desc": "Tryptophan is a serotonin precursor; combination with SSRIs increases serotonin syndrome risk.",
     "mechanism": "Serotonin biosynthesis + reuptake inhibition",
     "rec": "Avoid combination."},
    {"supplement": "L-Tryptophan", "drug": "Tramadol", "type": "additive", "severity": 4,
     "desc": "Additive serotonergic effect.",
     "mechanism": "Serotonin activity",
     "rec": "Avoid combination."},

    # -- DHEA --------------------------------------------------------
    {"supplement": "DHEA", "drug": "Tamoxifen", "type": "antagonistic", "severity": 4,
     "desc": "DHEA is a precursor to estradiol; may antagonize tamoxifen's action in hormone-sensitive breast cancer.",
     "mechanism": "Estrogen precursor",
     "rec": "Avoid combination in breast cancer patients."},
    {"supplement": "DHEA", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "May alter clotting factor synthesis.",
     "mechanism": "Hormonal effects on hemostasis",
     "rec": "Monitor INR."},

    # -- Red Yeast Rice (contains natural lovastatin) ----------------
    {"supplement": "Red Yeast Rice", "drug": "Atorvastatin", "type": "additive", "severity": 5,
     "desc": "Red yeast rice contains monacolin K (identical to lovastatin); "
             "additive statin exposure markedly increases myopathy and rhabdomyolysis risk.",
     "mechanism": "Dual HMG-CoA reductase inhibition",
     "rec": "Avoid combining with any prescription statin."},
    {"supplement": "Red Yeast Rice", "drug": "Simvastatin", "type": "additive", "severity": 5,
     "desc": "Additive statin exposure; rhabdomyolysis risk.",
     "mechanism": "Dual HMG-CoA reductase inhibition",
     "rec": "Contraindicated with prescription statins."},

    # -- Kava --------------------------------------------------------
    {"supplement": "Kava", "drug": "Alprazolam", "type": "additive", "severity": 4,
     "desc": "Additive CNS depression and potentiated hepatotoxicity.",
     "mechanism": "GABA-A modulation + shared hepatic metabolism",
     "rec": "Avoid combination."},
    {"supplement": "Kava", "drug": "Acetaminophen", "type": "additive", "severity": 4,
     "desc": "Kava is hepatotoxic; additive liver injury risk with other hepatotoxins at higher doses.",
     "mechanism": "Shared hepatotoxicity",
     "rec": "Avoid regular high-dose acetaminophen with kava."},

    # -- Glucosamine -------------------------------------------------
    {"supplement": "Glucosamine", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "May increase INR and bleeding risk in some patients.",
     "mechanism": "Uncertain; possible cytokine or clotting factor effects",
     "rec": "Monitor INR when starting or stopping."},

    # -- Chondroitin -------------------------------------------------
    {"supplement": "Chondroitin", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "Case reports of increased INR and bleeding.",
     "mechanism": "Possible antiplatelet or coagulation effect",
     "rec": "Monitor INR."},

    # -- Biotin (lab test interference, not pharm interaction) -------
    {"supplement": "Biotin", "drug": "Levothyroxine", "type": "lab_interference", "severity": 2,
     "desc": "High-dose biotin (>5 mg/day) interferes with many immunoassays "
             "(TSH, T4, troponin), causing falsely low or high results. Drug "
             "itself not affected, but lab monitoring is disrupted.",
     "mechanism": "Biotin-streptavidin assay interference",
     "rec": "Hold biotin 48+ hours before lab draws."},

    # -- Cinnamon (cassia, high-dose) --------------------------------
    {"supplement": "Cinnamon", "drug": "Metformin", "type": "additive", "severity": 2,
     "desc": "Modest additive glucose-lowering effect; hypoglycemia risk if doses increased simultaneously.",
     "mechanism": "Insulin sensitization",
     "rec": "Monitor fasting glucose."},
    {"supplement": "Cinnamon", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "High-dose cassia cinnamon contains coumarin; may potentiate warfarin.",
     "mechanism": "Coumarin content",
     "rec": "Avoid high-dose cinnamon extracts; culinary use probably fine."},

    # -- Grapefruit Juice (CYP3A4 inhibitor) -------------------------
    {"supplement": "Grapefruit Juice", "drug": "Simvastatin", "type": "pharmacokinetic", "severity": 4,
     "desc": "Grapefruit juice inhibits intestinal CYP3A4, dramatically increasing simvastatin levels and myopathy risk.",
     "mechanism": "Intestinal CYP3A4 inhibition",
     "rec": "Avoid grapefruit juice with simvastatin."},
    {"supplement": "Grapefruit Juice", "drug": "Amlodipine", "type": "pharmacokinetic", "severity": 3,
     "desc": "Modest increase in amlodipine levels; hypotension risk.",
     "mechanism": "CYP3A4 inhibition",
     "rec": "Limit grapefruit juice intake."},
    {"supplement": "Grapefruit Juice", "drug": "Apixaban", "type": "pharmacokinetic", "severity": 3,
     "desc": "May increase apixaban levels, elevating bleeding risk.",
     "mechanism": "CYP3A4 and P-gp inhibition",
     "rec": "Avoid large amounts of grapefruit juice."},
    {"supplement": "Grapefruit Juice", "drug": "Tacrolimus", "type": "pharmacokinetic", "severity": 4,
     "desc": "Substantially raises tacrolimus levels, causing nephrotoxicity risk.",
     "mechanism": "Intestinal CYP3A4 inhibition",
     "rec": "Avoid grapefruit juice while on tacrolimus."},

    # -- Hawthorn ----------------------------------------------------
    {"supplement": "Hawthorn", "drug": "Digoxin", "type": "additive", "severity": 3,
     "desc": "Hawthorn has cardiotonic effects that may potentiate digoxin.",
     "mechanism": "Additive positive inotropy",
     "rec": "Monitor for digoxin toxicity."},

    # -- Yohimbe -----------------------------------------------------
    {"supplement": "Yohimbe", "drug": "Phenelzine", "type": "additive", "severity": 5,
     "desc": "Yohimbe is a sympathomimetic; combination with MAOI can cause hypertensive crisis.",
     "mechanism": "Alpha-2 antagonism + MAO inhibition",
     "rec": "Contraindicated."},
    {"supplement": "Yohimbe", "drug": "Sertraline", "type": "additive", "severity": 3,
     "desc": "May increase blood pressure and anxiety.",
     "mechanism": "Sympathomimetic effect",
     "rec": "Avoid combination."},

    # -- Bitter Orange (synephrine) ----------------------------------
    {"supplement": "Bitter Orange", "drug": "Phenelzine", "type": "additive", "severity": 5,
     "desc": "Synephrine is a sympathomimetic; MAOI combination risks hypertensive crisis.",
     "mechanism": "Sympathomimetic + MAO inhibition",
     "rec": "Contraindicated."},

    # -- Cranberry (high-dose extract) -------------------------------
    {"supplement": "Cranberry Extract", "drug": "Warfarin", "type": "pharmacodynamic", "severity": 3,
     "desc": "High-dose cranberry extract has been associated with INR elevation and bleeding in case reports.",
     "mechanism": "Possible CYP2C9 inhibition",
     "rec": "Limit large doses; monitor INR if consuming regularly."},
]


# Plain-language fact-sheet summaries for the terms table.
# Each entry adds one row with category='supplement_info' and the ODS URL.
ODS_FACT_SHEETS: list[dict] = [
    {"term": "Vitamin D",
     "definition": "Fat-soluble vitamin important for bone health, immune function, "
                   "and muscle function. The body produces it in response to sunlight. "
                   "Adult RDA: 600-800 IU/day; tolerable upper limit: 4,000 IU/day. "
                   "Deficiency is common and is associated with osteoporosis, "
                   "muscle weakness, and increased fall risk.",
     "url": "https://ods.od.nih.gov/factsheets/VitaminD-Consumer/"},

    {"term": "Vitamin B12",
     "definition": "Water-soluble vitamin required for red blood cell formation, "
                   "neurological function, and DNA synthesis. Adult RDA: 2.4 mcg/day. "
                   "Deficiency causes megaloblastic anemia and neurological symptoms. "
                   "Common in older adults, vegans, and people taking metformin or "
                   "long-term PPIs.",
     "url": "https://ods.od.nih.gov/factsheets/VitaminB12-Consumer/"},

    {"term": "Vitamin K",
     "definition": "Fat-soluble vitamin essential for blood clotting and bone health. "
                   "Adequate intake: 90-120 mcg/day for adults. Found in leafy greens, "
                   "natto, and fermented foods. Directly antagonizes warfarin; patients "
                   "on warfarin should keep vitamin K intake consistent.",
     "url": "https://ods.od.nih.gov/factsheets/VitaminK-Consumer/"},

    {"term": "Vitamin E",
     "definition": "Fat-soluble antioxidant. Adult RDA: 15 mg/day alpha-tocopherol; "
                   "tolerable upper limit: 1,000 mg/day. High-dose supplements "
                   "(>400 IU) may increase bleeding risk, especially with anticoagulants.",
     "url": "https://ods.od.nih.gov/factsheets/VitaminE-Consumer/"},

    {"term": "Calcium",
     "definition": "Essential mineral for bone health, muscle contraction, and nerve "
                   "function. Adult RDA: 1,000-1,200 mg/day. Chelates many drugs "
                   "(levothyroxine, fluoroquinolones, tetracyclines, bisphosphonates) "
                   "in the GI tract; timing supplements away from these medications "
                   "is important.",
     "url": "https://ods.od.nih.gov/factsheets/Calcium-Consumer/"},

    {"term": "Magnesium",
     "definition": "Essential mineral involved in hundreds of enzymatic reactions "
                   "including muscle and nerve function, blood pressure regulation, "
                   "and bone health. Adult RDA: 310-420 mg/day. High doses can cause "
                   "diarrhea; chelates fluoroquinolones and tetracyclines.",
     "url": "https://ods.od.nih.gov/factsheets/Magnesium-Consumer/"},

    {"term": "Iron",
     "definition": "Essential mineral for hemoglobin synthesis and oxygen transport. "
                   "Adult RDA: 8 mg/day (men), 18 mg/day (premenopausal women). "
                   "Common side effects include constipation and GI upset. Reduces "
                   "absorption of levothyroxine and fluoroquinolones.",
     "url": "https://ods.od.nih.gov/factsheets/Iron-Consumer/"},

    {"term": "Zinc",
     "definition": "Essential mineral for immune function, wound healing, and DNA "
                   "synthesis. Adult RDA: 8-11 mg/day; tolerable upper limit: 40 mg/day. "
                   "Long-term high-dose supplementation can cause copper deficiency.",
     "url": "https://ods.od.nih.gov/factsheets/Zinc-Consumer/"},

    {"term": "Folate (Folic Acid)",
     "definition": "B-vitamin essential for DNA synthesis and red blood cell formation. "
                   "Adult RDA: 400 mcg DFE/day; 600 mcg during pregnancy. Preconception "
                   "and early-pregnancy supplementation of 400-800 mcg/day reduces "
                   "neural tube defects (USPSTF Grade A recommendation).",
     "url": "https://ods.od.nih.gov/factsheets/Folate-Consumer/"},

    {"term": "Omega-3 Fatty Acids",
     "definition": "Essential fatty acids (EPA and DHA) important for cardiovascular "
                   "and cognitive health. Typical supplement: 1-4 g/day fish oil. "
                   "High doses may increase bleeding time; modest effect on INR with "
                   "warfarin at doses >3 g/day.",
     "url": "https://ods.od.nih.gov/factsheets/Omega3FattyAcids-Consumer/"},

    {"term": "Melatonin",
     "definition": "Hormone produced by the pineal gland that regulates sleep-wake "
                   "cycles. Supplemental doses 0.5-5 mg at bedtime are commonly used "
                   "for sleep and jet lag. Generally well tolerated; may interact with "
                   "sedatives, warfarin, and immunosuppressants.",
     "url": "https://ods.od.nih.gov/factsheets/Melatonin-Consumer/"},

    {"term": "Coenzyme Q10",
     "definition": "Antioxidant produced naturally by the body and found in many foods. "
                   "Levels decline with age and statin use. Typical supplement dose: "
                   "100-200 mg/day. May modestly reduce warfarin efficacy via vitamin "
                   "K-like activity.",
     "url": "https://ods.od.nih.gov/factsheets/CoenzymeQ10-Consumer/"},

    {"term": "Probiotics",
     "definition": "Live microorganisms intended to provide health benefits, especially "
                   "for gut health. Generally safe for healthy adults; caution in "
                   "severely immunocompromised patients (transplant recipients, "
                   "critically ill) because of rare bloodstream infection reports.",
     "url": "https://ods.od.nih.gov/factsheets/Probiotics-Consumer/"},

    {"term": "St. John's Wort",
     "definition": "Herbal supplement used for mild-to-moderate depression. Major "
                   "safety concern: potent inducer of CYP3A4 and P-glycoprotein, "
                   "reducing levels of many drugs including warfarin, cyclosporine, "
                   "oral contraceptives, HIV protease inhibitors, and DOACs. Also "
                   "carries serotonin syndrome risk with SSRIs/SNRIs/MAOIs.",
     "url": "https://ods.od.nih.gov/factsheets/StJohnsWort-Consumer/"},

    {"term": "Turmeric / Curcumin",
     "definition": "Herbal supplement used for inflammation and joint pain. Typical "
                   "supplement doses 500-2,000 mg curcumin/day. Generally well "
                   "tolerated; may enhance bleeding risk with anticoagulants and "
                   "additively lower blood glucose with antidiabetics.",
     "url": "https://ods.od.nih.gov/factsheets/Turmeric-Consumer/"},

    {"term": "Melatonin Safety",
     "definition": "Doses typically effective for sleep are 0.5-3 mg; higher doses do "
                   "not consistently improve efficacy and may cause morning grogginess "
                   "or vivid dreams. Not recommended in children without clinician "
                   "guidance. Has been associated with INR changes in warfarin patients.",
     "url": "https://ods.od.nih.gov/factsheets/Melatonin-HealthProfessional/"},

    {"term": "Red Yeast Rice",
     "definition": "Fermented rice product that naturally contains monacolin K, "
                   "chemically identical to the prescription statin lovastatin. Used "
                   "for mild cholesterol reduction. Same muscle, liver, and kidney "
                   "risks as prescription statins; combining with any statin is "
                   "contraindicated.",
     "url": "https://ods.od.nih.gov/factsheets/RedYeastRice-Consumer/"},

    {"term": "Kava",
     "definition": "Root extract used for anxiety relief. Associated with rare but "
                   "serious liver injury (hepatitis, fulminant failure) leading to "
                   "bans in several countries. Avoid with other hepatotoxins and in "
                   "patients with underlying liver disease.",
     "url": "https://ods.od.nih.gov/factsheets/Kava-Consumer/"},
]


def build(db_path: str) -> int:
    """Insert ODS-sourced supplement interactions and fact sheets.

    Returns total rows inserted (supplements + terms).
    """
    log.info("ODS Supplements: starting build")
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        # Interactions -> supplements table
        for ix in ODS_INTERACTIONS:
            try:
                conn.execute(
                    "INSERT INTO supplements "
                    "(supplement_name, interacting_drug, interaction_type, "
                    " severity, description, mechanism, recommendation, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (ix["supplement"], ix["drug"], ix["type"],
                     ix["severity"], ix["desc"], ix["mechanism"], ix["rec"],
                     INTERACTION_SOURCE),
                )
                inserted += 1
            except sqlite3.IntegrityError as exc:
                log.warning("ODS: insert error for %s + %s: %s",
                            ix["supplement"], ix["drug"], exc)

        # Fact sheet summaries -> terms table
        for fs in ODS_FACT_SHEETS:
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO terms "
                    "(term, definition, category, url, source) "
                    "VALUES (?, ?, ?, ?, ?)",
                    (fs["term"], fs["definition"], TERMS_CATEGORY,
                     fs["url"], INTERACTION_SOURCE),
                )
                inserted += 1
            except sqlite3.IntegrityError:
                pass

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("ODS Supplements: inserted %d rows total", inserted)
    return inserted
