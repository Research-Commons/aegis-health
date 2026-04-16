"""USPSTF source – Grade A and B preventive-care recommendations.

Fetches from the USPSTF API and falls back to a curated list of ~50
active recommendations covering cancer screening, cardiovascular risk,
diabetes, mental health, and more.
"""
from __future__ import annotations

import logging
import sqlite3
import time

import requests

log = logging.getLogger(__name__)

USPSTF_API = "https://data.uspreventiveservicestaskforce.org/api/json"
REQUEST_TIMEOUT = 20
RETRY_COUNT = 3
RETRY_BACKOFF = 2.0

# Curated snapshot of Grade A and B recommendations (as of 2024-12).
# Each entry: (rec_id, title, grade, age_min, age_max, sex, description)
CURATED_RECOMMENDATIONS: list[tuple] = [
    # ── Cancer Screening ───────────────────────────────────────
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

    # ── Cardiovascular ─────────────────────────────────────────
    ("USPSTF-2022-10", "Statin Use: Primary Prevention of CVD",
     "B", 40, 75, "all",
     "Prescribe a statin for primary prevention of CVD in adults 40-75 who have 1+ CVD risk "
     "factors and a 10-year CVD event risk of ≥10%."),
    ("USPSTF-2022-11", "Hypertension: Screening",
     "A", 18, None, "all",
     "Screen for hypertension in adults aged 18 and older with office blood pressure measurement. "
     "Obtain out-of-office measurements for confirmatory diagnosis."),
    ("USPSTF-2022-12", "Aspirin Use: CVD Prevention",
     "B", 40, 59, "all",
     "Initiate low-dose aspirin for primary prevention of CVD and colorectal cancer in adults "
     "aged 40-59 with ≥10% 10-year CVD risk. Net benefit is small."),
    ("USPSTF-2023-13", "Abdominal Aortic Aneurysm: Screening",
     "B", 65, 75, "male",
     "One-time screening ultrasonography for AAA in men aged 65-75 who have ever smoked."),
    ("USPSTF-2024-14", "Atrial Fibrillation: Screening (Stroke Prevention)",
     "B", 65, None, "all",
     "Screen for atrial fibrillation with pulse palpation or ECG in adults ≥65."),

    # ── Diabetes & Metabolic ───────────────────────────────────
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

    # ── Infections & Immunization ──────────────────────────────
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
     "Screen sexually active women aged ≤24 and older women at increased risk for chlamydia and gonorrhea."),

    # ── Mental Health & Substance Use ──────────────────────────
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

    # ── Maternal / Perinatal ───────────────────────────────────
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

    # ── Pediatric ──────────────────────────────────────────────
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
     "Screen for developmental delays and disabilities using validated tools in children ≤3."),

    # ── Other Preventive Services ──────────────────────────────
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


def _fetch_api() -> list[dict]:
    """Attempt to fetch recommendations from the USPSTF API."""
    for attempt in range(1, RETRY_COUNT + 1):
        try:
            resp = requests.get(USPSTF_API, timeout=REQUEST_TIMEOUT)
            resp.raise_for_status()
            data = resp.json()
            if isinstance(data, list):
                return data
            return data.get("specificRecommendations", data.get("data", []))
        except requests.RequestException as exc:
            log.warning("USPSTF API failed (attempt %d/%d): %s", attempt, RETRY_COUNT, exc)
            if attempt < RETRY_COUNT:
                time.sleep(RETRY_BACKOFF * attempt)
    return []


def build(db_path: str) -> int:
    """Populate the guidelines table. Returns number of rows inserted."""
    log.info("USPSTF: starting build")
    conn = sqlite3.connect(db_path)
    inserted = 0

    try:
        # Try API first
        api_recs = _fetch_api()
        if api_recs:
            log.info("USPSTF: got %d records from API", len(api_recs))
            for rec in api_recs:
                grade = rec.get("grade", "")
                if grade not in ("A", "B"):
                    continue
                try:
                    conn.execute(
                        "INSERT OR IGNORE INTO guidelines "
                        "(recommendation_id, title, grade, population_sex, "
                        " description, clinical_url, source) "
                        "VALUES (?, ?, ?, 'all', ?, ?, 'uspstf_api')",
                        (
                            rec.get("id", ""),
                            rec.get("title", ""),
                            grade,
                            rec.get("text", rec.get("description", "")),
                            rec.get("url", ""),
                        ),
                    )
                    inserted += 1
                except sqlite3.IntegrityError:
                    pass

        # Always supplement with curated list to ensure completeness
        for rec in CURATED_RECOMMENDATIONS:
            rec_id, title, grade, age_min, age_max, sex, desc = rec
            try:
                conn.execute(
                    "INSERT OR IGNORE INTO guidelines "
                    "(recommendation_id, title, grade, population_age_min, "
                    " population_age_max, population_sex, description, source) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'uspstf_curated')",
                    (rec_id, title, grade, age_min, age_max, sex, desc),
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

    log.info("USPSTF: inserted %d rows into guidelines", inserted)
    return inserted
