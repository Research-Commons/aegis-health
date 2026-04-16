"""openFDA source – fetches drug labels for drugs, interactions, and warnings.

Uses the openFDA drug label API:
  https://api.fda.gov/drug/label.json
"""
from __future__ import annotations

import logging
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
RATE_LIMIT_SLEEP = 0.6  # openFDA: 120 req/min without key ≈ 2/s


def _get_json(url: str, params: dict | None = None) -> dict | None:
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


def _fetch_label(drug_name: str, skip: int = 0) -> dict | None:
    params = {
        "search": f'openfda.generic_name:"{drug_name}"',
        "limit": PAGE_SIZE,
        "skip": skip,
    }
    return _get_json(FDA_LABEL_URL, params)


def _parse_interacting_drugs(text: str) -> list[str]:
    """Extract drug names mentioned in an interaction section."""
    patterns = [
        r"(?:concurrent|concomitant|co-administration)\s+(?:use\s+)?(?:of|with)\s+(\w+)",
        r"(\w+)\s+(?:may|can|will|could)\s+(?:increase|decrease|potentiate|inhibit)",
        r"(?:avoid|do not (?:use|take))\s+(?:with\s+)?(\w+)",
    ]
    found: set[str] = set()
    for pat in patterns:
        for m in re.finditer(pat, text, re.IGNORECASE):
            candidate = m.group(1).strip().capitalize()
            if len(candidate) > 3:
                found.add(candidate)
    return list(found)


# ── Public build functions ────────────────────────────────────────


def build_drugs(db_path: str) -> int:
    """Populate the drugs table from openFDA labels."""
    log.info("openFDA: building drugs table")
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        drugs = _rxcui_list(conn)
        for idx, (rxcui, generic) in enumerate(drugs):
            data = _fetch_label(generic)
            if not data or "results" not in data:
                continue

            for result in data["results"][:3]:
                openfda = result.get("openfda", {})
                brand_names = openfda.get("brand_name", [generic])
                dosage = _join_field(result, "dosage_forms_and_strengths")
                route_list = openfda.get("route", [])
                route = route_list[0] if route_list else None
                labeler = (openfda.get("manufacturer_name") or [None])[0]
                desc = _join_field(result, "description")

                for bname in brand_names[:2]:
                    try:
                        conn.execute(
                            "INSERT INTO drugs "
                            "(rxcui, drug_name, generic_name, dosage_form, route, labeler, description, source) "
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 'openfda')",
                            (rxcui, bname, generic, dosage, route, labeler,
                             (desc or "")[:2000]),
                        )
                        inserted += 1
                    except sqlite3.IntegrityError:
                        pass

            time.sleep(RATE_LIMIT_SLEEP)
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

        for idx, (rxcui, generic) in enumerate(drugs):
            data = _fetch_label(generic)
            if not data or "results" not in data:
                continue

            for result in data["results"][:2]:
                interaction_text = _join_field(result, "drug_interactions")
                if not interaction_text:
                    continue

                mentioned = _parse_interacting_drugs(interaction_text)
                for other_name in mentioned:
                    other_rxcui = rxcui_by_name.get(other_name.lower())
                    if not other_rxcui or other_rxcui == rxcui:
                        continue

                    severity = _extract_severity(interaction_text)
                    try:
                        conn.execute(
                            "INSERT INTO interactions "
                            "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, "
                            " severity, description, source) "
                            "VALUES (?, ?, ?, ?, ?, ?, 'openfda')",
                            (rxcui, generic, other_rxcui, other_name,
                             severity, interaction_text[:2000]),
                        )
                        inserted += 1
                    except sqlite3.IntegrityError:
                        pass

            time.sleep(RATE_LIMIT_SLEEP)
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

            time.sleep(RATE_LIMIT_SLEEP)
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
