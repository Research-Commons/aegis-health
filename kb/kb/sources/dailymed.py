"""DailyMed SPL source – parses active ingredient decomposition for
combination products (e.g. NyQuil → acetaminophen + dextromethorphan + doxylamine).

Uses the DailyMed SPL API and lxml for XML parsing.
"""
from __future__ import annotations

import logging
import sqlite3
import time

import requests
from lxml import etree

log = logging.getLogger(__name__)

DAILYMED_SEARCH = "https://dailymed.nlm.nih.gov/dailymed/services/v2/spls.json"
DAILYMED_SPL = "https://dailymed.nlm.nih.gov/dailymed/services/v2/spls/{setid}.xml"
REQUEST_TIMEOUT = 20
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0
RATE_LIMIT_SLEEP = 0.5

SPL_NS = {"spl": "urn:hl7-org:v3"}

COMBO_PRODUCTS: list[str] = [
    "NyQuil", "DayQuil", "Excedrin", "Mucinex DM", "Advil Cold and Sinus",
    "Tylenol Cold", "Alka-Seltzer Plus", "Sudafed PE", "Robitussin DM",
    "Theraflu", "Dimetapp", "Coricidin HBP", "Zyrtec-D", "Claritin-D",
    "Allegra-D", "Percocet", "Vicodin", "Norco", "Lortab",
    "Fioricet", "Fiorinal", "Suboxone", "Bactrim", "Augmentin",
    "Stalevo", "Sinemet", "Entresto", "Twinrix", "Combivir",
    "Triumeq", "Symbicort", "Advair", "Breo Ellipta", "Combivent",
    "Hyzaar", "Lotrel", "Exforge", "Twynsta", "Tribenzor",
    "Byetta", "Janumet", "Glyxambi", "Invokamet", "Xigduo",
]


def _get_json(url: str, params: dict | None = None) -> dict | None:
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, params=params, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as exc:
            log.warning("DailyMed request failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _get_xml(url: str) -> etree._Element | None:
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            return etree.fromstring(resp.content)
        except (requests.RequestException, etree.XMLSyntaxError) as exc:
            log.warning("DailyMed XML failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _search_spl(drug_name: str) -> str | None:
    """Return the first SPL setid for a given drug name."""
    data = _get_json(DAILYMED_SEARCH, {"drug_name": drug_name, "pagesize": 1})
    if not data:
        return None
    results = data.get("data", [])
    if not results:
        return None
    return results[0].get("setid")


def _parse_ingredients(root: etree._Element) -> list[dict]:
    """Extract active ingredients from SPL XML."""
    ingredients: list[dict] = []

    for ingredient_el in root.iter("{urn:hl7-org:v3}ingredient"):
        class_code = ingredient_el.get("classCode", "")
        if class_code not in ("ACTIB", "ACTIM", "ACTIR", "ACTI"):
            continue

        name_el = ingredient_el.find(
            ".//spl:ingredientSubstance/spl:name", SPL_NS
        )
        if name_el is None:
            name_el = ingredient_el.find(
                ".//spl:activeIngredientSubstance/spl:name", SPL_NS
            )
        name = name_el.text.strip() if name_el is not None and name_el.text else None
        if not name:
            continue

        code_el = ingredient_el.find(
            ".//spl:ingredientSubstance/spl:code", SPL_NS
        )
        if code_el is None:
            code_el = ingredient_el.find(
                ".//spl:activeIngredientSubstance/spl:code", SPL_NS
            )
        rxcui = code_el.get("code") if code_el is not None else None

        strength = None
        qty_el = ingredient_el.find(".//spl:quantity/spl:numerator", SPL_NS)
        if qty_el is not None:
            val = qty_el.get("value", "")
            unit = qty_el.get("unit", "")
            if val:
                strength = f"{val} {unit}".strip()

        ingredients.append({
            "name": name,
            "rxcui": rxcui,
            "strength": strength,
        })

    return ingredients


def build(db_path: str) -> int:
    """Populate the drug_ingredients table. Returns number of rows inserted."""
    log.info("DailyMed: starting build for %d combo products", len(COMBO_PRODUCTS))
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        # Build a lookup for parent rxcui from rxnorm_lookup
        rxcui_map: dict[str, str] = {}
        for row in conn.execute("SELECT brand_name, rxcui FROM rxnorm_lookup"):
            rxcui_map[row[0].lower()] = row[1]

        for idx, product in enumerate(COMBO_PRODUCTS):
            setid = _search_spl(product)
            if not setid:
                log.debug("DailyMed: no SPL found for %s", product)
                continue

            spl_url = DAILYMED_SPL.format(setid=setid)
            root = _get_xml(spl_url)
            if root is None:
                continue

            ingredients = _parse_ingredients(root)
            if not ingredients:
                log.debug("DailyMed: no ingredients parsed for %s", product)
                continue

            parent_rxcui = rxcui_map.get(product.lower(), "UNKNOWN")

            for ing in ingredients:
                try:
                    conn.execute(
                        "INSERT INTO drug_ingredients "
                        "(parent_rxcui, parent_name, ingredient_name, "
                        " ingredient_rxcui, strength, source) "
                        "VALUES (?, ?, ?, ?, ?, 'dailymed')",
                        (parent_rxcui, product, ing["name"],
                         ing["rxcui"], ing["strength"]),
                    )
                    inserted += 1
                except sqlite3.IntegrityError:
                    pass

            time.sleep(RATE_LIMIT_SLEEP)
            if (idx + 1) % 10 == 0:
                log.info("DailyMed: processed %d / %d products", idx + 1, len(COMBO_PRODUCTS))

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("DailyMed: inserted %d rows into drug_ingredients", inserted)
    return inserted
