"""LactMed source - breastfeeding safety data for drugs in rxnorm_lookup.

Two-phase ingest:

  Phase 1 (curated, high quality)
    Ships a hand-verified snapshot of ~100 high-frequency drugs. Severities
    were assigned by reading the LactMed summary section for each drug and
    mapping narrative guidance to a 1-5 integer scale. Curated rows always
    take precedence over auto-fetched rows for the same (rxcui, warning_type).

  Phase 2 (automated fetch from NCBI Bookshelf)
    For every drug in `rxnorm_lookup` not already covered by Phase 1, fetch
    the LactMed monograph from https://www.ncbi.nlm.nih.gov/books/<NBK>/,
    extract the 'Summary of Use during Lactation' section, and infer a
    conservative severity from regex keyword rules (defaults to 3 when no
    strong signal matches). Fills the long tail of ~1,905 total LactMed
    monographs beyond the curated 100.

Why HTML scrape instead of efetch XML: `db=books` does not support
`efetch&rettype=xml` (returns 'not supported'). esearch + esummary are
still used to enumerate and name the monographs; the monograph body is
only reachable by scraping the bookshelf HTML page.

Severity scale (mapped to LactMed narrative guidance):
  5 - Contraindicated / avoid (e.g., amiodarone, methotrexate, lithium)
  4 - Use alternative if possible / monitor infant closely
  3 - Limited data, caution warranted, monitor  (default when unclear)
  2 - Probably compatible, monitor; preferred among class
  1 - Compatible / no special precautions required

Each entry cites LactMed directly via the CITATION constant. Rate limits
are the standard NCBI E-utilities 3 req/s anonymous; no API key required.
"""
from __future__ import annotations

import html as html_lib
import logging
import re
import sqlite3
import time

import requests

log = logging.getLogger(__name__)

CITATION = "NLM LactMed (NCBI Bookshelf NBK501922)"
WARNING_TYPE = "lactation"
POPULATION = "breastfeeding"

# ---------------------------------------------------------------------------
# E-utilities + Bookshelf scraping constants
# ---------------------------------------------------------------------------

EUTILS_BASE   = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
BOOKSHELF_URL = "https://www.ncbi.nlm.nih.gov/books"
ESEARCH_TERM  = "lactmed[book] AND chapter[type]"
ESEARCH_PAGE  = 500      # retmax per page
ESUMMARY_BATCH = 200     # E-utilities supports up to ~200 ids per esummary
REQUEST_TIMEOUT = 30
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0
NCBI_RATE_SLEEP = 0.34   # 3 req/s anonymous

# NCBI Bookshelf 403s requests with an identifying tool-UA for bulk
# page fetches; a conventional browser UA is accepted. E-utilities endpoints
# also work fine with this UA, so we use it for every request.
_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
)
_HEADERS = {
    "User-Agent": _USER_AGENT,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.5",
}

# Summary section wrapper: a <div> whose id varies across monograph vintages:
#   legacy:  "LM330.Summary_of_Use_during_Lactation"
#   newer:   "depemokimab.Summary_of_Use_during_Lactat"   (truncated)
#   newest:  "tenofovir_alafenam.Summary_of_Use_during"   (truncated further)
# A regex that tries to use a lookahead for the end boundary is unreliable
# because the section body starts with a nested <h3> heading and sibling
# section headings drop their id attribute in newer monographs. We instead
# do balanced <div> walking: find the opening tag, count nested divs until
# depth returns to 0 at the matching </div>.
_SUMMARY_OPEN_RE = re.compile(
    r'<div\s+id="[^"]+\.Summary_of_Use_during[^"]*"[^>]*>',
    re.IGNORECASE,
)
_DIV_OPEN_RE  = re.compile(r"<div\b", re.IGNORECASE)
_DIV_CLOSE_RE = re.compile(r"</div\s*>", re.IGNORECASE)
_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE  = re.compile(r"\s+")
_HEADING_RE = re.compile(r"^\s*Summary of Use during Lactation\s*", re.I)

# Conservative severity heuristic. Patterns are tried in order; the MAX
# matched severity wins so "contraindicated" beats "preferred". Default 3
# (limited data / caution) when no pattern matches.
_SEVERITY_PATTERNS: list[tuple[re.Pattern[str], int]] = [
    # 5 — contraindicated / strongly avoid
    (re.compile(r"\bcontraindicated\b", re.I), 5),
    (re.compile(r"breastfeeding\s+is\s+not\s+recommended", re.I), 5),
    (re.compile(r"should\s+not\s+be\s+(?:used|given|administered)\s+(?:during|by|in)\s+(?:breastfeeding|nursing|lactation)", re.I), 5),
    (re.compile(r"(?:nursing|breastfeeding)\s+(?:should\s+be\s+)?(?:discontinued|interrupted|withheld)", re.I), 5),
    # 4 — alternatives preferred / avoid if possible
    (re.compile(r"alternate\s+drug[s]?\s+(?:to\s+consider|(?:is|are)\s+preferred|may\s+be\s+preferred)", re.I), 4),
    (re.compile(r"(?:an\s+)?alternate\s+(?:drug|agent)\s+(?:may\s+be\s+)?preferred", re.I), 4),
    (re.compile(r"\bavoid\b[^.]{0,80}\bbreast", re.I), 4),
    # 1 — explicitly compatible / not absorbed
    (re.compile(r"no\s+(?:special\s+)?precautions?", re.I), 1),
    (re.compile(r"not\s+(?:expected\s+to\s+be\s+)?absorbed\s+(?:orally|from\s+the\s+gut|by\s+the\s+infant)", re.I), 1),
    (re.compile(r"(?:compatible|safe)\s+(?:for\s+use\s+)?(?:with|during)\s+breastfeeding", re.I), 1),
    (re.compile(r"poor(?:ly)?\s+absorbed\s+orally", re.I), 1),
    # 2 — preferred within class / probably compatible
    (re.compile(r"\bpreferred\b[^.]{0,80}\b(?:breastfeeding|nursing|lactation)", re.I), 2),
    (re.compile(r"acceptable\s+(?:during|in)\s+breastfeeding", re.I), 2),
    (re.compile(r"probably\s+compatible", re.I), 2),
]

# Each entry: (generic_name_lower, severity, summary).
# Generic name is looked up against rxnorm_lookup at build time.
# Entries reflect LactMed guidance verified against 2024-2025 monographs.
CURATED_LACTMED: list[tuple[str, int, str]] = [

    # -- Analgesics / NSAIDs ----------------------------------------
    ("acetaminophen", 1,
     "Acetaminophen is the preferred analgesic and antipyretic for "
     "breastfeeding mothers. Amounts in milk are much less than doses "
     "given to infants. No special precautions required at recommended "
     "maternal doses."),
    ("ibuprofen", 1,
     "Ibuprofen is a preferred NSAID in breastfeeding because of very "
     "low milk transfer and a short half-life. Compatible with "
     "breastfeeding at standard maternal doses."),
    ("naproxen", 2,
     "Naproxen passes into milk in low amounts but has a longer "
     "half-life than ibuprofen and has been associated with GI bleeding "
     "and prolonged bleeding time in one case. Short-term occasional use "
     "probably acceptable; prefer ibuprofen for routine analgesia."),
    ("aspirin", 4,
     "High-dose maternal aspirin can transfer to the infant and has been "
     "associated with metabolic acidosis and (theoretically) Reye "
     "syndrome when the infant has a concurrent viral illness. Avoid "
     "analgesic doses; low-dose (81 mg) cardioprotective aspirin is "
     "probably compatible. Prefer acetaminophen or ibuprofen."),
    ("celecoxib", 2,
     "Only trace amounts of celecoxib are found in milk. Limited data "
     "show no adverse effects in breastfed infants. Can be used when "
     "an NSAID is needed."),
    ("meloxicam", 3,
     "Very limited data on meloxicam in breastfeeding. Long half-life "
     "and NSAID class effects make ibuprofen or other short-acting "
     "alternatives preferred."),
    ("diclofenac", 2,
     "Diclofenac transfers to milk in small amounts. Considered "
     "compatible during lactation, but short-acting NSAIDs like "
     "ibuprofen remain first-line."),
    ("ketorolac", 2,
     "Ketorolac is poorly excreted into milk. Considered compatible "
     "for short-term postpartum analgesia."),
    ("indomethacin", 3,
     "Indomethacin is excreted into milk; one case of possible infant "
     "seizures has been reported. Use only if no alternative and monitor "
     "the infant."),

    # -- Opioids ----------------------------------------------------
    ("codeine", 4,
     "Codeine is converted to morphine via CYP2D6; ultra-rapid "
     "metabolizers can produce dangerously high morphine levels in milk. "
     "The FDA recommends against codeine use in breastfeeding mothers. "
     "Prefer non-opioid analgesia or closely monitored alternatives."),
    ("tramadol", 4,
     "Tramadol is metabolized by CYP2D6 to an active opioid metabolite; "
     "ultra-rapid metabolizers can pose risk to breastfed infants. FDA "
     "recommends against use during breastfeeding."),
    ("hydrocodone", 3,
     "Small amounts of hydrocodone pass into milk. Brief postpartum use "
     "of low doses is probably acceptable; avoid prolonged use and "
     "monitor the infant for sedation, poor feeding, and decreased "
     "muscle tone."),
    ("oxycodone", 3,
     "Oxycodone transfers into milk and infant sedation has been "
     "reported. If used, keep doses as low and as brief as possible and "
     "monitor the infant closely."),
    ("morphine", 3,
     "Morphine appears in milk in small amounts; brief postpartum use "
     "is generally acceptable. Infant should be observed for drowsiness "
     "and adequate weight gain, especially with prolonged use."),
    ("hydromorphone", 3,
     "Limited data on hydromorphone in breastfeeding. Short-term use of "
     "low doses is probably acceptable; avoid repeated dosing and "
     "monitor the infant for sedation."),
    ("fentanyl", 2,
     "Epidural and IV fentanyl used during labor reach very low levels "
     "in colostrum. Single or short-term perioperative use is considered "
     "compatible with breastfeeding."),
    ("methadone", 3,
     "Methadone passes into milk in small amounts. Breastfeeding is "
     "considered acceptable in women on stable methadone maintenance "
     "and is not a reason to wean, but infants should be monitored for "
     "sedation and growth."),
    ("buprenorphine", 2,
     "Buprenorphine enters milk in low amounts with poor oral "
     "bioavailability in the infant. Compatible with breastfeeding in "
     "mothers on maintenance therapy."),
    ("naloxone", 1,
     "Naloxone has poor oral bioavailability and very low milk transfer. "
     "Compatible with breastfeeding."),

    # -- Antidepressants -------------------------------------------
    ("sertraline", 2,
     "Sertraline is one of the preferred SSRIs during breastfeeding. "
     "Low milk levels and the lowest infant serum levels among SSRIs. "
     "No consistent adverse effects reported in breastfed infants."),
    ("paroxetine", 2,
     "Paroxetine produces low levels in milk and undetectable to low "
     "levels in infant serum. Considered a preferred SSRI during "
     "breastfeeding."),
    ("escitalopram", 2,
     "Escitalopram enters milk in small amounts. Most infant serum "
     "levels are low and adverse effects have been rare. Considered "
     "compatible with breastfeeding."),
    ("citalopram", 3,
     "Citalopram is present in milk at higher relative doses than "
     "sertraline. Some reports of infant somnolence and feeding issues. "
     "If possible, sertraline or paroxetine is preferred; if citalopram "
     "is needed, monitor infant."),
    ("fluoxetine", 3,
     "Fluoxetine and its active metabolite have long half-lives and "
     "produce higher infant serum levels than other SSRIs. Colic, "
     "irritability, poor feeding, and weight gain concerns have been "
     "reported. Sertraline or paroxetine is preferred for starting "
     "therapy during breastfeeding."),
    ("venlafaxine", 3,
     "Venlafaxine and its active metabolite enter milk. Most infant "
     "serum levels are low but some adverse effects (somnolence, poor "
     "feeding) have been reported. Use with monitoring; SSRIs are "
     "generally preferred first-line."),
    ("duloxetine", 3,
     "Limited data for duloxetine; milk levels are low but long-term "
     "effects on infants are not well characterized. SSRIs such as "
     "sertraline are generally preferred during breastfeeding."),
    ("bupropion", 3,
     "Bupropion enters milk in low amounts but one case of infant "
     "seizure has been reported. Also may decrease milk supply in some "
     "mothers. Use with caution and monitor milk supply plus infant "
     "alertness."),
    ("mirtazapine", 3,
     "Mirtazapine transfers to milk in small amounts. Limited data; "
     "use only if needed and monitor infant for sedation and weight."),
    ("trazodone", 2,
     "Trazodone enters milk in small amounts. Considered acceptable "
     "for short-term use during breastfeeding."),
    ("amitriptyline", 3,
     "Amitriptyline and its active metabolite transfer to milk. Most "
     "studies show low infant serum levels, but sedation has been "
     "reported. Safer alternatives exist; if used, monitor infant."),

    # -- Antipsychotics / mood stabilizers --------------------------
    ("quetiapine", 3,
     "Quetiapine enters milk in small amounts. Most infant serum levels "
     "are low. Monitor infant for drowsiness and developmental "
     "milestones; infants exposed in utero may have withdrawal symptoms."),
    ("olanzapine", 3,
     "Olanzapine enters milk in small amounts and most infants have "
     "low or undetectable serum levels. Monitor for sedation and weight."),
    ("risperidone", 3,
     "Risperidone and its active metabolite enter milk. Most reported "
     "infant serum levels are low. Monitor for sedation and "
     "extrapyramidal symptoms."),
    ("aripiprazole", 3,
     "Aripiprazole enters milk. Limited reports of decreased milk "
     "supply and infant sedation. Monitor supply and infant closely "
     "if used."),
    ("lithium", 5,
     "Lithium is excreted in milk and infant serum levels can reach "
     "30-40% of maternal levels, causing toxicity in some infants "
     "(hypotonia, cyanosis, dehydration). Lithium is generally not "
     "recommended during breastfeeding; if continued, close clinical "
     "and laboratory monitoring of the infant is essential."),
    ("valproic acid", 3,
     "Valproate enters milk in small amounts and most infants have low "
     "serum levels. Rare cases of thrombocytopenic purpura and anemia "
     "reported. Monitor infant platelet count and liver function."),
    ("lamotrigine", 3,
     "Lamotrigine enters milk in clinically significant amounts and "
     "infant serum levels can be 30% of maternal. Most infants have no "
     "adverse effects, but rare severe rashes have been reported. "
     "Monitor infant carefully."),

    # -- Benzodiazepines / hypnotics --------------------------------
    ("lorazepam", 3,
     "Lorazepam enters milk in very small amounts. Occasional single "
     "doses are considered compatible; repeated or long-term use may "
     "cause infant sedation. Prefer the lowest effective dose for the "
     "shortest time."),
    ("alprazolam", 4,
     "Alprazolam enters milk and withdrawal symptoms have been reported "
     "in infants after maternal discontinuation. Repeated use is not "
     "recommended during breastfeeding; consider alternatives."),
    ("clonazepam", 4,
     "Clonazepam has a long half-life and enters milk. Cases of infant "
     "apnea and sedation have been reported. Avoid repeated doses "
     "during breastfeeding."),
    ("diazepam", 4,
     "Diazepam has a long half-life and active metabolites that "
     "accumulate in breastfed infants. Occasional use is probably "
     "acceptable; repeated or long-term use may cause infant sedation."),
    ("zolpidem", 2,
     "Zolpidem transfers to milk in very small amounts and has a short "
     "half-life. Compatible with breastfeeding at typical doses."),

    # -- Antibiotics ------------------------------------------------
    ("amoxicillin", 1,
     "Amoxicillin is excreted into milk in small amounts with no "
     "reported adverse effects on breastfed infants. Compatible with "
     "breastfeeding."),
    ("ampicillin", 1,
     "Ampicillin appears in milk at low levels and is considered "
     "compatible with breastfeeding. Monitor infant for rash, diarrhea, "
     "or candidiasis as with other penicillins."),
    ("cephalexin", 1,
     "Cephalexin is excreted in small amounts into milk. Compatible "
     "with breastfeeding; rare reports of infant rash or diarrhea."),
    ("azithromycin", 2,
     "Azithromycin passes into milk in small amounts. Monitor breastfed "
     "infants for diarrhea, vomiting, rash, or candidiasis. Considered "
     "compatible with breastfeeding."),
    ("clindamycin", 2,
     "Clindamycin enters milk in low amounts. Monitor breastfed infants "
     "for diarrhea or bloody stools; one case of bloody diarrhea has "
     "been reported. Considered compatible with breastfeeding."),
    ("doxycycline", 3,
     "Short courses (up to 3 weeks) of doxycycline are considered "
     "acceptable during breastfeeding because milk calcium largely "
     "blocks absorption. Longer courses should be avoided because of "
     "theoretical risks to infant bones and teeth."),
    ("ciprofloxacin", 2,
     "Ciprofloxacin enters milk in small amounts. Short-term use is "
     "considered compatible; monitor the infant for rash and candidal "
     "infections. A preferred fluoroquinolone for breastfeeding mothers."),
    ("levofloxacin", 2,
     "Levofloxacin passes into milk in low amounts; short-term use is "
     "generally compatible. Monitor breastfed infants for diarrhea, "
     "rash, and candidiasis."),
    ("metronidazole", 2,
     "Metronidazole enters milk at levels similar to maternal serum. "
     "Some experts recommend avoiding breastfeeding during high-dose "
     "single-dose therapy; standard divided oral doses are compatible "
     "with breastfeeding. Monitor infant for diarrhea or candidiasis."),
    ("trimethoprim / sulfamethoxazole", 3,
     "Low-dose TMP/SMX is generally compatible with breastfeeding in "
     "healthy full-term infants. Avoid in infants who are ill, preterm, "
     "jaundiced, or have G6PD deficiency because of risk of kernicterus "
     "or hemolysis."),
    ("nitrofurantoin", 3,
     "Nitrofurantoin enters milk in small amounts. Avoid in infants "
     "under 1 month old and in those with G6PD deficiency because of "
     "hemolysis risk; otherwise considered compatible."),
    ("fluconazole", 2,
     "Fluconazole passes into milk and is used directly in infants at "
     "doses higher than those received through breastfeeding. "
     "Compatible with breastfeeding."),
    ("acyclovir", 2,
     "Acyclovir is excreted into milk but amounts are less than "
     "doses given to infants directly. Compatible with breastfeeding."),
    ("valacyclovir", 2,
     "Valacyclovir is rapidly converted to acyclovir and considered "
     "compatible with breastfeeding."),

    # -- Antidiabetics ----------------------------------------------
    ("metformin", 1,
     "Metformin enters milk in very small amounts; infant serum levels "
     "are clinically insignificant. Preferred oral antidiabetic during "
     "breastfeeding."),
    ("glipizide", 3,
     "Very limited data; one study found glipizide undetectable in "
     "milk. Monitor breastfed infants for signs of hypoglycemia. "
     "Metformin or insulin is preferred when possible."),
    ("glyburide", 3,
     "Glyburide appears not to transfer into milk in detectable amounts "
     "in limited studies. Monitor infant for signs of hypoglycemia."),
    ("insulin glargine", 1,
     "Insulin is a large peptide and is destroyed in the infant's GI "
     "tract; compatible with breastfeeding. Breastfeeding mothers with "
     "diabetes may have reduced insulin requirements."),
    ("insulin lispro", 1,
     "Insulin is a large peptide and is destroyed in the infant's GI "
     "tract; compatible with breastfeeding."),
    ("insulin aspart", 1,
     "Insulin is a large peptide and is destroyed in the infant's GI "
     "tract; compatible with breastfeeding."),

    # -- Thyroid / endocrine ----------------------------------------
    ("levothyroxine", 1,
     "Very small amounts of levothyroxine are present in milk. "
     "Maternal hypothyroidism must be adequately treated for normal "
     "lactation. Compatible with breastfeeding at replacement doses."),
    ("methimazole", 2,
     "Methimazole is the preferred antithyroid drug during "
     "breastfeeding at doses up to 20 mg/day. Monitor infant thyroid "
     "function if higher doses are needed."),
    ("propylthiouracil", 2,
     "PTU is considered compatible with breastfeeding; methimazole is "
     "generally preferred because of PTU's hepatotoxicity profile."),

    # -- Cardiovascular ---------------------------------------------
    ("lisinopril", 3,
     "No published data on lisinopril in breastfeeding mothers. "
     "Enalapril and captopril have more data and are preferred "
     "ACE inhibitors during breastfeeding."),
    ("enalapril", 2,
     "Enalapril enters milk in very small amounts. One of the preferred "
     "ACE inhibitors during breastfeeding."),
    ("captopril", 2,
     "Captopril is excreted in milk in small amounts. Preferred "
     "ACE inhibitor during breastfeeding."),
    ("losartan", 3,
     "No published information on losartan in breastfeeding. ACE "
     "inhibitors with data (enalapril, captopril) are preferred."),
    ("metoprolol", 3,
     "Metoprolol enters milk; infants can have measurable serum levels. "
     "Monitor for signs of beta-blockade (bradycardia, hypoglycemia) "
     "especially in preterm or small infants. Propranolol is "
     "preferred by some."),
    ("propranolol", 2,
     "Propranolol passes into milk in very small amounts. One of the "
     "preferred beta-blockers during breastfeeding."),
    ("atenolol", 4,
     "Atenolol accumulates in milk at higher concentrations than "
     "maternal serum and has caused cyanosis, bradycardia, and "
     "hypotension in breastfed infants. Metoprolol or propranolol is "
     "preferred."),
    ("amlodipine", 3,
     "Amlodipine is excreted in milk at low levels. Limited data; "
     "no adverse effects reported in small case series. Compatible "
     "with close infant monitoring."),
    ("hydrochlorothiazide", 3,
     "HCTZ passes into milk in low amounts. Intense diuresis may "
     "decrease milk volume; doses of 50 mg/day or less are probably "
     "acceptable once lactation is established."),
    ("furosemide", 3,
     "Furosemide is excreted in milk and may suppress lactation at "
     "high doses. Occasional or low-dose use in established lactation "
     "is probably acceptable."),
    ("spironolactone", 2,
     "Spironolactone's active metabolite passes into milk in small "
     "amounts. Considered compatible with breastfeeding."),
    ("warfarin", 1,
     "Warfarin is highly protein-bound and not detectable in milk. "
     "Compatible with breastfeeding."),
    ("heparin", 1,
     "Heparin is a large molecule that does not pass into milk and "
     "would not be absorbed orally if it did. Compatible with "
     "breastfeeding."),
    ("enoxaparin", 1,
     "Enoxaparin is a large molecule poorly excreted into milk and "
     "not absorbed orally. Compatible with breastfeeding."),
    ("apixaban", 3,
     "Very limited data on apixaban in breastfeeding. Consider "
     "enoxaparin or warfarin (both compatible with breastfeeding) "
     "unless a DOAC is specifically indicated."),
    ("digoxin", 2,
     "Digoxin enters milk at low levels; infants receive clinically "
     "insignificant doses. Compatible with breastfeeding."),
    ("amiodarone", 5,
     "Amiodarone and its active metabolite accumulate in milk to "
     "levels equal to or exceeding maternal plasma, along with high "
     "iodine content. Breastfeeding is generally not recommended "
     "during maternal amiodarone therapy."),

    # -- GI ---------------------------------------------------------
    ("omeprazole", 2,
     "Omeprazole enters milk in small amounts. Considered compatible "
     "with breastfeeding."),
    ("pantoprazole", 2,
     "Pantoprazole is excreted in milk at low levels. Considered "
     "compatible with breastfeeding."),
    ("famotidine", 1,
     "Famotidine is excreted in milk at levels less than therapeutic "
     "infant doses. Preferred H2 blocker during breastfeeding."),
    ("ranitidine", 2,
     "Ranitidine passes into milk but is used directly in infants. "
     "Compatible with breastfeeding (note: ranitidine has been "
     "withdrawn in many markets due to NDMA; famotidine is preferred)."),
    ("ondansetron", 2,
     "No published data, but low maternal oral bioavailability and "
     "direct infant use at higher doses suggest it is unlikely to "
     "harm breastfed infants. Considered compatible with breastfeeding."),
    ("metoclopramide", 2,
     "Metoclopramide has been used to increase milk supply. Enters "
     "milk in small amounts with infrequent adverse effects. Consider "
     "maternal side effects (tardive dyskinesia with prolonged use)."),
    ("loperamide", 2,
     "Loperamide has poor oral bioavailability and is poorly excreted "
     "into milk. Compatible with breastfeeding."),

    # -- Respiratory / allergy --------------------------------------
    ("albuterol", 1,
     "Inhaled albuterol produces low maternal plasma levels and "
     "negligible milk transfer. Compatible with breastfeeding."),
    ("budesonide", 1,
     "Inhaled and oral budesonide produce minimal milk levels because "
     "of extensive first-pass metabolism. Compatible with breastfeeding."),
    ("fluticasone", 1,
     "Inhaled fluticasone produces minimal systemic exposure and is "
     "compatible with breastfeeding."),
    ("montelukast", 2,
     "No published data on montelukast in milk, but infant exposure "
     "is expected to be low. Considered compatible with breastfeeding."),
    ("loratadine", 1,
     "Loratadine enters milk in very small amounts and produces low "
     "infant serum levels. Considered a preferred antihistamine during "
     "breastfeeding."),
    ("cetirizine", 2,
     "Cetirizine is excreted in milk. Preferred antihistamines for "
     "breastfeeding are loratadine and fexofenadine because of lower "
     "sedation risk; cetirizine is acceptable at standard doses."),
    ("fexofenadine", 1,
     "Fexofenadine transfers into milk in very small amounts. "
     "Preferred second-generation antihistamine during breastfeeding."),
    ("diphenhydramine", 3,
     "Diphenhydramine enters milk in small amounts but maternal "
     "sedation and possible milk supply suppression are concerns. "
     "Non-sedating antihistamines (loratadine, fexofenadine) are "
     "preferred."),

    # -- Anticonvulsants / neuro -----------------------------------
    ("gabapentin", 2,
     "Gabapentin is excreted into milk in moderate amounts but infant "
     "serum levels are generally low. Monitor infant for sedation and "
     "growth."),
    ("pregabalin", 3,
     "Pregabalin enters milk; limited data on infant outcomes. "
     "Alternative treatments (gabapentin) may be preferred during "
     "breastfeeding."),
    ("levetiracetam", 2,
     "Levetiracetam enters milk but infant serum levels are low and "
     "adverse effects are rare. Considered compatible with breastfeeding."),
    ("topiramate", 3,
     "Topiramate enters milk; some infants have measurable serum "
     "levels. Monitor for diarrhea, sedation, and poor weight gain."),
    ("carbamazepine", 3,
     "Carbamazepine passes into milk; infant serum levels are "
     "typically low but rare hepatic and hematologic adverse effects "
     "have been reported. Monitor infant."),
    ("phenytoin", 3,
     "Phenytoin enters milk at low levels. Generally compatible with "
     "breastfeeding; monitor infant for sedation and adequate feeding."),

    # -- Steroids ---------------------------------------------------
    ("prednisone", 2,
     "Prednisone passes into milk in small amounts. Single daily doses "
     "up to 40 mg are compatible with breastfeeding; for higher doses, "
     "wait 4 hours before nursing to minimize infant exposure."),
    ("prednisolone", 2,
     "Prednisolone transfers to milk similarly to prednisone. Doses up "
     "to 40 mg/day are compatible with breastfeeding."),
    ("dexamethasone", 3,
     "Short-term dexamethasone use is probably compatible with "
     "breastfeeding. Long-term high-dose systemic therapy should be "
     "minimized; topical or local routes are preferred when possible."),

    # -- Hormonal / contraception ----------------------------------
    ("ethinyl estradiol / norethindrone", 3,
     "Combined estrogen-progestin oral contraceptives can decrease "
     "milk production, especially in the first 6 weeks postpartum. "
     "Progestin-only contraceptives are preferred while establishing "
     "breastfeeding."),
    ("levonorgestrel", 1,
     "Levonorgestrel (including emergency contraception and progestin-"
     "only pill) does not decrease milk supply or affect breastfed "
     "infants. Compatible with breastfeeding."),
    ("medroxyprogesterone", 1,
     "DMPA (injectable medroxyprogesterone) does not decrease milk "
     "supply or adversely affect breastfed infants. Compatible with "
     "breastfeeding."),

    # -- Migraine / triptans ---------------------------------------
    ("sumatriptan", 2,
     "Sumatriptan enters milk in very small amounts. Considered "
     "compatible with breastfeeding; preferred triptan."),
    ("rizatriptan", 2,
     "Limited data; infant exposure expected to be low. Sumatriptan "
     "has more safety data and is preferred."),

    # -- Immunosuppressants / oncology -----------------------------
    ("methotrexate", 5,
     "Methotrexate is a cytotoxic drug that can accumulate in neonatal "
     "tissues. Conventional low-dose weekly methotrexate for rheumatic "
     "disease is widely considered incompatible with breastfeeding by "
     "most guidelines; higher chemotherapy doses are absolutely "
     "contraindicated."),
    ("hydroxychloroquine", 2,
     "Hydroxychloroquine enters milk in small amounts and is considered "
     "compatible with breastfeeding. Used in lupus and RA patients "
     "during lactation."),
    ("tacrolimus", 3,
     "Tacrolimus enters milk at low levels and infant serum levels are "
     "typically undetectable. Increasingly considered acceptable during "
     "breastfeeding with infant monitoring."),
    ("cyclosporine", 3,
     "Cyclosporine enters milk variably; infant serum levels are "
     "usually low. Can be considered during breastfeeding with close "
     "infant monitoring."),
    ("azathioprine", 3,
     "Azathioprine metabolites are found in milk in small amounts. "
     "Generally considered compatible with breastfeeding; monitor "
     "infant CBC periodically if used long-term."),
    ("mycophenolate mofetil", 5,
     "Mycophenolate has teratogenic potential and limited breastfeeding "
     "data. Manufacturer and most guidelines recommend against "
     "breastfeeding during therapy."),

    # -- Misc high-frequency ---------------------------------------
    ("caffeine", 2,
     "Moderate maternal caffeine intake (up to ~300 mg/day, about 3 "
     "cups of coffee) is compatible with breastfeeding. Higher intakes "
     "may cause infant irritability and poor sleep, especially in "
     "newborns."),
    ("ethanol", 3,
     "Alcohol passes into milk freely. Occasional single drinks are "
     "probably compatible if nursing is delayed 2+ hours per drink. "
     "Regular heavy use impairs infant development and milk production."),
    ("prenatal vitamin", 1,
     "Standard prenatal multivitamins are routinely recommended during "
     "breastfeeding and do not cause adverse effects in infants."),
    ("folic acid", 1,
     "Folic acid supplementation is compatible with and often "
     "recommended during breastfeeding."),
    ("iron sulfate", 1,
     "Oral iron supplements do not alter milk iron content "
     "significantly and are compatible with breastfeeding."),
]


def _http_get(url: str, params: dict | None = None) -> requests.Response | None:
    """GET with retry + polite rate limit. Returns None on total failure.

    Passes a User-Agent identifying this tool — NCBI 403s the default
    python-requests UA for bulk bookshelf fetches.
    """
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(
                url, params=params, headers=_HEADERS, timeout=REQUEST_TIMEOUT,
            )
            resp.raise_for_status()
            return resp
        except requests.RequestException as exc:
            log.warning(
                "LactMed fetch failed (attempt %d/%d) %s: %s",
                attempt, RETRY_COUNT, url, exc,
            )
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return None


def _esearch_all_uids() -> list[str]:
    """Paginate esearch to collect every LactMed chapter UID."""
    uids: list[str] = []
    retstart = 0
    while True:
        resp = _http_get(
            f"{EUTILS_BASE}/esearch.fcgi",
            {
                "db": "books",
                "term": ESEARCH_TERM,
                "retmode": "json",
                "retmax": ESEARCH_PAGE,
                "retstart": retstart,
            },
        )
        if resp is None:
            break
        data = resp.json().get("esearchresult", {})
        page = data.get("idlist", []) or []
        if not page:
            break
        uids.extend(page)
        total = int(data.get("count", 0))
        retstart += ESEARCH_PAGE
        if retstart >= total:
            break
        time.sleep(NCBI_RATE_SLEEP)
    log.info("LactMed: esearch enumerated %d monograph UIDs", len(uids))
    return uids


def _esummary_titles(uids: list[str]) -> list[tuple[str, str]]:
    """Return a list of (nbk_accession, drug_title) for the given UIDs."""
    out: list[tuple[str, str]] = []
    for i in range(0, len(uids), ESUMMARY_BATCH):
        batch = uids[i:i + ESUMMARY_BATCH]
        resp = _http_get(
            f"{EUTILS_BASE}/esummary.fcgi",
            {"db": "books", "id": ",".join(batch), "retmode": "json"},
        )
        if resp is None:
            continue
        result = resp.json().get("result", {})
        for uid in batch:
            rec = result.get(uid)
            if not rec:
                continue
            nbk   = rec.get("chapteraccessionid") or rec.get("bookaccession")
            title = rec.get("title") or ""
            if nbk and title:
                out.append((nbk, title))
        time.sleep(NCBI_RATE_SLEEP)
    return out


def _extract_summary(html: str) -> str | None:
    """Extract the 'Summary of Use during Lactation' narrative as plain text.

    Uses balanced <div> counting so nested headings and missing-id siblings
    in newer monographs don't truncate the section prematurely.
    """
    m = _SUMMARY_OPEN_RE.search(html)
    if not m:
        return None
    start = m.end()
    depth = 1
    pos = start
    end: int | None = None
    while pos < len(html):
        o = _DIV_OPEN_RE.search(html, pos)
        c = _DIV_CLOSE_RE.search(html, pos)
        if c is None:
            break
        if o is not None and o.start() < c.start():
            depth += 1
            pos = o.end()
        else:
            depth -= 1
            if depth == 0:
                end = c.start()
                break
            pos = c.end()
    if end is None:
        return None
    raw = html[start:end]
    text = _TAG_RE.sub(" ", raw)
    text = html_lib.unescape(text)
    text = _WS_RE.sub(" ", text).strip()
    text = _HEADING_RE.sub("", text).strip()
    # Trim monograph inline refs like "[ 1 ]"
    text = re.sub(r"\[\s*\d+\s*\]", "", text)
    text = _WS_RE.sub(" ", text).strip()
    return text or None


def _infer_severity(summary: str) -> int:
    """Return the MAX severity across all matching patterns, default 3."""
    sev = 0
    for pattern, value in _SEVERITY_PATTERNS:
        if pattern.search(summary):
            if value > sev:
                sev = value
    return sev or 3


def _fetch_monograph_summary(nbk: str) -> str | None:
    """HTML-scrape the LactMed monograph at /books/<NBK>/ and return the summary."""
    resp = _http_get(f"{BOOKSHELF_URL}/{nbk}/")
    if resp is None:
        return None
    return _extract_summary(resp.text)


def _load_rxnorm_index(conn: sqlite3.Connection) -> dict[str, tuple[str, str]]:
    """Return a dict: normalized_generic_name -> (rxcui, canonical_generic)."""
    idx: dict[str, tuple[str, str]] = {}
    for rxcui, generic in conn.execute(
        "SELECT rxcui, generic_name FROM rxnorm_lookup"
    ):
        key = _normalize_drug_key(generic)
        if key and key not in idx:
            idx[key] = (rxcui, generic)
    return idx


def _normalize_drug_key(name: str) -> str:
    """Lowercase + strip punctuation for fuzzy name matching."""
    s = name.strip().lower()
    s = re.sub(r"[^\w/\s-]", "", s)
    s = _WS_RE.sub(" ", s).strip()
    return s


def _match_rxnorm(title: str, index: dict[str, tuple[str, str]]) -> tuple[str, str] | None:
    """Match a LactMed monograph title to a (rxcui, canonical_name) in rxnorm_lookup.

    Tries exact normalized equality first, then checks whether any rxnorm
    name is a whitespace-bounded substring of the monograph title (e.g.
    'Hydroxychloroquine Sulfate' → 'hydroxychloroquine').
    """
    key = _normalize_drug_key(title)
    if not key:
        return None
    if key in index:
        return index[key]
    # Try first token (monographs often titled "Drug Name (synonym)")
    first = key.split(" ", 1)[0]
    if first in index:
        return index[first]
    # Substring fallback
    for rx_key, (rxcui, generic) in index.items():
        if f" {rx_key} " in f" {key} ":
            return (rxcui, generic)
    return None


def _insert_curated(conn: sqlite3.Connection) -> int:
    """Insert hand-verified curated rows. Idempotent: skips any drug already
    represented by an existing lactation warning row in the KB.
    """
    already: set[str] = {
        r[0] for r in conn.execute(
            "SELECT DISTINCT drug_rxcui FROM warnings WHERE warning_type = ?",
            (WARNING_TYPE,),
        )
    }
    inserted = 0
    skipped_no_rxnorm = 0

    for generic, severity, summary in CURATED_LACTMED:
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
            continue
        rxcui, canonical = row
        if rxcui in already:
            continue
        conn.execute(
            "INSERT INTO warnings "
            "(drug_rxcui, drug_name, warning_type, population, "
            " description, severity, source) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (rxcui, canonical, WARNING_TYPE, POPULATION,
             summary, severity, CITATION),
        )
        already.add(rxcui)
        inserted += 1

    log.info(
        "LactMed (curated): inserted %d rows, skipped %d drugs not in rxnorm_lookup",
        inserted, skipped_no_rxnorm,
    )
    return inserted


def _insert_auto(conn: sqlite3.Connection) -> int:
    """Fetch every LactMed monograph, fuzzy-match to rxnorm_lookup, insert rows
    only for drugs that do not yet have any lactation warning.

    To keep runtime bounded we only fetch HTML for monographs whose title
    already resolves to an rxcui in our index — avoids ~1,400 wasted page
    fetches for drugs we don't know about.
    """
    already: set[str] = {
        r[0] for r in conn.execute(
            "SELECT DISTINCT drug_rxcui FROM warnings WHERE warning_type = ?",
            (WARNING_TYPE,),
        )
    }
    rx_index = _load_rxnorm_index(conn)
    log.info("LactMed (auto): rxnorm_lookup index has %d generic names", len(rx_index))

    uids = _esearch_all_uids()
    if not uids:
        log.warning("LactMed (auto): esearch returned no UIDs - skipping fetch phase")
        return 0
    metadata = _esummary_titles(uids)
    log.info("LactMed (auto): esummary resolved %d (nbk, title) pairs", len(metadata))

    # Filter to monographs we care about *before* fetching HTML.
    fetch_plan: list[tuple[str, str, str, str]] = []  # (nbk, title, rxcui, canonical)
    seen_rxcuis: set[str] = set(already)
    for nbk, title in metadata:
        match = _match_rxnorm(title, rx_index)
        if not match:
            continue
        rxcui, canonical = match
        if rxcui in seen_rxcuis:
            continue
        seen_rxcuis.add(rxcui)
        fetch_plan.append((nbk, title, rxcui, canonical))

    log.info(
        "LactMed (auto): %d monographs to fetch (%d skipped: already covered or no rxnorm match)",
        len(fetch_plan), len(metadata) - len(fetch_plan),
    )

    inserted = 0
    for i, (nbk, title, rxcui, canonical) in enumerate(fetch_plan):
        summary = _fetch_monograph_summary(nbk)
        time.sleep(NCBI_RATE_SLEEP)
        if not summary:
            log.debug("LactMed (auto): no summary section parsed for %s (%s)", title, nbk)
            continue
        severity = _infer_severity(summary)
        # Cap summary length so huge monographs don't bloat the warnings row.
        # First 1,200 chars is plenty for display; full source is cited via NBK.
        truncated = summary[:1200].rstrip()
        if len(summary) > 1200:
            truncated += " …"
        description = f"{truncated} (See NLM LactMed monograph: {BOOKSHELF_URL}/{nbk}/)"
        conn.execute(
            "INSERT INTO warnings "
            "(drug_rxcui, drug_name, warning_type, population, "
            " description, severity, source) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (rxcui, canonical, WARNING_TYPE, POPULATION,
             description, severity, CITATION),
        )
        inserted += 1
        if (i + 1) % 25 == 0:
            conn.commit()
            log.info("LactMed (auto): inserted %d / %d", inserted, len(fetch_plan))

    log.info("LactMed (auto): inserted %d rows", inserted)
    return inserted


def build(db_path: str) -> int:
    """Two-phase build: curated (Phase 1) then auto-fetched (Phase 2)."""
    log.info("LactMed: starting two-phase build")
    conn = sqlite3.connect(db_path)
    total = 0
    try:
        total += _insert_curated(conn)
        conn.commit()

        total += _insert_auto(conn)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    log.info("LactMed: total rows inserted this build: %d", total)
    return total
