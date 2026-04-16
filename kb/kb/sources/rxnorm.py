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
                try:
                    conn.execute(
                        "INSERT OR IGNORE INTO rxnorm_lookup "
                        "(rxcui, brand_name, generic_name, tty, source) "
                        "VALUES (?, ?, ?, ?, 'rxnorm_api')",
                        (rxcui, brand, generic, tty),
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
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO rxnorm_lookup "
                    "(rxcui, brand_name, generic_name, tty, source) "
                    "VALUES (?, ?, ?, 'SBD', 'rxnorm_fallback')",
                    (rxcui, brand, generic),
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
