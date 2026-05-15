"""Lab-test raw-name alias map.

Maps raw test-name strings (as printed on vendor lab-report PDFs) to canonical
names. The canonical names match the corresponding hand-curated ground-truth
JSON values under eval/fixtures/lab_reports/{vendor}/*-evaluated.json, so the
Python reference parser produces canonical_name values that byte-match the
fixture ground truth after canonicalization per .planning/specs/EXTRACTION-SPEC.md.

Authority for canonical names: NLM MedlinePlus + Mayo Clinic Laboratories
(per .planning/research/ARCHITECTURE.md LabRowNormalizer section). The values
intentionally preserve mixed case (e.g. "HDL cholesterol", "AST", "eGFR",
"hemoglobin a1c") because the ground-truth JSONs do — canonical_name is the
field the model and tests both see, and case must round-trip exactly.

Keys: lowercase + internal-whitespace-collapsed (the lookup applies
str.lower().strip() + collapse-internal-whitespace before the dict get).

This map covers all 5 fixture vendors plus the D-05 analyte scope (lipid /
A1C / CBC / BMP / CMP / TSH). It is the parser's local truth source; the
Kotlin port in Phase 2 mirrors this map (kotlinx-serialization data class).
"""
from __future__ import annotations

LAB_TERM_ALIASES: dict[str, str] = {
    # ---------- Lipid panel (D-05) ----------
    # Total cholesterol variants — fixtures: labcorp "CHOLESTEROL, TOTAL",
    # hospital_lis "Total Cholesterol"
    "cholesterol, total":            "total cholesterol",
    "total cholesterol":             "total cholesterol",
    "cholesterol total":             "total cholesterol",
    "tc":                            "total cholesterol",
    "cholesterol":                   "total cholesterol",

    # LDL variants — fixtures: labcorp "LDL CHOLESTEROL" / "LDL-CHOLESTEROL",
    # hospital_lis "LDL Cholesterol"
    "ldl":                           "LDL cholesterol",
    "ldl-c":                         "LDL cholesterol",
    "ldl-cholesterol":               "LDL cholesterol",
    "ldl cholesterol":               "LDL cholesterol",
    "ldl chol":                      "LDL cholesterol",
    "ldl cholesterol calc":          "LDL cholesterol",
    "ldl-calculated":                "LDL cholesterol",
    "low-density lipoprotein":       "LDL cholesterol",

    # HDL variants — fixtures: labcorp "HDL CHOLESTEROL", hospital_lis "HDL Cholesterol"
    "hdl":                           "HDL cholesterol",
    "hdl-c":                         "HDL cholesterol",
    "hdl cholesterol":               "HDL cholesterol",
    "high-density lipoprotein":      "HDL cholesterol",

    # VLDL — fixtures: hospital_lis "VLDL Cholesterol"
    "vldl":                          "VLDL cholesterol",
    "vldl cholesterol":              "VLDL cholesterol",
    "very-low-density lipoprotein":  "VLDL cholesterol",

    # Triglycerides — fixtures: labcorp "TRIGLYCERIDES", hospital_lis "Triglyceride"
    "triglyceride":                  "triglycerides",
    "triglycerides":                 "triglycerides",
    "trig":                          "triglycerides",

    # Non-HDL — fixtures: labcorp "NON HDL CHOLESTEROL", hospital_lis "Non-HDL Cholesterol"
    "non hdl cholesterol":           "non-HDL cholesterol",
    "non-hdl cholesterol":           "non-HDL cholesterol",
    "non-hdl":                       "non-HDL cholesterol",
    "non hdl":                       "non-HDL cholesterol",

    # Lipid ratios — fixtures: labcorp "CHOL/HDLC RATIO", hospital_lis "LDL / HDL Ratio" + "TC / HDL Ratio"
    "chol/hdlc ratio":               "cholesterol ratio",
    "tc / hdl ratio":                "cholesterol ratio",
    "tc/hdl ratio":                  "cholesterol ratio",
    "cholesterol/hdl ratio":         "cholesterol ratio",
    "ldl / hdl ratio":               "LDL/HDL ratio",
    "ldl/hdl ratio":                 "LDL/HDL ratio",

    # ---------- A1C / glucose ----------
    "a1c":                           "hemoglobin a1c",
    "hba1c":                         "hemoglobin a1c",
    "hemoglobin a1c":                "hemoglobin a1c",
    "hemoglobin a1c with eag":       "hemoglobin a1c",
    "glycated hemoglobin":           "hemoglobin a1c",
    "estim. avg glu":                "estimated average glucose",
    "estim avg glu":                 "estimated average glucose",
    "estim. avg glu (eag)":          "estimated average glucose",
    "estim avg glu (eag)":           "estimated average glucose",
    "eag":                           "estimated average glucose",
    "estimated average glucose":     "estimated average glucose",
    "glucose":                       "glucose",
    "glu":                           "glucose",
    "fasting glucose":               "glucose",
    "fasting blood glucose":         "glucose",

    # ---------- CBC (D-05) ----------
    "hemoglobin":                    "hemoglobin",
    "hgb":                           "hemoglobin",
    "hb":                            "hemoglobin",
    "hematocrit":                    "hematocrit",
    "hematocrit value, hct":         "hematocrit",
    "hct":                           "hematocrit",
    "total leukocyte count":         "white blood cell count",
    "white blood cell count":        "white blood cell count",
    "white blood cell":              "white blood cell count",
    "wbc":                           "white blood cell count",
    "total rbc count":               "red blood cell count",
    "red blood cell count":          "red blood cell count",
    "red blood cell":                "red blood cell count",
    "rbc":                           "red blood cell count",
    "platelet count":                "platelet count",
    "platelets":                     "platelet count",
    "plt":                           "platelet count",
    "mean corpuscular volume, mcv":  "mean corpuscular volume",
    "mean corpuscular volume":       "mean corpuscular volume",
    "mcv":                           "mean corpuscular volume",
    "mean cell haemoglobin, mch":    "mean corpuscular hemoglobin",
    "mean cell haemoglobin":         "mean corpuscular hemoglobin",
    "mean corpuscular hemoglobin":   "mean corpuscular hemoglobin",
    "mch":                           "mean corpuscular hemoglobin",
    "mean cell haemoglobin con, mchc": "mean corpuscular hemoglobin concentration",
    "mean cell haemoglobin con":     "mean corpuscular hemoglobin concentration",
    "mean corpuscular hemoglobin concentration": "mean corpuscular hemoglobin concentration",
    "mchc":                          "mean corpuscular hemoglobin concentration",
    "neutrophils":                   "neutrophils",
    "lymphocyte":                    "lymphocytes",
    "lymphocytes":                   "lymphocytes",
    "eosinophils":                   "eosinophils",
    "monocytes":                     "monocytes",
    "basophils":                     "basophils",

    # ---------- BMP / CMP (D-05) ----------
    "urea nitrogen (bun)":           "blood urea nitrogen",
    "blood urea nitrogen":           "blood urea nitrogen",
    "bun":                           "blood urea nitrogen",
    "urea nitrogen":                 "blood urea nitrogen",
    "creatinine":                    "creatinine",
    "cr":                            "creatinine",
    "bun/creatinine ratio":          "BUN/creatinine ratio",
    "egfr non-afr. american":        "eGFR",
    "egfr african american":         "eGFR",
    "egfr":                          "eGFR",
    "estimated gfr":                 "eGFR",
    "sodium":                        "sodium",
    "na":                            "sodium",
    "potassium":                     "potassium",
    "k":                             "potassium",
    "chloride":                      "chloride",
    "cl":                            "chloride",
    "carbon dioxide":                "carbon dioxide",
    "co2":                           "carbon dioxide",
    "bicarbonate":                   "carbon dioxide",
    "calcium":                       "calcium",
    "ca":                            "calcium",
    "protein, total":                "total protein",
    "total protein":                 "total protein",
    "tp":                            "total protein",
    "albumin":                       "albumin",
    "alb":                           "albumin",
    "globulin":                      "globulin",
    "albumin/globulin ratio":        "albumin/globulin ratio",
    "a/g ratio":                     "albumin/globulin ratio",
    "bilirubin, total":              "bilirubin",
    "total bilirubin":               "bilirubin",
    "bilirubin":                     "bilirubin",
    "tbili":                         "bilirubin",
    "alkaline phosphatase":          "alkaline phosphatase",
    "alp":                           "alkaline phosphatase",
    "ast":                           "AST",
    "sgot":                          "AST",
    "ast (aspartate aminotransferase)": "AST",
    "alt":                           "ALT",
    "sgpt":                          "ALT",
    "alt (alanine aminotransferase)": "ALT",

    # ---------- Endocrine ----------
    "tsh":                           "thyroid-stimulating hormone",
    "thyroid-stimulating hormone":   "thyroid-stimulating hormone",

    # Phase 4.1 D-10: British / Indian-lab variants -- all canonicals already exist.
    "haemoglobin":                   "hemoglobin",
    "haemoglobin a1c":               "hemoglobin a1c",
    "glycated haemoglobin":          "hemoglobin a1c",
    "leucocyte":                     "white blood cell count",
    "leucocytes":                    "white blood cell count",
    "total leucocyte count":         "white blood cell count",
    "tlc":                           "white blood cell count",
    "haematocrit":                   "hematocrit",
    "haematocrit value":             "hematocrit",
    "erythrocyte count":             "red blood cell count",
    "random blood sugar":            "glucose",
    "rbs":                           "glucose",
    "fasting blood sugar":           "glucose",
    "fbs":                           "glucose",
}


def normalize(raw_name: str) -> str | None:
    """Map a raw PDF test-name string to a canonical name; None if unknown.

    Lookup is lowercase + internal-whitespace-collapsed + strip()-ed.
    """
    if raw_name is None:
        return None
    key = " ".join(str(raw_name).strip().lower().split())
    return LAB_TERM_ALIASES.get(key)
