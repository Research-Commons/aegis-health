"""openFDA source – fetches drug labels for drugs, interactions, and warnings.

Uses the openFDA drug label API:
  https://api.fda.gov/drug/label.json

Set OPENFDA_API_KEY in the environment to raise the rate limit from
1,000/day to 120,000/day. Free key at https://open.fda.gov/apis/authentication/.
"""
from __future__ import annotations

import logging
import os
import re
import sqlite3
import time

import requests

log = logging.getLogger(__name__)

FDA_LABEL_URL = "https://api.fda.gov/drug/label.json"
REQUEST_TIMEOUT = 20
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0
PAGE_SIZE = 100
# Without API key: 240 req/min. With key: 240 req/min per IP but 120k/day.
RATE_LIMIT_SLEEP_NOKEY = 0.6
RATE_LIMIT_SLEEP_KEY = 0.25


def _rate_limit_sleep() -> float:
    return RATE_LIMIT_SLEEP_KEY if os.environ.get("OPENFDA_API_KEY") else RATE_LIMIT_SLEEP_NOKEY


def _get_json(url: str, params: dict | None = None) -> dict | None:
    api_key = os.environ.get("OPENFDA_API_KEY")
    if api_key:
        params = dict(params or {})
        params["api_key"] = api_key
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
            if resp.status_code == 429:
                wait = RETRY_BACKOFF * attempt * 2
                log.warning("openFDA rate limited, sleeping %.1fs", wait)
                time.sleep(wait)
                continue
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as exc:
            log.warning("openFDA request failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _migrate_drugs_schema(conn: sqlite3.Connection) -> None:
    """Ensure the drugs table has the expanded columns and a uniqueness index.

    Idempotent:
      * ADD COLUMN is only issued if the column is missing.
      * De-duplicates rows before creating the unique index so existing DBs
        built without the index migrate cleanly.
    """
    existing = {r[1] for r in conn.execute("PRAGMA table_info(drugs)")}
    if "pharm_class" not in existing:
        conn.execute("ALTER TABLE drugs ADD COLUMN pharm_class TEXT")
        log.info("openFDA: added pharm_class column to drugs")
    if "indication_summary" not in existing:
        conn.execute("ALTER TABLE drugs ADD COLUMN indication_summary TEXT")
        log.info("openFDA: added indication_summary column to drugs")

    # De-dup (rxcui, drug_name) pairs before creating the unique index.
    dupes = conn.execute(
        "SELECT COUNT(*) FROM drugs d1 WHERE d1.id > ("
        "  SELECT MIN(d2.id) FROM drugs d2 "
        "  WHERE d2.rxcui = d1.rxcui AND d2.drug_name = d1.drug_name"
        ")"
    ).fetchone()[0]
    if dupes:
        conn.execute(
            "DELETE FROM drugs WHERE id IN ("
            "  SELECT d1.id FROM drugs d1 WHERE d1.id > ("
            "    SELECT MIN(d2.id) FROM drugs d2 "
            "    WHERE d2.rxcui = d1.rxcui AND d2.drug_name = d1.drug_name"
            "  )"
            ")"
        )
        log.info("openFDA: de-duplicated %d existing rows in drugs", dupes)

    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_drugs_unique "
        "ON drugs(rxcui, drug_name)"
    )
    conn.execute(
        "CREATE INDEX IF NOT EXISTS idx_drugs_pharmclass "
        "ON drugs(pharm_class COLLATE NOCASE)"
    )


def _rxcui_list(conn: sqlite3.Connection) -> list[tuple[str, str]]:
    """Return [(rxcui, generic_name), …] from the rxnorm_lookup table."""
    cur = conn.execute("SELECT rxcui, generic_name FROM rxnorm_lookup")
    return cur.fetchall()


def _join_field(result: dict, field: str) -> str | None:
    val = result.get(field)
    if isinstance(val, list):
        return "\n".join(val).strip() or None
    return val


def _extract_severity(text: str) -> int:
    """Heuristic severity from text: 1 (info) … 5 (life-threatening)."""
    lower = text.lower()
    if any(w in lower for w in ("fatal", "death", "life-threatening", "contraindicated")):
        return 5
    if any(w in lower for w in ("serious", "severe", "black box", "boxed warning")):
        return 4
    if any(w in lower for w in ("significant", "major", "avoid")):
        return 3
    if any(w in lower for w in ("moderate", "caution", "monitor")):
        return 2
    return 1


# Module-level cache: each drug's label JSON is fetched once per build.
# build_drugs, build_interactions, and build_warnings all hit the same
# drug list; caching keeps the full rebuild under the openFDA 1,000/day
# free-tier cap (previously 3 fetches per drug -> now 1).
_LABEL_CACHE: dict[tuple[str, int], dict | None] = {}


def _fetch_label(drug_name: str, skip: int = 0) -> dict | None:
    """Fetch an openFDA label (cached). Sleeps for rate limiting only on miss."""
    key = (drug_name.strip().lower(), skip)
    if key in _LABEL_CACHE:
        return _LABEL_CACHE[key]
    params = {
        "search": f'openfda.generic_name:"{drug_name}"',
        "limit": PAGE_SIZE,
        "skip": skip,
    }
    data = _get_json(FDA_LABEL_URL, params)
    _LABEL_CACHE[key] = data
    time.sleep(_rate_limit_sleep())
    return data


def _clear_label_cache() -> None:
    """Reset the label cache between builds (mainly for tests)."""
    _LABEL_CACHE.clear()


def _parse_interacting_drugs(text: str, known_names: set[str]) -> list[str]:
    """Find known drug names mentioned anywhere in an interaction text.

    Uses word-boundary matching against the full rxnorm_lookup dictionary
    rather than narrow grammatical patterns, which missed most real-world
    mentions (e.g. "ibuprofen" in a list of NSAIDs after a colon).

    Filters out names shorter than 4 characters to avoid spurious matches on
    common abbreviations that coincidentally appear in drug names.
    """
    text_lower = text.lower()
    found: list[str] = []
    for name in known_names:
        if len(name) < 4:
            continue
        if re.search(r"\b" + re.escape(name) + r"\b", text_lower):
            found.append(name)
    return found


# ── Public build functions ────────────────────────────────────────


# Fallback mapping for drugs whose openFDA labels don't expose pharm_class_*
# fields (mainly older generics). Values use FDA Established Pharmacologic
# Class (EPC) terminology where available; otherwise the widely-accepted
# clinical class. Source: FDA drug label archives, NLM RxClass EPC, and
# standard clinical pharmacology references.
CURATED_PHARM_CLASS: dict[str, str] = {
    # Statins
    "simvastatin": "HMG-CoA Reductase Inhibitor [EPC]",
    "pravastatin": "HMG-CoA Reductase Inhibitor [EPC]",
    "rosuvastatin": "HMG-CoA Reductase Inhibitor [EPC]",
    "lovastatin": "HMG-CoA Reductase Inhibitor [EPC]",
    "pitavastatin": "HMG-CoA Reductase Inhibitor [EPC]",
    # ACE inhibitors
    "lisinopril": "Angiotensin-Converting Enzyme Inhibitor [EPC]",
    "enalapril": "Angiotensin-Converting Enzyme Inhibitor [EPC]",
    "ramipril": "Angiotensin-Converting Enzyme Inhibitor [EPC]",
    "captopril": "Angiotensin-Converting Enzyme Inhibitor [EPC]",
    "benazepril": "Angiotensin-Converting Enzyme Inhibitor [EPC]",
    "quinapril": "Angiotensin-Converting Enzyme Inhibitor [EPC]",
    # ARBs
    "losartan": "Angiotensin 2 Receptor Blocker [EPC]",
    "valsartan": "Angiotensin 2 Receptor Blocker [EPC]",
    "irbesartan": "Angiotensin 2 Receptor Blocker [EPC]",
    "olmesartan": "Angiotensin 2 Receptor Blocker [EPC]",
    "telmisartan": "Angiotensin 2 Receptor Blocker [EPC]",
    "candesartan": "Angiotensin 2 Receptor Blocker [EPC]",
    # Beta blockers
    "metoprolol": "Beta-Adrenergic Blocker [EPC]",
    "atenolol": "Beta-Adrenergic Blocker [EPC]",
    "propranolol": "Beta-Adrenergic Blocker [EPC]",
    "carvedilol": "Alpha- and Beta-Adrenergic Blocker [EPC]",
    "bisoprolol": "Beta-Adrenergic Blocker [EPC]",
    "nebivolol": "Beta-Adrenergic Blocker [EPC]",
    "labetalol": "Alpha- and Beta-Adrenergic Blocker [EPC]",
    "sotalol": "Beta-Adrenergic Blocker [EPC] | Class III Antiarrhythmic",
    # Calcium channel blockers
    "amlodipine": "Dihydropyridine Calcium Channel Blocker [EPC]",
    "nifedipine": "Dihydropyridine Calcium Channel Blocker [EPC]",
    "felodipine": "Dihydropyridine Calcium Channel Blocker [EPC]",
    "diltiazem": "Calcium Channel Blocker [EPC]",
    "verapamil": "Calcium Channel Blocker [EPC]",
    # Diuretics
    "hydrochlorothiazide": "Thiazide Diuretic [EPC]",
    "chlorthalidone": "Thiazide-like Diuretic [EPC]",
    "furosemide": "Loop Diuretic [EPC]",
    "bumetanide": "Loop Diuretic [EPC]",
    "torsemide": "Loop Diuretic [EPC]",
    "spironolactone": "Aldosterone Antagonist [EPC]",
    "eplerenone": "Aldosterone Antagonist [EPC]",
    # Anticoagulants
    "warfarin": "Vitamin K Antagonist [EPC]",
    "apixaban": "Factor Xa Inhibitor [EPC]",
    "rivaroxaban": "Factor Xa Inhibitor [EPC]",
    "edoxaban": "Factor Xa Inhibitor [EPC]",
    "dabigatran": "Direct Thrombin Inhibitor [EPC]",
    "heparin": "Heparin [EPC]",
    "enoxaparin": "Low Molecular Weight Heparin [EPC]",
    # Antiplatelets
    "clopidogrel": "P2Y12 Platelet Inhibitor [EPC]",
    "prasugrel": "P2Y12 Platelet Inhibitor [EPC]",
    "ticagrelor": "P2Y12 Platelet Inhibitor [EPC]",
    # SSRIs / SNRIs / antidepressants
    "sertraline": "Selective Serotonin Reuptake Inhibitor [EPC]",
    "fluoxetine": "Selective Serotonin Reuptake Inhibitor [EPC]",
    "citalopram": "Selective Serotonin Reuptake Inhibitor [EPC]",
    "escitalopram": "Selective Serotonin Reuptake Inhibitor [EPC]",
    "paroxetine": "Selective Serotonin Reuptake Inhibitor [EPC]",
    "fluvoxamine": "Selective Serotonin Reuptake Inhibitor [EPC]",
    "venlafaxine": "Serotonin and Norepinephrine Reuptake Inhibitor [EPC]",
    "duloxetine": "Serotonin and Norepinephrine Reuptake Inhibitor [EPC]",
    "desvenlafaxine": "Serotonin and Norepinephrine Reuptake Inhibitor [EPC]",
    "bupropion": "Aminoketone Antidepressant [EPC]",
    "mirtazapine": "Tetracyclic Antidepressant [EPC]",
    "trazodone": "Serotonin Modulator [EPC]",
    "phenelzine": "Monoamine Oxidase Inhibitor [EPC]",
    "tranylcypromine": "Monoamine Oxidase Inhibitor [EPC]",
    "amitriptyline": "Tricyclic Antidepressant [EPC]",
    "nortriptyline": "Tricyclic Antidepressant [EPC]",
    # Benzodiazepines / Z-drugs
    "alprazolam": "Benzodiazepine [EPC]",
    "clonazepam": "Benzodiazepine [EPC]",
    "diazepam": "Benzodiazepine [EPC]",
    "lorazepam": "Benzodiazepine [EPC]",
    "temazepam": "Benzodiazepine [EPC]",
    "zolpidem": "GABA-A Receptor Agonist [EPC]",
    # Opioids
    "oxycodone": "Opioid Agonist [EPC]",
    "hydrocodone": "Opioid Agonist [EPC]",
    "morphine": "Opioid Agonist [EPC]",
    "hydromorphone": "Opioid Agonist [EPC]",
    "fentanyl": "Opioid Agonist [EPC]",
    "methadone": "Opioid Agonist [EPC]",
    "codeine": "Opioid Agonist [EPC]",
    "tramadol": "Opioid Agonist [EPC]",
    "buprenorphine": "Partial Opioid Agonist [EPC]",
    "naloxone": "Opioid Antagonist [EPC]",
    "naltrexone": "Opioid Antagonist [EPC]",
    # Antipsychotics
    "aripiprazole": "Atypical Antipsychotic [EPC]",
    "quetiapine": "Atypical Antipsychotic [EPC]",
    "olanzapine": "Atypical Antipsychotic [EPC]",
    "risperidone": "Atypical Antipsychotic [EPC]",
    "ziprasidone": "Atypical Antipsychotic [EPC]",
    "lurasidone": "Atypical Antipsychotic [EPC]",
    "clozapine": "Atypical Antipsychotic [EPC]",
    "haloperidol": "Typical Antipsychotic [EPC]",
    # Diabetes
    "metformin": "Biguanide [EPC]",
    "glipizide": "Sulfonylurea [EPC]",
    "glyburide": "Sulfonylurea [EPC]",
    "glimepiride": "Sulfonylurea [EPC]",
    "pioglitazone": "Thiazolidinedione [EPC]",
    "sitagliptin": "Dipeptidyl Peptidase 4 Inhibitor [EPC]",
    "linagliptin": "Dipeptidyl Peptidase 4 Inhibitor [EPC]",
    "saxagliptin": "Dipeptidyl Peptidase 4 Inhibitor [EPC]",
    "empagliflozin": "Sodium-Glucose Cotransporter 2 Inhibitor [EPC]",
    "canagliflozin": "Sodium-Glucose Cotransporter 2 Inhibitor [EPC]",
    "dapagliflozin": "Sodium-Glucose Cotransporter 2 Inhibitor [EPC]",
    "liraglutide": "Glucagon-like Peptide-1 Receptor Agonist [EPC]",
    "semaglutide": "Glucagon-like Peptide-1 Receptor Agonist [EPC]",
    "dulaglutide": "Glucagon-like Peptide-1 Receptor Agonist [EPC]",
    "tirzepatide": "Glucose-Dependent Insulinotropic Polypeptide and Glucagon-like Peptide-1 Receptor Agonist [EPC]",
    # Antibiotics
    "amoxicillin": "Penicillin-class Antibacterial [EPC]",
    "ampicillin": "Penicillin-class Antibacterial [EPC]",
    "cephalexin": "Cephalosporin Antibacterial [EPC]",
    "ceftriaxone": "Cephalosporin Antibacterial [EPC]",
    "ciprofloxacin": "Fluoroquinolone Antibacterial [EPC]",
    "levofloxacin": "Fluoroquinolone Antibacterial [EPC]",
    "azithromycin": "Macrolide Antibacterial [EPC]",
    "erythromycin": "Macrolide Antibacterial [EPC]",
    "doxycycline": "Tetracycline-class Antibacterial [EPC]",
    "clindamycin": "Lincosamide Antibacterial [EPC]",
    "metronidazole": "Nitroimidazole Antimicrobial [EPC]",
    "nitrofurantoin": "Nitrofuran Antibacterial [EPC]",
    # PPIs / H2 blockers
    "omeprazole": "Proton Pump Inhibitor [EPC]",
    "esomeprazole": "Proton Pump Inhibitor [EPC]",
    "pantoprazole": "Proton Pump Inhibitor [EPC]",
    "lansoprazole": "Proton Pump Inhibitor [EPC]",
    "rabeprazole": "Proton Pump Inhibitor [EPC]",
    "dexlansoprazole": "Proton Pump Inhibitor [EPC]",
    "famotidine": "Histamine-2 Receptor Antagonist [EPC]",
    "ranitidine": "Histamine-2 Receptor Antagonist [EPC]",
    # Thyroid
    "levothyroxine": "l-Thyroxine [EPC]",
    "liothyronine": "Triiodothyronine [EPC]",
    # NSAIDs
    "ibuprofen": "Nonsteroidal Anti-inflammatory Drug [EPC]",
    "naproxen": "Nonsteroidal Anti-inflammatory Drug [EPC]",
    "meloxicam": "Nonsteroidal Anti-inflammatory Drug [EPC]",
    "celecoxib": "COX-2 Selective Nonsteroidal Anti-inflammatory Drug [EPC]",
    "diclofenac": "Nonsteroidal Anti-inflammatory Drug [EPC]",
    "indomethacin": "Nonsteroidal Anti-inflammatory Drug [EPC]",
    "ketorolac": "Nonsteroidal Anti-inflammatory Drug [EPC]",
    "aspirin": "Nonsteroidal Anti-inflammatory Drug [EPC] | Platelet Aggregation Inhibitor",
    # Anticonvulsants / mood stabilizers
    "gabapentin": "Gamma-Aminobutyric Acid Analog [EPC]",
    "pregabalin": "Gamma-Aminobutyric Acid Analog [EPC]",
    "lamotrigine": "Antiepileptic [EPC]",
    "levetiracetam": "Antiepileptic [EPC]",
    "topiramate": "Antiepileptic [EPC]",
    "carbamazepine": "Antiepileptic [EPC]",
    "valproic acid": "Antiepileptic [EPC]",
    "phenytoin": "Antiepileptic [EPC]",
    "lithium": "Mood Stabilizer",
    # Antihistamines
    "diphenhydramine": "Histamine-1 Receptor Antagonist [EPC]",
    "loratadine": "Histamine-1 Receptor Antagonist [EPC]",
    "cetirizine": "Histamine-1 Receptor Antagonist [EPC]",
    "fexofenadine": "Histamine-1 Receptor Antagonist [EPC]",
    "levocetirizine": "Histamine-1 Receptor Antagonist [EPC]",
    "hydroxyzine": "Histamine-1 Receptor Antagonist [EPC]",
    # Asthma / COPD
    "albuterol": "beta2-Adrenergic Agonist [EPC]",
    "levalbuterol": "beta2-Adrenergic Agonist [EPC]",
    "salmeterol": "Long Acting beta2-Adrenergic Agonist [EPC]",
    "formoterol": "Long Acting beta2-Adrenergic Agonist [EPC]",
    "tiotropium": "Anticholinergic [EPC]",
    "ipratropium": "Anticholinergic [EPC]",
    "fluticasone": "Corticosteroid [EPC]",
    "budesonide": "Corticosteroid [EPC]",
    "mometasone": "Corticosteroid [EPC]",
    "montelukast": "Leukotriene Receptor Antagonist [EPC]",
    # Corticosteroids
    "prednisone": "Corticosteroid [EPC]",
    "prednisolone": "Corticosteroid [EPC]",
    "methylprednisolone": "Corticosteroid [EPC]",
    "hydrocortisone": "Corticosteroid [EPC]",
    "dexamethasone": "Corticosteroid [EPC]",
    "triamcinolone": "Corticosteroid [EPC]",
    "betamethasone": "Corticosteroid [EPC]",
    # Bisphosphonates
    "alendronate": "Bisphosphonate [EPC]",
    "risedronate": "Bisphosphonate [EPC]",
    "ibandronate": "Bisphosphonate [EPC]",
    "zoledronic acid": "Bisphosphonate [EPC]",
    # Immunosuppressants
    "tacrolimus": "Calcineurin Inhibitor [EPC]",
    "cyclosporine": "Calcineurin Inhibitor [EPC]",
    "methotrexate": "Folate Analog Metabolic Inhibitor [EPC]",
    "mycophenolate mofetil": "Inosine Monophosphate Dehydrogenase Inhibitor [EPC]",
    "azathioprine": "Purine Analog [EPC]",
    # Misc major classes
    "digoxin": "Cardiac Glycoside [EPC]",
    "amiodarone": "Class III Antiarrhythmic",
    "hydroxychloroquine": "Antimalarial [EPC]",
    "colchicine": "Anti-Gout [EPC]",
    "allopurinol": "Xanthine Oxidase Inhibitor [EPC]",
    "febuxostat": "Xanthine Oxidase Inhibitor [EPC]",
    "finasteride": "5-alpha Reductase Inhibitor [EPC]",
    "dutasteride": "5-alpha Reductase Inhibitor [EPC]",
    "tamsulosin": "Alpha-1 Adrenergic Antagonist [EPC]",
    "doxazosin": "Alpha-1 Adrenergic Antagonist [EPC]",
    "sildenafil": "Phosphodiesterase 5 Inhibitor [EPC]",
    "tadalafil": "Phosphodiesterase 5 Inhibitor [EPC]",
    "ondansetron": "Serotonin-3 Receptor Antagonist [EPC]",
    "sumatriptan": "Serotonin-1B/1D Receptor Agonist [EPC]",
}


def _extract_pharm_class(openfda: dict) -> str | None:
    """Combine FDA-established pharm class fields into a readable string.

    Draws from pharm_class_epc (Established Pharmacologic Class),
    pharm_class_moa (Mechanism of Action), pharm_class_cs (Chemical Structure),
    and pharm_class_pe (Physiologic Effect).
    """
    parts: list[str] = []
    for field in ("pharm_class_epc", "pharm_class_moa", "pharm_class_cs", "pharm_class_pe"):
        values = openfda.get(field) or []
        for v in values:
            if v and v not in parts:
                parts.append(v)
    return " | ".join(parts) if parts else None


def _find_pharm_class_in_results(results: list[dict], target_generic: str) -> str | None:
    """Scan results for the first one that has FDA-established pharm class data.

    Filters to single-ingredient products matching ``target_generic``; combo
    products (e.g., metformin+sitagliptin) carry the *other* drug's class
    and would otherwise pollute the result. Also requires substance_name
    to match so combos with the same generic label but extra ingredients
    are excluded.
    """
    target_lower = target_generic.strip().lower()
    for r in results[:50]:
        openfda = r.get("openfda", {})
        generic_list = [g.lower() for g in (openfda.get("generic_name") or [])]
        substance_list = [s.lower() for s in (openfda.get("substance_name") or [])]
        if target_lower not in generic_list and target_lower not in substance_list:
            continue
        # Reject combo products: more than one distinct substance
        if len(substance_list) > 1:
            continue
        pc = _extract_pharm_class(openfda)
        if pc:
            return pc
    return None


def _extract_indication(result: dict) -> str | None:
    """First 500 chars of the indications_and_usage field, single-lined."""
    raw = _join_field(result, "indications_and_usage")
    if not raw:
        return None
    cleaned = re.sub(r"\s+", " ", raw).strip()
    return cleaned[:500] if cleaned else None


def build_drugs(db_path: str) -> int:
    """Populate the drugs table from openFDA labels."""
    log.info("openFDA: building drugs table")
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        _migrate_drugs_schema(conn)
        drugs = _rxcui_list(conn)
        for idx, (rxcui, generic) in enumerate(drugs):
            data = _fetch_label(generic)
            if not data or "results" not in data:
                continue

            # Precedence: curated map wins for drugs we've hand-verified,
            # since live openFDA labels can leak combo-product pharm_class
            # through filters (e.g., lisinopril/HCTZ tagging lisinopril as
            # a thiazide). Fall through to live scan for drugs we haven't
            # curated.
            curated = CURATED_PHARM_CLASS.get(generic.strip().lower())
            pharm_class_common = curated or _find_pharm_class_in_results(
                data["results"], generic
            )

            for result in data["results"][:3]:
                openfda = result.get("openfda", {})
                brand_names = openfda.get("brand_name", [generic])
                dosage = _join_field(result, "dosage_forms_and_strengths")
                route_list = openfda.get("route", [])
                route = route_list[0] if route_list else None
                labeler = (openfda.get("manufacturer_name") or [None])[0]
                desc = _join_field(result, "description")
                pharm_class = _extract_pharm_class(openfda) or pharm_class_common
                indication = _extract_indication(result)

                for bname in brand_names[:2]:
                    try:
                        conn.execute(
                            "INSERT OR IGNORE INTO drugs "
                            "(rxcui, drug_name, generic_name, dosage_form, route, labeler, "
                            " description, pharm_class, indication_summary, source) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'openfda')",
                            (rxcui, bname, generic, dosage, route, labeler,
                             (desc or "")[:2000], pharm_class, indication),
                        )
                        inserted += 1
                    except sqlite3.IntegrityError:
                        pass

            if (idx + 1) % 20 == 0:
                log.info("openFDA drugs: %d / %d processed", idx + 1, len(drugs))

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("openFDA: inserted %d rows into drugs", inserted)
    return inserted


def build_interactions(db_path: str) -> int:
    """Populate the interactions table from openFDA drug_interactions field."""
    log.info("openFDA: building interactions table")
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        drugs = _rxcui_list(conn)
        rxcui_by_name: dict[str, str] = {name.lower(): rxcui for rxcui, name in drugs}
        known_names: set[str] = set(rxcui_by_name.keys())

        for idx, (rxcui, generic) in enumerate(drugs):
            data = _fetch_label(generic)
            if not data or "results" not in data:
                continue

            for result in data["results"][:2]:
                interaction_text = _join_field(result, "drug_interactions")
                if not interaction_text:
                    continue

                # _parse_interacting_drugs returns lowercase names already
                mentioned = _parse_interacting_drugs(interaction_text, known_names)
                for other_name in mentioned:
                    other_rxcui = rxcui_by_name.get(other_name)
                    if not other_rxcui or other_rxcui == rxcui:
                        continue

                    severity = _extract_severity(interaction_text)
                    try:
                        conn.execute(
                            "INSERT OR IGNORE INTO interactions "
                            "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, "
                            " severity, description, source) "
                            "VALUES (?, ?, ?, ?, ?, ?, 'openfda')",
                            (rxcui, generic, other_rxcui, other_name,
                             severity, interaction_text[:2000]),
                        )
                        inserted += 1
                    except sqlite3.Error as exc:
                        log.warning("openFDA interactions: DB error for %s ↔ %s: %s",
                                    generic, other_name, exc)

            if (idx + 1) % 20 == 0:
                log.info("openFDA interactions: %d / %d processed", idx + 1, len(drugs))

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("openFDA: inserted %d rows into interactions", inserted)
    return inserted


def build_warnings(db_path: str) -> int:
    """Populate the warnings table from openFDA contraindication / warning fields."""
    log.info("openFDA: building warnings table")
    conn = sqlite3.connect(db_path)
    inserted = 0

    WARNING_FIELDS = {
        "boxed_warning":                  "boxed",
        "warnings_and_cautions":          "contraindication",
        "warnings":                       "contraindication",
        "contraindications":              "contraindication",
        "pregnancy":                      "pregnancy",
        "nursing_mothers":                "pregnancy",
        "pediatric_use":                  "pediatric",
        "geriatric_use":                  "geriatric",
    }

    try:
        drugs = _rxcui_list(conn)
        for idx, (rxcui, generic) in enumerate(drugs):
            data = _fetch_label(generic)
            if not data or "results" not in data:
                continue

            for result in data["results"][:2]:
                for field, wtype in WARNING_FIELDS.items():
                    text = _join_field(result, field)
                    if not text or len(text) < 20:
                        continue

                    population = None
                    if wtype == "pediatric":
                        population = "pediatric"
                    elif wtype == "geriatric":
                        population = "geriatric (≥65)"
                    elif wtype == "pregnancy":
                        population = "pregnant or nursing"

                    severity = _extract_severity(text)
                    try:
                        conn.execute(
                            "INSERT INTO warnings "
                            "(drug_rxcui, drug_name, warning_type, population, "
                            " description, severity, source) "
                            "VALUES (?, ?, ?, ?, ?, ?, 'openfda')",
                            (rxcui, generic, wtype, population,
                             text[:3000], severity),
                        )
                        inserted += 1
                    except sqlite3.IntegrityError:
                        pass

            if (idx + 1) % 20 == 0:
                log.info("openFDA warnings: %d / %d processed", idx + 1, len(drugs))

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("openFDA: inserted %d rows into warnings", inserted)
    return inserted


def build(db_path: str) -> int:
    """Run all three sub-builds. Returns total rows inserted."""
    total = 0
    total += build_drugs(db_path)
    total += build_interactions(db_path)
    total += build_warnings(db_path)
    return total
