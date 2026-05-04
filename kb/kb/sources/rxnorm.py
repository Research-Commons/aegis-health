"""RxNorm source – maps brand names to generic names and RxCUI codes.

Uses the RxNorm REST API (https://rxnav.nlm.nih.gov/REST/) with a
hardcoded fallback for the 50 most common drugs when the API is
unavailable.
"""
from __future__ import annotations

import logging
import sqlite3
import time
from typing import Any

import requests

log = logging.getLogger(__name__)

RXNORM_BASE = "https://rxnav.nlm.nih.gov/REST"
REQUEST_TIMEOUT = 15
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0
RATE_LIMIT_SLEEP = 0.12  # ~8 req/s; RxNorm allows ~20/s

# fmt: off
SEED_DRUGS: list[str] = [
    # ── Top 150 Rx ──────────────────────────────────────────────────
    "Lisinopril", "Atorvastatin", "Metformin", "Amlodipine", "Metoprolol",
    "Omeprazole", "Losartan", "Albuterol", "Gabapentin", "Hydrochlorothiazide",
    "Sertraline", "Simvastatin", "Montelukast", "Escitalopram", "Rosuvastatin",
    "Levothyroxine", "Acetaminophen with Codeine", "Pantoprazole", "Furosemide",
    "Fluticasone", "Amoxicillin", "Trazodone", "Duloxetine", "Prednisone",
    "Tamsulosin", "Pravastatin", "Meloxicam", "Clopidogrel", "Carvedilol",
    "Tramadol", "Bupropion", "Venlafaxine", "Cyclobenzaprine", "Spironolactone",
    "Citalopram", "Alprazolam", "Clonazepam", "Doxycycline", "Potassium Chloride",
    "Buspirone", "Glipizide", "Warfarin", "Propranolol", "Oxycodone",
    "Azithromycin", "Cetirizine", "Fluoxetine", "Linagliptin", "Empagliflozin",
    "Sitagliptin", "Apixaban", "Rivaroxaban", "Insulin Glargine", "Insulin Lispro",
    "Insulin Aspart", "Methotrexate", "Hydroxychloroquine", "Adalimumab",
    "Etanercept", "Levetiracetam", "Topiramate", "Lamotrigine", "Pregabalin",
    "Aripiprazole", "Quetiapine", "Olanzapine", "Risperidone", "Lithium",
    "Methylphenidate", "Amphetamine", "Lisdexamfetamine", "Clonidine",
    "Guanfacine", "Atomoxetine", "Finasteride", "Dutasteride", "Sildenafil",
    "Tadalafil", "Sumatriptan", "Rizatriptan", "Ondansetron", "Promethazine",
    "Metoclopramide", "Dicyclomine", "Ranitidine", "Famotidine", "Esomeprazole",
    "Lansoprazole", "Dexlansoprazole", "Sucralfate", "Misoprostol",
    "Ciprofloxacin", "Levofloxacin", "Amoxicillin / Clavulanate",
    "Cephalexin", "Ceftriaxone", "Sulfamethoxazole / Trimethoprim",
    "Nitrofurantoin", "Clindamycin", "Metronidazole", "Fluconazole",
    "Valacyclovir", "Acyclovir", "Oseltamivir", "Benzonatate",
    "Dextromethorphan", "Guaifenesin", "Tiotropium", "Budesonide",
    "Mometasone", "Triamcinolone", "Hydrocortisone", "Betamethasone",
    "Clobetasol", "Mupirocin", "Ketoconazole", "Terbinafine",
    "Latanoprost", "Timolol", "Brimonidine", "Dorzolamide",
    "Azelastine", "Olopatadine", "Moxifloxacin",
    "Pioglitazone", "Glimepiride", "Canagliflozin", "Dapagliflozin",
    "Liraglutide", "Semaglutide", "Dulaglutide", "Tirzepatide",
    "Ezetimibe", "Fenofibrate", "Niacin", "Diltiazem", "Verapamil",
    "Isosorbide Mononitrate", "Nitroglycerin", "Hydralazine", "Doxazosin",
    "Terazosin", "Enalapril", "Ramipril", "Valsartan", "Irbesartan",
    "Olmesartan", "Telmisartan", "Sacubitril / Valsartan",
    "Digoxin", "Amiodarone", "Sotalol", "Flecainide",
    # ── Pain / opioids ─────────────────────────────────────────────
    "Morphine", "Hydromorphone", "Fentanyl", "Methadone",
    "Hydrocodone / Acetaminophen", "Buprenorphine", "Buprenorphine / Naloxone",
    "Codeine", "Naloxone", "Naltrexone",
    # ── Anti-seizure / neuro ───────────────────────────────────────
    "Carbamazepine", "Oxcarbazepine", "Valproic Acid", "Divalproex",
    "Phenytoin", "Lacosamide", "Zonisamide", "Brivaracetam",
    "Perampanel", "Rufinamide",
    # ── Parkinson's / Alzheimer's ──────────────────────────────────
    "Carbidopa / Levodopa", "Pramipexole", "Ropinirole", "Selegiline",
    "Rasagiline", "Amantadine", "Entacapone", "Donepezil", "Memantine",
    "Rivastigmine", "Galantamine",
    # ── Migraine ───────────────────────────────────────────────────
    "Zolmitriptan", "Eletriptan", "Naratriptan", "Rimegepant",
    "Ubrogepant", "Erenumab", "Galcanezumab", "Fremanezumab",
    # ── Multiple sclerosis ─────────────────────────────────────────
    "Dimethyl Fumarate", "Teriflunomide", "Fingolimod", "Ocrelizumab",
    "Natalizumab", "Glatiramer Acetate", "Interferon Beta-1a",
    # ── Gout ───────────────────────────────────────────────────────
    "Allopurinol", "Febuxostat", "Colchicine", "Probenecid",
    # ── Osteoporosis ───────────────────────────────────────────────
    "Alendronate", "Risedronate", "Ibandronate", "Zoledronic Acid",
    "Denosumab", "Teriparatide", "Romosozumab",
    # ── Thyroid / endocrine ────────────────────────────────────────
    "Methimazole", "Propylthiouracil", "Liothyronine", "Prednisolone",
    "Dexamethasone", "Methylprednisolone", "Fludrocortisone",
    # ── Contraceptives / hormonal ──────────────────────────────────
    "Ethinyl Estradiol / Norethindrone", "Ethinyl Estradiol / Norgestimate",
    "Ethinyl Estradiol / Drospirenone", "Levonorgestrel",
    "Medroxyprogesterone", "Estradiol", "Conjugated Estrogens",
    "Testosterone",
    # ── Biologics / immunology ─────────────────────────────────────
    "Infliximab", "Certolizumab Pegol", "Golimumab", "Ustekinumab",
    "Secukinumab", "Ixekizumab", "Risankizumab", "Guselkumab",
    "Tofacitinib", "Baricitinib", "Upadacitinib", "Abatacept",
    "Rituximab", "Tocilizumab", "Sarilumab",
    # ── IBD / GI ───────────────────────────────────────────────────
    "Mesalamine", "Sulfasalazine", "Budesonide Oral", "Vedolizumab",
    "Ursodiol", "Lubiprostone", "Linaclotide", "Plecanatide",
    "Rifaximin", "Ondansetron ODT",
    # ── Oncology (common oral & targeted) ──────────────────────────
    "Tamoxifen", "Letrozole", "Anastrozole", "Exemestane", "Fulvestrant",
    "Palbociclib", "Ribociclib", "Abemaciclib",
    "Imatinib", "Dasatinib", "Nilotinib",
    "Erlotinib", "Osimertinib", "Gefitinib",
    "Ibrutinib", "Venetoclax",
    "Olaparib", "Rucaparib", "Niraparib",
    "Enzalutamide", "Abiraterone", "Apalutamide",
    "Leuprolide", "Goserelin",
    "Methotrexate Oncology",
    # ── HIV / ART ──────────────────────────────────────────────────
    "Tenofovir Disoproxil Fumarate", "Tenofovir Alafenamide",
    "Emtricitabine", "Dolutegravir", "Bictegravir",
    "Darunavir", "Ritonavir",
    "Bictegravir / Emtricitabine / Tenofovir Alafenamide",
    "Emtricitabine / Tenofovir Disoproxil Fumarate",
    # ── Hepatitis C / B ────────────────────────────────────────────
    "Ledipasvir / Sofosbuvir", "Sofosbuvir / Velpatasvir",
    "Glecaprevir / Pibrentasvir", "Entecavir",
    # ── Anticoagulants / antiplatelets ─────────────────────────────
    "Heparin", "Enoxaparin", "Dalteparin", "Fondaparinux",
    "Dabigatran", "Edoxaban", "Argatroban", "Bivalirudin",
    "Prasugrel", "Ticagrelor", "Cangrelor",
    # ── Vasopressors / critical care ───────────────────────────────
    "Norepinephrine", "Epinephrine", "Dopamine", "Dobutamine",
    "Vasopressin", "Milrinone",
    # ── Transplant immunosuppressants ──────────────────────────────
    "Tacrolimus", "Cyclosporine", "Mycophenolate Mofetil", "Sirolimus",
    "Everolimus", "Azathioprine",
    # ── Respiratory biologics ──────────────────────────────────────
    "Omalizumab", "Mepolizumab", "Benralizumab", "Dupilumab", "Tezepelumab",
    # ── Insulins (additional) ──────────────────────────────────────
    "Insulin Detemir", "Insulin Degludec", "Insulin NPH", "Insulin Regular",
    # ── Kidney / electrolyte ───────────────────────────────────────
    "Tolvaptan", "Patiromer", "Sodium Polystyrene Sulfonate",
    "Sevelamer", "Calcitriol", "Cinacalcet",
    # ── Antiemetics additional ─────────────────────────────────────
    "Granisetron", "Aprepitant", "Palonosetron", "Prochlorperazine",
    # ── Eye drops additional ───────────────────────────────────────
    "Travoprost", "Bimatoprost", "Cyclosporine Ophthalmic", "Pilocarpine",
    # ── Pediatric & specialty ──────────────────────────────────────
    "Epinephrine Auto-Injector", "Epinephrine / Lidocaine",
    "Levalbuterol", "Ipratropium / Albuterol",
    "Fluticasone / Salmeterol", "Budesonide / Formoterol",
    "Tiotropium / Olodaterol",
    # ── Antifungals ────────────────────────────────────────────────
    "Itraconazole", "Voriconazole", "Posaconazole", "Nystatin",
    "Griseofulvin",
    # ── Antivirals (non-HIV/HCV) ───────────────────────────────────
    "Valganciclovir", "Ganciclovir", "Famciclovir", "Remdesivir",
    # ── Miscellaneous high-use ─────────────────────────────────────
    "Methotrexate Rheumatology", "Leflunomide", "Hydroxyurea",
    "Mesna", "Allopurinol Pediatric", "Pyridoxine",
    "Calcium Gluconate IV", "Magnesium Sulfate",
    "Potassium Chloride IV", "Flumazenil",
    # ── Coverage-gap additions (PIM / LactMed / anchor cases) ──────
    # Tricyclic antidepressants
    "Amitriptyline", "Nortriptyline", "Doxepin", "Imipramine",
    "Clomipramine", "Desipramine",
    # Typical antipsychotics (PIM-relevant)
    "Haloperidol", "Chlorpromazine", "Fluphenazine", "Perphenazine",
    "Thioridazine",
    # More benzodiazepines
    "Diazepam", "Lorazepam", "Temazepam", "Oxazepam", "Triazolam",
    "Midazolam", "Chlordiazepoxide",
    # Z-drugs + melatonin agonist
    "Eszopiclone", "Zaleplon", "Ramelteon",
    # Other psychiatric
    "Mirtazapine", "Paroxetine", "Phenelzine", "Tranylcypromine",
    # Muscle relaxants
    "Carisoprodol", "Methocarbamol", "Orphenadrine",
    # Antihistamines (first-gen, PIM-relevant)
    "Hydroxyzine", "Chlorpheniramine",
    # NSAIDs (additional)
    "Celecoxib", "Diclofenac", "Indomethacin", "Ketorolac",
    # Antibiotics (additional)
    "Ampicillin", "Trimethoprim / Sulfamethoxazole",
    # Cardiovascular (additional)
    "Atenolol", "Nadolol", "Betaxolol", "Captopril",
    "Nifedipine", "Disopyramide", "Gemfibrozil",
    "Chlorothiazide", "Indapamide", "Eplerenone",
    # Antidiabetic (older)
    "Glyburide", "Chlorpropamide",
    # GU anticholinergics (PIM-relevant)
    "Oxybutynin", "Tolterodine", "Hyoscyamine", "Dicyclomine",
    # Opioids (additional)
    "Meperidine", "Pentazocine",
    # Other
    "Desmopressin", "Isotretinoin",
    # ── Top 80 OTC ──────────────────────────────────────────────────
    "Acetaminophen", "Ibuprofen", "Aspirin", "Naproxen", "Diphenhydramine",
    "Loratadine", "Fexofenadine", "Loperamide", "Bismuth Subsalicylate",
    "Calcium Carbonate", "Magnesium Hydroxide", "Simethicone",
    "Pseudoephedrine", "Phenylephrine", "Oxymetazoline", "Docusate",
    "Polyethylene Glycol 3350", "Bisacodyl", "Sennosides", "Psyllium",
    "Melatonin", "Vitamin D3", "Vitamin C", "Vitamin B12", "Folic Acid",
    "Iron Sulfate", "Zinc", "Magnesium Oxide", "Calcium Citrate",
    "Potassium Gluconate", "Biotin", "Collagen", "Probiotics",
    "Glucosamine", "Chondroitin", "Coenzyme Q10", "Alpha Lipoic Acid",
    "Lutein", "Omega-3 Fatty Acids", "Cranberry Extract",
    "Dextromethorphan / Guaifenesin", "Acetaminophen / Dextromethorphan / Doxylamine",
    "Miconazole", "Clotrimazole", "Tolnaftate", "Bacitracin",
    "Neomycin / Polymyxin B / Bacitracin", "Benzoyl Peroxide",
    "Salicylic Acid", "Minoxidil", "Nicotine", "Levocetirizine",
    "Ranitidine OTC", "Omeprazole OTC", "Lansoprazole OTC",
    "Famotidine OTC", "Meclizine", "Dimenhydrinate",
    "Pyrantel Pamoate", "Permethrin",
    "Benzocaine", "Lidocaine Topical", "Menthol", "Camphor",
    "Capsaicin", "Methyl Salicylate", "Hydrogen Peroxide",
    "Povidone Iodine", "Witch Hazel", "Calamine",
    "Dyclonine", "Phenazopyridine",
    "Sodium Bicarbonate", "Charcoal Activated",
    "Electrolyte Solution", "Pedialyte",
    "Artificial Tears", "Saline Nasal Spray",
    "Fluoride Toothpaste", "Chlorhexidine",
    # ── Top 30 Supplements ──────────────────────────────────────────
    "St. John's Wort", "Fish Oil", "Ginkgo Biloba", "Garlic Extract",
    "Ginseng", "Echinacea", "Saw Palmetto", "Valerian Root",
    "Turmeric / Curcumin", "Milk Thistle", "Ashwagandha", "Black Cohosh",
    "Evening Primrose Oil", "Green Tea Extract", "Elderberry",
    "Spirulina", "Chlorella", "Astragalus", "Rhodiola Rosea",
    "Berberine", "Cinnamon Extract", "Fenugreek", "Ginger Extract",
    "Hawthorn Berry", "Licorice Root", "Maca Root", "Resveratrol",
    "Quercetin", "Bromelain", "Devil's Claw",
]
# fmt: on

# ---------------------------------------------------------------------------
# Drug category classification
# Sources: DEA Controlled Substances Act schedules; FDA OTC monograph system;
#          NIH Office of Dietary Supplements categorization.
# Used when inserting into rxnorm_lookup.category and when migrating existing KBs.
# ---------------------------------------------------------------------------

# Schedule II–V controlled substances present in the seed list.
# Only includes those with clear DEA scheduling in the US.
CONTROLLED_GENERICS: frozenset[str] = frozenset([
    # Schedule II opioids
    "oxycodone", "morphine", "hydromorphone", "fentanyl",
    "methadone", "hydrocodone", "hydrocodone / acetaminophen",
    "codeine",  # sched II alone; III-V in combinations
    # Schedule II stimulants
    "amphetamine", "dextroamphetamine", "lisdexamfetamine",
    "methylphenidate",
    # Schedule III
    "buprenorphine", "buprenorphine / naloxone", "testosterone",
    # Schedule IV opioid
    "tramadol",
    # Schedule IV benzodiazepines / Z-drugs
    "alprazolam", "clonazepam", "zolpidem",
    "diazepam", "lorazepam", "temazepam",
    "oxazepam", "triazolam", "midazolam", "chlordiazepoxide",
    "eszopiclone", "zaleplon",
    # Schedule V
    "pregabalin",
])

# Over-the-counter drugs (FDA OTC approval; no Rx required).
OTC_GENERICS: frozenset[str] = frozenset([
    "acetaminophen", "ibuprofen", "aspirin", "naproxen",
    "diphenhydramine", "loratadine", "fexofenadine", "cetirizine",
    "levocetirizine", "loperamide", "bismuth subsalicylate",
    "calcium carbonate", "magnesium hydroxide", "simethicone",
    "pseudoephedrine", "phenylephrine", "oxymetazoline",
    "docusate", "polyethylene glycol 3350", "bisacodyl", "sennosides",
    "psyllium", "guaifenesin",
    "omeprazole", "famotidine", "ranitidine", "lansoprazole", "esomeprazole",
    "meclizine", "dimenhydrinate",
    "miconazole", "clotrimazole", "tolnaftate",
    "bacitracin", "benzoyl peroxide", "permethrin",
    "minoxidil", "nicotine", "levocetirizine",
    "pyrantel", "benzocaine", "lidocaine topical", "capsaicin",
    "methyl salicylate", "hydrogen peroxide", "povidone-iodine",
    "witch hazel", "calamine", "dyclonine", "phenazopyridine",
    "sodium bicarbonate", "activated charcoal",
    "chlorhexidine",
])

# Dietary supplements (not FDA-approved drugs; sold OTC as supplements).
SUPPLEMENT_GENERICS: frozenset[str] = frozenset([
    "melatonin", "vitamin d3", "cholecalciferol",
    "ascorbic acid",   # vitamin C
    "cyanocobalamin",  # vitamin B12
    "folic acid", "biotin", "niacin",
    "zinc", "magnesium oxide", "calcium citrate", "potassium gluconate",
    "ferrous sulfate", "iron sulfate",
    "collagen", "glucosamine", "chondroitin",
    "ubidecarenone",   # coenzyme Q10
    "thioctic acid",   # alpha-lipoic acid
    "lutein", "omega-3 fatty acids", "fish oils", "cranberry preparation",
    "bromelains", "spirulina", "quercetin", "resveratrol",
    "st. john's wort extract", "ginkgo biloba extract",
    "ginseng preparation", "echinacea preparation",
    "saw palmetto extract", "valerian root extract",
    "licorice root extract", "devil's claw preparation",
    "astragalus preparation", "sedum roseum root extract",  # rhodiola
    "milk thistle seed extract", "evening primrose oil",
    "green tea extract", "elderberry preparation",
    "garlic preparation", "ginger extract",
    "hawthorn berry", "fenugreek seed meal",
    "black cohosh extract", "berberine",
])


def _matches_any(name_lower: str, candidate_set: frozenset[str]) -> bool:
    """True if any candidate is exactly name_lower, or appears as a
    whitespace-bounded substring (e.g., 'morphine' matches 'morphine sulfate').
    """
    if name_lower in candidate_set:
        return True
    tokens = set(name_lower.split())
    return any(
        c in tokens or f" {c} " in f" {name_lower} " or name_lower.startswith(f"{c} ")
        for c in candidate_set
    )


def infer_category(generic_name: str) -> str:
    """Return 'Controlled', 'OTC', 'Supplement', or 'Rx' for a generic drug name.

    Handles suffixed formulations (e.g., "morphine hydrochloride",
    "oxycodone / acetaminophen") by matching canonical base names as
    whitespace-bounded substrings.
    """
    name_lower = generic_name.strip().lower()
    if _matches_any(name_lower, CONTROLLED_GENERICS):
        return "Controlled"
    if _matches_any(name_lower, SUPPLEMENT_GENERICS):
        return "Supplement"
    if _matches_any(name_lower, OTC_GENERICS):
        return "OTC"
    return "Rx"


def populate_categories(db_path: str) -> int:
    """Add category column to rxnorm_lookup (if missing) and populate it.

    Safe to run on an existing KB: uses ALTER TABLE IF NOT EXISTS equivalent,
    then updates all rows from the classification sets above.
    Returns the number of rows updated.
    """
    conn = sqlite3.connect(db_path)
    updated = 0
    try:
        # Add column if it doesn't exist yet
        existing_cols = {r[1] for r in conn.execute("PRAGMA table_info(rxnorm_lookup)")}
        if "category" not in existing_cols:
            conn.execute(
                "ALTER TABLE rxnorm_lookup ADD COLUMN category TEXT NOT NULL DEFAULT 'Rx'"
            )
            log.info("RxNorm: added category column to rxnorm_lookup")

        # Apply overrides for Controlled, Supplement, OTC
        for category, name_set in [
            ("Controlled", CONTROLLED_GENERICS),
            ("Supplement", SUPPLEMENT_GENERICS),
            ("OTC",        OTC_GENERICS),
        ]:
            for name in name_set:
                conn.execute(
                    "UPDATE rxnorm_lookup SET category = ? "
                    "WHERE LOWER(generic_name) = ? AND category != ?",
                    (category, name, category),
                )
                updated += conn.total_changes

        conn.commit()
        log.info("RxNorm: category column populated, %d rows updated", updated)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return updated


FALLBACK_MAPPINGS: dict[str, tuple[str, str]] = {
    # brand_name -> (rxcui, generic_name)
    "Lipitor":      ("83367",  "Atorvastatin"),
    "Crestor":      ("301542", "Rosuvastatin"),
    "Zocor":        ("36567",  "Simvastatin"),
    "Pravachol":    ("42463",  "Pravastatin"),
    "Norvasc":      ("17767",  "Amlodipine"),
    "Prinivil":     ("29046",  "Lisinopril"),
    "Zestril":      ("29046",  "Lisinopril"),
    "Glucophage":   ("6809",   "Metformin"),
    "Lopressor":    ("6918",   "Metoprolol"),
    "Toprol-XL":    ("6918",   "Metoprolol"),
    "Prilosec":     ("7646",   "Omeprazole"),
    "Nexium":       ("283742", "Esomeprazole"),
    "Prevacid":     ("17128",  "Lansoprazole"),
    "Protonix":     ("40790",  "Pantoprazole"),
    "Cozaar":       ("52175",  "Losartan"),
    "Diovan":       ("69749",  "Valsartan"),
    "Synthroid":    ("10582",  "Levothyroxine"),
    "Zoloft":       ("36437",  "Sertraline"),
    "Lexapro":      ("352741", "Escitalopram"),
    "Prozac":       ("4493",   "Fluoxetine"),
    "Celexa":       ("200371", "Citalopram"),
    "Cymbalta":     ("596926", "Duloxetine"),
    "Effexor":      ("39786",  "Venlafaxine"),
    "Wellbutrin":   ("42347",  "Bupropion"),
    "Singulair":    ("88249",  "Montelukast"),
    "Neurontin":    ("25480",  "Gabapentin"),
    "Lyrica":       ("187832", "Pregabalin"),
    "Xanax":        ("596",    "Alprazolam"),
    "Klonopin":     ("2598",   "Clonazepam"),
    "Ambien":       ("39993",  "Zolpidem"),
    "Coumadin":     ("11289",  "Warfarin"),
    "Eliquis":      ("1364430","Apixaban"),
    "Xarelto":      ("1114195","Rivaroxaban"),
    "Plavix":       ("32968",  "Clopidogrel"),
    "Lasix":        ("4603",   "Furosemide"),
    "Aldactone":    ("9997",   "Spironolactone"),
    "Flomax":       ("77492",  "Tamsulosin"),
    "Januvia":      ("593411", "Sitagliptin"),
    "Jardiance":    ("1545653","Empagliflozin"),
    "Ozempic":      ("1991302","Semaglutide"),
    "Trulicity":    ("1551291","Dulaglutide"),
    "Lantus":       ("261551", "Insulin Glargine"),
    "Humalog":      ("86009",  "Insulin Lispro"),
    "Abilify":      ("89013",  "Aripiprazole"),
    "Seroquel":     ("51272",  "Quetiapine"),
    "Zyprexa":      ("61381",  "Olanzapine"),
    "Risperdal":    ("35636",  "Risperidone"),
    "Adderall":     ("725",    "Amphetamine"),
    "Vyvanse":      ("854838", "Lisdexamfetamine"),
    "Ritalin":      ("6901",   "Methylphenidate"),
    # ── Common OTC brand names (user queries often use these) ─────────
    "Sudafed":      ("8896",   "Pseudoephedrine"),
    "Sudafed PE":   ("8163",   "Phenylephrine"),
    "Tylenol":      ("161",    "Acetaminophen"),
    "Advil":        ("5640",   "Ibuprofen"),
    "Motrin":       ("5640",   "Ibuprofen"),
    "Aleve":        ("7258",   "Naproxen"),
    "Bayer":        ("1191",   "Aspirin"),
    "Benadryl":     ("3498",   "Diphenhydramine"),
    "Claritin":     ("28889",  "Loratadine"),
    "Zyrtec":       ("20610",  "Cetirizine"),
    "Allegra":      ("82675",  "Fexofenadine"),
    "Xyzal":        ("1011724","Levocetirizine"),
    "Afrin":        ("8163",   "Oxymetazoline"),
    "Mucinex":      ("5032",   "Guaifenesin"),
    "Robitussin DM":("214488", "Dextromethorphan / Guaifenesin"),
    "NyQuil":       ("705258", "Acetaminophen / Dextromethorphan / Doxylamine"),
    "DayQuil":      ("214488", "Dextromethorphan / Guaifenesin"),
    "Imodium":      ("17091",  "Loperamide"),
    "Pepto-Bismol": ("1326",   "Bismuth Subsalicylate"),
    "Tums":         ("1898",   "Calcium Carbonate"),
    "Rolaids":      ("1898",   "Calcium Carbonate"),
    "Pepcid":       ("4278",   "Famotidine"),
    "Zantac":       ("9143",   "Ranitidine"),
    "Prilosec OTC": ("7646",   "Omeprazole"),
    "Dramamine":    ("3423",   "Dimenhydrinate"),
    "Bonine":       ("6660",   "Meclizine"),
    "MiraLAX":      ("27084",  "Polyethylene Glycol 3350"),
    "Dulcolax":     ("1549",   "Bisacodyl"),
    "Senokot":      ("9718",   "Sennosides"),
    "Metamucil":    ("8777",   "Psyllium"),
    "Colace":       ("3407",   "Docusate"),
    "Flonase":      ("41126",  "Fluticasone"),
    "Nasonex":      ("6960",   "Mometasone"),
    "Visine":       ("3638",   "Tetrahydrozoline"),
    "Cortizone":    ("5492",   "Hydrocortisone"),
    "Neosporin":    ("1191088","Bacitracin / Neomycin / Polymyxin B"),
    "Bactine":      ("6387",   "Lidocaine Topical"),
    "Orajel":       ("1292",   "Benzocaine"),
    "Preparation H":("21246",  "Witch Hazel"),
    "Desitin":      ("38605",  "Zinc"),
    "Gas-X":        ("9456",   "Simethicone"),
    "Beano":        ("80583",  "Alpha Galactosidase"),
}


def _get_json(url: str, params: dict[str, Any] | None = None) -> dict | None:
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as exc:
            log.warning("RxNorm request failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _lookup_drug(name: str) -> tuple[str, str, str, str] | None:
    """Return (rxcui, brand_name, generic_name, tty) or None."""
    data = _get_json(f"{RXNORM_BASE}/rxcui.json", {"name": name, "search": 1})
    if not data:
        return None

    id_group = data.get("idGroup", {})
    rxcui_list = id_group.get("rxnormId")
    if not rxcui_list:
        return None
    rxcui = rxcui_list[0]

    props_data = _get_json(f"{RXNORM_BASE}/rxcui/{rxcui}/properties.json")
    if not props_data:
        return rxcui, name, name, "UNKNOWN"

    props = props_data.get("properties", {})
    generic = props.get("name", name)
    tty = props.get("tty", "UNKNOWN")
    return rxcui, name, generic, tty


def build(db_path: str) -> int:
    """Populate the rxnorm_lookup table. Returns number of rows inserted."""
    log.info("RxNorm: starting build for %d seed drugs", len(SEED_DRUGS))
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        # API-based lookups
        for i, drug in enumerate(SEED_DRUGS):
            result = _lookup_drug(drug)
            if result:
                rxcui, brand, generic, tty = result
                category = infer_category(generic)
                try:
                    conn.execute(
                        "INSERT OR IGNORE INTO rxnorm_lookup "
                        "(rxcui, brand_name, generic_name, tty, category, source) "
                        "VALUES (?, ?, ?, ?, ?, 'rxnorm_api')",
                        (rxcui, brand, generic, tty, category),
                    )
                    inserted += conn.total_changes - inserted
                except sqlite3.IntegrityError:
                    pass
            else:
                log.debug("RxNorm: no API result for %s, will try fallback", drug)

            time.sleep(RATE_LIMIT_SLEEP)
            if (i + 1) % 25 == 0:
                log.info("RxNorm: processed %d / %d drugs", i + 1, len(SEED_DRUGS))

        # Fallback hardcoded mappings
        for brand, (rxcui, generic) in FALLBACK_MAPPINGS.items():
            category = infer_category(generic)
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO rxnorm_lookup "
                    "(rxcui, brand_name, generic_name, tty, category, source) "
                    "VALUES (?, ?, ?, 'SBD', ?, 'rxnorm_fallback')",
                    (rxcui, brand, generic, category),
                )
                inserted += conn.total_changes - inserted
            except sqlite3.IntegrityError:
                pass

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("RxNorm: inserted %d rows into rxnorm_lookup", inserted)
    return inserted
