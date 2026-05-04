"""USPSTF source - Grade A and B preventive-care recommendations.

Four-tier fetch strategy, each independent and defensive:

1. **Authenticated API** (gated by env var ``USPSTF_API_KEY``). Only runs if
   the key is present. Validates responses and rejects error-message payloads.
2. **Public scrape** of ``/uspstf/recommendation-topics/uspstf-and-b-recommendations``.
   No auth required. Parses the sortable HTML table.
3. **Per-topic detail-page scrape** (B4). For every topic discovered by tier 2,
   fetches its own detail page and parses the "Recommendation Summary" table
   (Population × Recommendation × Grade) to capture sub-population rows the
   A&B list conflates into one. This is the replacement path for the unfunded
   AHRQ API — delivers structured age/sex/interval metadata without auth.
4. **Curated snapshot** (below) of ~48 Grade A/B recommendations with
   population filters (age_min, age_max, sex). Authoritative baseline with
   structured demographic filters; augmented by the three fetch tiers above.

Before inserting, the table is cleared of rows whose ``source`` starts with
``uspstf`` so stale or error-message rows from prior builds are purged.
"""
from __future__ import annotations

import logging
import os
import re
import sqlite3
import time

import requests
from bs4 import BeautifulSoup

log = logging.getLogger(__name__)

USPSTF_API = "https://data.uspreventiveservicestaskforce.org/api/json"
USPSTF_AB_PAGE = (
    "https://www.uspreventiveservicestaskforce.org/uspstf/"
    "recommendation-topics/uspstf-and-b-recommendations"
)
REQUEST_TIMEOUT = 20
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0
USER_AGENT = "aegis-health/0.1 (kb build; +https://researchcommons.ai)"

# Citation strings surfaced to end users via AegisResponse.citations
CITATION_CURATED = "USPSTF (2024 curated snapshot)"
CITATION_API = "USPSTF API"
CITATION_SCRAPE = "USPSTF A&B Recommendations list"
CITATION_SCRAPE_DETAIL = "USPSTF Recommendation Summary (detail page)"

# Per-topic scrape (B4) — pause between detail-page fetches to stay polite.
DETAIL_FETCH_DELAY = 0.5
DETAIL_MAX_TOPICS  = None  # None = all; set a small int for debugging

# Tokens that indicate an error-message payload rather than a real recommendation.
ERROR_TOKENS = ("api key", "please contact", "access denied", "unauthorized")

# Curated snapshot of Grade A and B recommendations (as of 2024-12).
# Each entry: (rec_id, title, grade, age_min, age_max, sex, description)
CURATED_RECOMMENDATIONS: list[tuple] = [
    # -- Cancer Screening ---------------------------------------
    ("USPSTF-2021-01", "Breast Cancer: Screening",
     "B", 50, 74, "female",
     "Screen with biennial mammography for women aged 50-74. "
     "Decision to start screening at age 40 should be individualized."),
    ("USPSTF-2021-02", "Cervical Cancer: Screening",
     "A", 21, 65, "female",
     "Screen every 3 years with cervical cytology in women aged 21-29. "
     "For women 30-65, screen every 3 years with cytology, every 5 years with hrHPV testing, "
     "or every 5 years with both."),
    ("USPSTF-2021-03", "Colorectal Cancer: Screening",
     "A", 45, 75, "all",
     "Screen for colorectal cancer starting at age 45 through age 75 using stool-based tests, "
     "colonoscopy, or CT colonography."),
    ("USPSTF-2021-04", "Lung Cancer: Screening",
     "B", 50, 80, "all",
     "Annual screening with low-dose CT in adults aged 50-80 who have a 20 pack-year smoking "
     "history and currently smoke or have quit within the past 15 years."),
    ("USPSTF-2018-05", "Prostate Cancer: Screening",
     "B", 55, 69, "male",
     "For men aged 55-69, the decision to undergo periodic PSA-based screening should be "
     "individualized after informed discussion."),
    ("USPSTF-2023-06", "Skin Cancer: Behavioral Counseling",
     "B", 6, 24, "all",
     "Counsel young adults, adolescents, children, and parents of young children about "
     "minimizing UV radiation exposure to reduce skin cancer risk."),

    # -- Cardiovascular -----------------------------------------
    ("USPSTF-2022-10", "Statin Use: Primary Prevention of CVD",
     "B", 40, 75, "all",
     "Prescribe a statin for primary prevention of CVD in adults 40-75 who have 1+ CVD risk "
     "factors and a 10-year CVD event risk of >=10%."),
    ("USPSTF-2022-11", "Hypertension: Screening",
     "A", 18, None, "all",
     "Screen for hypertension in adults aged 18 and older with office blood pressure measurement. "
     "Obtain out-of-office measurements for confirmatory diagnosis."),
    ("USPSTF-2022-12", "Aspirin Use: CVD Prevention",
     "B", 40, 59, "all",
     "Initiate low-dose aspirin for primary prevention of CVD and colorectal cancer in adults "
     "aged 40-59 with >=10% 10-year CVD risk. Net benefit is small."),
    ("USPSTF-2023-13", "Abdominal Aortic Aneurysm: Screening",
     "B", 65, 75, "male",
     "One-time screening ultrasonography for AAA in men aged 65-75 who have ever smoked."),
    ("USPSTF-2024-14", "Atrial Fibrillation: Screening (Stroke Prevention)",
     "B", 65, None, "all",
     "Screen for atrial fibrillation with pulse palpation or ECG in adults >=65."),

    # -- Diabetes & Metabolic -----------------------------------
    ("USPSTF-2021-20", "Prediabetes / Type 2 Diabetes: Screening",
     "B", 35, 70, "all",
     "Screen for prediabetes and type 2 diabetes in adults aged 35-70 who are overweight or obese. "
     "Clinicians should offer or refer patients with prediabetes to effective prevention interventions."),
    ("USPSTF-2022-21", "Gestational Diabetes: Screening",
     "B", 24, 44, "female",
     "Screen for gestational diabetes in asymptomatic pregnant persons at 24 weeks of gestation or after."),
    ("USPSTF-2023-22", "Obesity: Screening & Management",
     "B", 6, None, "all",
     "Screen for obesity and offer or refer patients aged 6+ with obesity to comprehensive, "
     "intensive behavioral interventions."),

    # -- Infections & Immunization ------------------------------
    ("USPSTF-2019-30", "Hepatitis B: Screening",
     "B", 15, 65, "all",
     "Screen for hepatitis B virus infection in adolescents and adults at increased risk."),
    ("USPSTF-2020-31", "Hepatitis C: Screening",
     "B", 18, 79, "all",
     "Screen for hepatitis C virus infection in adults aged 18-79."),
    ("USPSTF-2019-32", "HIV: Screening",
     "A", 15, 65, "all",
     "Screen for HIV infection in adolescents and adults aged 15-65. "
     "Younger adolescents and older adults at increased risk should also be screened."),
    ("USPSTF-2023-33", "STIs: Behavioral Counseling",
     "B", 15, 65, "all",
     "Provide behavioral counseling for all sexually active adolescents and adults at increased "
     "risk for sexually transmitted infections."),
    ("USPSTF-2019-34", "Syphilis: Screening",
     "A", 15, None, "all",
     "Screen persons at increased risk for syphilis infection."),
    ("USPSTF-2022-35", "Latent Tuberculosis: Screening",
     "B", 18, None, "all",
     "Screen for latent tuberculosis infection in populations at increased risk."),
    ("USPSTF-2024-36", "Chlamydia & Gonorrhea: Screening",
     "B", 15, 24, "female",
     "Screen sexually active women aged <=24 and older women at increased risk for chlamydia and gonorrhea."),

    # -- Mental Health & Substance Use --------------------------
    ("USPSTF-2023-40", "Depression: Screening (Adults)",
     "B", 18, None, "all",
     "Screen for depression in the general adult population, including pregnant and postpartum persons. "
     "Ensure adequate systems for diagnosis, treatment, and follow-up."),
    ("USPSTF-2022-41", "Depression: Screening (Adolescents)",
     "B", 12, 18, "all",
     "Screen for major depressive disorder in adolescents aged 12-18."),
    ("USPSTF-2023-42", "Anxiety: Screening",
     "B", 8, 64, "all",
     "Screen for anxiety disorders in adults aged 8-64, including pregnant and postpartum persons."),
    ("USPSTF-2020-43", "Unhealthy Alcohol Use: Screening",
     "B", 18, None, "all",
     "Screen for unhealthy alcohol use in adults 18 and older; provide brief behavioral counseling "
     "interventions to persons engaged in risky drinking."),
    ("USPSTF-2020-44", "Unhealthy Drug Use: Screening",
     "B", 18, None, "all",
     "Screen by asking questions about unhealthy drug use in adults aged 18 and older."),
    ("USPSTF-2021-45", "Tobacco Use: Interventions",
     "A", 18, None, "all",
     "Ask all adults about tobacco use, advise them to stop, and provide behavioral "
     "interventions and FDA-approved pharmacotherapy."),
    ("USPSTF-2020-46", "Suicide Risk: Screening (Adolescents)",
     "B", 12, 18, "all",
     "Screen for suicide risk in adolescents aged 12-18."),
    ("USPSTF-2024-47", "Intimate Partner Violence: Screening",
     "B", 14, 46, "female",
     "Screen for intimate partner violence in women of reproductive age and provide or refer "
     "those who screen positive to ongoing support services."),

    # -- Maternal / Perinatal -----------------------------------
    ("USPSTF-2021-50", "Preeclampsia: Prevention (Aspirin)",
     "B", 12, 50, "female",
     "Use low-dose aspirin (81 mg/day) as preventive medication after 12 weeks of gestation "
     "in persons at high risk for preeclampsia."),
    ("USPSTF-2023-51", "Folic Acid Supplementation",
     "A", 15, 45, "female",
     "All persons planning or capable of pregnancy should take a daily supplement containing "
     "0.4-0.8 mg of folic acid to prevent neural tube defects."),
    ("USPSTF-2019-52", "Rh(D) Incompatibility: Screening",
     "A", 15, 45, "female",
     "Screen all pregnant women for Rh(D) blood type and antibody status at first prenatal visit."),
    ("USPSTF-2019-53", "Bacteriuria: Screening (Pregnancy)",
     "A", 15, 45, "female",
     "Screen for asymptomatic bacteriuria with urine culture in pregnant persons."),
    ("USPSTF-2021-54", "Breastfeeding: Interventions",
     "B", 15, 45, "female",
     "Provide interventions during pregnancy and after birth to support breastfeeding."),

    # -- Pediatric ----------------------------------------------
    ("USPSTF-2022-60", "Vision Screening: Children",
     "B", 3, 5, "all",
     "Screen for amblyopia and its risk factors in children aged 3-5."),
    ("USPSTF-2023-61", "Lead Exposure: Screening (Children)",
     "B", 1, 5, "all",
     "Screen for elevated blood lead levels in children aged 1-5 who are at increased risk."),
    ("USPSTF-2020-62", "Obesity: Screening (Children 6+)",
     "B", 6, 18, "all",
     "Screen for obesity in children and adolescents 6+ and offer or refer to comprehensive, "
     "intensive behavioral interventions to promote improvements in weight status."),
    ("USPSTF-2024-63", "Developmental Screening",
     "B", 0, 3, "all",
     "Screen for developmental delays and disabilities using validated tools in children <=3."),

    # -- Other Preventive Services ------------------------------
    ("USPSTF-2022-70", "Falls Prevention: Exercise Interventions",
     "B", 65, None, "all",
     "Exercise interventions to prevent falls in community-dwelling adults 65 and older "
     "who are at increased risk for falls."),
    ("USPSTF-2018-71", "Osteoporosis: Screening",
     "B", 65, None, "female",
     "Screen for osteoporosis with bone measurement testing in women 65 and older and in younger "
     "postmenopausal women at increased fracture risk."),
    ("USPSTF-2024-72", "Vitamin D Deficiency: Screening (Older Adults)",
     "B", 65, None, "all",
     "Screen adults 65+ for vitamin D deficiency."),
    ("USPSTF-2021-73", "Healthy Diet & Physical Activity: Counseling",
     "B", 18, None, "all",
     "Offer or refer adults with CVD risk factors to behavioral counseling interventions to "
     "promote a healthy diet and physical activity."),
    ("USPSTF-2022-74", "Dental Caries: Fluoride Varnish (Children)",
     "B", 0, 5, "all",
     "Apply fluoride varnish to the primary teeth of all infants and children starting at the "
     "age of primary tooth eruption through age 5."),
    ("USPSTF-2023-75", "Hearing Loss: Screening (Older Adults)",
     "B", 50, None, "all",
     "Screen for hearing loss in adults 50 and older."),
    ("USPSTF-2023-76", "Chronic Kidney Disease: Screening",
     "B", 18, None, "all",
     "Screen for chronic kidney disease in adults with hypertension, diabetes, or both."),
    ("USPSTF-2024-77", "Iron Deficiency Anemia: Screening & Supplementation (Pregnancy)",
     "B", 15, 45, "female",
     "Screen for iron deficiency anemia in pregnant persons and provide iron supplementation."),
    ("USPSTF-2024-78", "BRCA-Related Cancer: Risk Assessment",
     "B", 18, None, "female",
     "Assess women with a personal or family history of breast, ovarian, tubal, or peritoneal "
     "cancer for potentially harmful BRCA1/2 mutations using validated tools."),
]


# Text tokens that flag a recommendation as sex-specific. The scraped
# A/B page doesn't expose structured sex metadata, so we infer it from
# wording in the title + description. Order matters: female tokens run
# first because some phrases (e.g. "men and women") should not match
# as male-specific.
_FEMALE_TOKENS = (
    "women", "woman", "female",
    "pregnan", "gestational", "preeclamp", "breast", "cervical",
    "ovarian", "endometrial", "uterine", "tubal", "peritoneal",
    "menopaus", "postmenopausal", "bacteriuria in",  # "bacteriuria in pregnancy"
    "breastfeed", "lactation", "mammograph", "brca",
    "folic acid", "neural tube", "rh(d)", "rh incompatibility",
    "chlamydia and gonorrhea",  # USPSTF rec is women-specific
)
_MALE_TOKENS = (
    "prostate", "erectile", "testosterone",
    # AAA is officially for men who have ever smoked in USPSTF guidance
    "abdominal aortic aneurysm",
)

_AGE_RANGE_RE = re.compile(
    r"aged?\s+(\d+)\s*(?:to|through|-|–)\s*(\d+)", re.IGNORECASE
)
_AGE_MIN_RE = re.compile(
    r"(?:aged?\s+)?(\d+)\s*(?:years?\s*)?(?:or\s+older|and\s+older|and\s+up|or\s+over|\+)",
    re.IGNORECASE,
)
_AGE_MAX_RE = re.compile(
    r"(?:aged?\s+)?(?:younger\s+than|under|up\s+to)\s+(\d+)", re.IGNORECASE
)


def _topic_of(title: str) -> str:
    """Return the ``Topic: Action`` portion of a USPSTF-formatted title.

    Scraped titles follow the pattern ``Topic: Action: Population``, e.g.
    ``Depression and Suicide Risk in Adults: Screening: adults, including
    pregnant and postpartum persons``. When inferring the primary population
    *sex* we want only the topic portion — otherwise "pregnant" mentioned as
    a sub-population in the population descriptor leaks into sex inference.
    """
    parts = title.split(": ", 2)
    if len(parts) >= 2:
        return parts[0] + ": " + parts[1]
    return title


def _sex_tokens_only(text: str) -> str:
    """Core token-matching. Returns 'female', 'male', or 'all'."""
    lowered = text.lower()
    if "men and women" in lowered or "all adults" in lowered:
        return "all"
    for t in _FEMALE_TOKENS:
        if t in lowered:
            return "female"
    for t in _MALE_TOKENS:
        if t in lowered:
            return "male"
    return "all"


def _infer_sex_from_title(full_title: str) -> str:
    """Two-pass sex inference on a USPSTF-formatted title.

    Pass 1: check topic (first two colon-separated parts). This catches
    topic-level sex clues like "Breast Cancer", "Prostate Cancer", "AAA".

    Pass 2: when the topic is sex-neutral, check the population descriptor
    (after the second colon). If it contains "adults" or "men and women",
    treat as mixed-sex; otherwise apply female/male token matching to the
    descriptor. This catches cases like "HIV: Screening: pregnant persons"
    where the topic is neutral but the population is female-only.
    """
    topic_sex = _sex_tokens_only(_topic_of(full_title))
    if topic_sex != "all":
        return topic_sex

    parts = full_title.split(": ", 2)
    if len(parts) < 3:
        return "all"
    pop = parts[2].lower()
    # A mixed-sex qualifier in the population descriptor → all.
    if "adults" in pop or "men and women" in pop or "both sexes" in pop:
        return "all"
    return _sex_tokens_only(pop)


# Keep the older name for backward-compat with any external callers.
def _infer_sex(text: str) -> str:
    """Convenience wrapper. If the text contains colon separators, use the
    two-pass logic; otherwise fall back to plain token matching."""
    if text.count(": ") >= 2:
        return _infer_sex_from_title(text)
    return _sex_tokens_only(text)


def _infer_age_range(text: str) -> tuple[int | None, int | None]:
    """Return (age_min, age_max) inferred from text.

    Looks for patterns like "aged 50 to 74", "65 or older", "younger than 18".
    Returns (None, None) if no age signal is found.
    """
    m = _AGE_RANGE_RE.search(text)
    if m:
        lo, hi = int(m.group(1)), int(m.group(2))
        if lo <= hi:
            return lo, hi

    m = _AGE_MIN_RE.search(text)
    age_min = int(m.group(1)) if m else None

    m = _AGE_MAX_RE.search(text)
    age_max = int(m.group(1)) if m else None

    # Generic hints when no explicit number found.
    if age_min is None and age_max is None:
        lowered = text.lower()
        if "children" in lowered or "pediatric" in lowered or "infant" in lowered:
            return 0, 17
        if "adolescent" in lowered:
            return 12, 18
        if "older adults" in lowered or "elderly" in lowered:
            return 65, None
        if "adults" in lowered:
            return 18, None
    return age_min, age_max


def _looks_like_error(text: str) -> bool:
    """True if text contains any token characteristic of an error/auth message."""
    if not text:
        return False
    lowered = text.lower()
    return any(token in lowered for token in ERROR_TOKENS)


def _is_valid_record(title: str, grade: str, description: str) -> bool:
    """Validate a fetched record before insertion."""
    if grade not in ("A", "B"):
        return False
    if not title or _looks_like_error(title):
        return False
    if _looks_like_error(description):
        return False
    return True


def _fetch_api() -> list[dict]:
    """Fetch from the authenticated USPSTF API. Skips silently if no key set."""
    api_key = os.environ.get("USPSTF_API_KEY")
    if not api_key:
        log.info("USPSTF: no USPSTF_API_KEY set - skipping API fetch")
        return []

    params = {"key": api_key}
    headers = {"User-Agent": USER_AGENT}
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(
                USPSTF_API, params=params, headers=headers, timeout=REQUEST_TIMEOUT
            )
            resp.raise_for_status()
            data = resp.json()
            if isinstance(data, list):
                return data
            if isinstance(data, dict):
                return data.get("specificRecommendations", data.get("data", []))
            return []
        except requests.RequestException as exc:
            log.warning("USPSTF API failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return []


def _fetch_scrape() -> list[dict]:
    """Scrape the public Grade A/B recommendations list page.

    Returns records as dicts: ``{title, grade, description, url}``.
    Population fields are left unset; the curated list supplies those.
    """
    headers = {"User-Agent": USER_AGENT}
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(USPSTF_AB_PAGE, headers=headers, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            break
        except requests.RequestException as exc:
            log.warning("USPSTF scrape failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    else:
        return []

    soup = BeautifulSoup(resp.text, "lxml")
    table = soup.find("table")
    if not table:
        log.warning("USPSTF scrape: no table found on A&B page")
        return []

    records: list[dict] = []
    for row in table.find_all("tr"):
        cells = row.find_all(["td", "th"])
        if len(cells) < 3:
            continue
        # Skip header row
        if cells[0].name == "th":
            continue

        title_cell = cells[0]
        link = title_cell.find("a")
        title = (link.get_text(strip=True) if link else title_cell.get_text(strip=True)).strip()
        url = ""
        if link and link.has_attr("href"):
            href = link["href"]
            url = (
                href if href.startswith("http")
                else f"https://www.uspreventiveservicestaskforce.org{href}"
            )

        description = cells[1].get_text(strip=True)
        grade = cells[2].get_text(strip=True).upper()

        if not title or grade not in ("A", "B"):
            continue

        records.append(
            {"title": title, "description": description, "grade": grade, "url": url}
        )

    log.info("USPSTF scrape: parsed %d records from A&B page", len(records))
    return records


def _parse_detail_page(html: str) -> list[dict]:
    """Parse the Recommendation Summary table on a USPSTF detail page.

    Returns one dict per data row:
        {"population": str, "recommendation": str, "grade": str}

    The detail page has a ``<table class="table">`` whose first row headers
    are ``Population``, ``Recommendation``, ``Grade``. Sub-population rows
    often have distinct grades (e.g. colorectal: A for 50-75, B for 45-49,
    C for 76-85). We keep only A/B here; lower grades are filtered
    downstream by ``_is_valid_record``.
    """
    soup = BeautifulSoup(html, "lxml")
    # Recommendation Summary table is the first .table whose header row
    # mentions "Population" and "Grade".
    for tbl in soup.find_all("table", class_="table"):
        header = tbl.find("tr")
        if not header:
            continue
        header_text = [c.get_text(strip=True).lower() for c in header.find_all(["th", "td"])]
        if not header_text or "population" not in header_text[0]:
            continue
        if "grade" not in (header_text[-1] if header_text else ""):
            continue

        out: list[dict] = []
        for row in tbl.find_all("tr")[1:]:
            cells = row.find_all(["td", "th"])
            if len(cells) < 3:
                continue
            pop = cells[0].get_text(" ", strip=True)
            rec = cells[1].get_text(" ", strip=True)
            grade = cells[2].get_text(" ", strip=True).upper()
            if not pop or not rec or grade not in ("A", "B"):
                continue
            # Normalise internal whitespace (the cells often have newlines
            # from tooltip/screen-reader copy).
            rec = re.sub(r"\s+", " ", rec)
            out.append({"population": pop, "recommendation": rec, "grade": grade})
        return out
    return []


def _fetch_detail(url: str) -> list[dict]:
    """Fetch one topic's detail page and return its parsed summary rows."""
    if not url:
        return []
    headers = {"User-Agent": USER_AGENT}
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(url, headers=headers, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            return _parse_detail_page(resp.text)
        except requests.RequestException as exc:
            log.warning(
                "USPSTF detail fetch failed (attempt %d/%d) %s: %s",
                attempt, RETRY_COUNT, url, exc,
            )
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return []


def _build_detail_cache(topics: list[dict]) -> dict[str, list[dict]]:
    """Fetch each unique detail URL once. Returns ``{url: [parsed rows]}``."""
    cache: dict[str, list[dict]] = {}
    urls = {t.get("url") for t in topics if t.get("url")}
    urls.discard(None)
    urls.discard("")
    for i, url in enumerate(sorted(urls)):
        cache[url] = _fetch_detail(url)
        time.sleep(DETAIL_FETCH_DELAY)
        if (i + 1) % 20 == 0:
            log.info("USPSTF detail: cached %d / %d unique URLs", i + 1, len(urls))
    log.info("USPSTF detail: cached %d unique URLs", len(cache))
    return cache


def _pick_best_match(topic_title: str, detail_rows: list[dict]) -> dict | None:
    """Pick the detail-page row whose Population cell best matches the
    sub-population suffix encoded in the A&B-list topic title.

    Match heuristic: the detail-page Population text should appear
    (case-insensitive) as a substring somewhere in the topic title.
    If no row matches, return the first row whose grade matches — it
    is usually the canonical adult recommendation.
    """
    if not detail_rows:
        return None
    title_lower = topic_title.lower()
    best: dict | None = None
    best_overlap = 0
    for r in detail_rows:
        pop_lower = r["population"].lower().strip()
        if not pop_lower:
            continue
        if pop_lower in title_lower:
            # Prefer the longest population-string match (more specific).
            if len(pop_lower) > best_overlap:
                best = r
                best_overlap = len(pop_lower)
    if best is not None:
        return best
    # Fall back to the first Grade-A row, else first overall.
    for r in detail_rows:
        if r.get("grade") == "A":
            return r
    return detail_rows[0]


def _purge_stale(conn: sqlite3.Connection) -> int:
    """Delete all prior USPSTF rows so the build is idempotent.

    Returns number of rows purged.
    """
    cur = conn.execute(
        "DELETE FROM guidelines WHERE source LIKE 'USPSTF%' OR source LIKE 'uspstf%'"
    )
    purged = cur.rowcount
    if purged:
        log.info("USPSTF: purged %d stale rows", purged)
    return purged


def build(db_path: str) -> int:
    """Populate the guidelines table. Returns number of rows inserted."""
    log.info("USPSTF: starting build")
    conn = sqlite3.connect(db_path)
    inserted = 0
    rejected = 0

    try:
        _purge_stale(conn)

        # Curated list first - authoritative baseline with population filters
        for rec in CURATED_RECOMMENDATIONS:
            rec_id, title, grade, age_min, age_max, sex, desc = rec
            if not _is_valid_record(title, grade, desc):
                rejected += 1
                continue
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO guidelines "
                    "(recommendation_id, title, grade, population_age_min, "
                    " population_age_max, population_sex, description, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (rec_id, title, grade, age_min, age_max, sex, desc, CITATION_CURATED),
                )
                inserted += 1
            except sqlite3.IntegrityError:
                pass

        # API augmentation (only if key set)
        api_recs = _fetch_api()
        for rec in api_recs:
            title = (rec.get("title") or "").strip()
            grade = (rec.get("grade") or "").strip().upper()
            desc = rec.get("text") or rec.get("description") or ""
            if not _is_valid_record(title, grade, desc):
                rejected += 1
                continue
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO guidelines "
                    "(recommendation_id, title, grade, population_sex, "
                    " description, clinical_url, source) "
                    "VALUES (?, ?, ?, 'all', ?, ?, ?)",
                    (
                        rec.get("id", ""),
                        title,
                        grade,
                        desc,
                        rec.get("url", ""),
                        CITATION_API,
                    ),
                )
                inserted += 1
            except sqlite3.IntegrityError:
                pass

        # Scrape augmentation (always attempted)
        scrape_recs = _fetch_scrape()

        # Tier 4: per-topic detail-page scrape. The A&B list already ships
        # one row per sub-population; detail pages give us *structured*
        # Population + Recommendation + Grade for each, which yields cleaner
        # age/sex inference than parsing the appended population substring
        # from the A&B-list title. We insert ONE enriched row per A&B topic.
        detail_cache = _build_detail_cache(scrape_recs) if scrape_recs else {}
        detail_titles_inserted: set[str] = set()
        for topic in scrape_recs:
            title = topic["title"]
            url   = topic.get("url") or ""
            grade = topic["grade"]
            fallback_desc = topic.get("description", "")
            if not _is_valid_record(title, grade, fallback_desc):
                continue
            match = _pick_best_match(title, detail_cache.get(url, []))
            if match and match.get("grade") in ("A", "B"):
                grade_use = match["grade"]
                desc_use  = match["recommendation"]
                pop_text  = match["population"]
                age_min, age_max = _infer_age_range(pop_text)
                if age_min is None and age_max is None:
                    age_min, age_max = _infer_age_range(desc_use)
                inferred_sex = _sex_tokens_only(pop_text) or "all"
                if inferred_sex == "all":
                    inferred_sex = _infer_sex_from_title(title)
                citation = CITATION_SCRAPE_DETAIL
            else:
                # No detail-page match — fall back to title-based inference.
                grade_use = grade
                desc_use  = fallback_desc
                age_min, age_max = _infer_age_range(title)
                if age_min is None and age_max is None:
                    age_min, age_max = _infer_age_range(fallback_desc)
                inferred_sex = _infer_sex_from_title(title)
                citation = CITATION_SCRAPE
            if title.lower() in {t.lower() for t in detail_titles_inserted}:
                continue
            # Curated rows take precedence — skip if any existing row shares this title.
            existing = conn.execute(
                "SELECT 1 FROM guidelines WHERE LOWER(title) = LOWER(?) LIMIT 1",
                (title,),
            ).fetchone()
            if existing:
                continue
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO guidelines "
                    "(title, grade, population_age_min, population_age_max, "
                    " population_sex, description, clinical_url, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (title, grade_use, age_min, age_max, inferred_sex,
                     desc_use, url, citation),
                )
                if conn.total_changes:
                    inserted += 1
                    detail_titles_inserted.add(title)
            except sqlite3.IntegrityError:
                pass

        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info(
        "USPSTF: inserted %d rows, rejected %d invalid candidates", inserted, rejected
    )
    return inserted
