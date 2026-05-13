"""Plain-language explanation for a lab test name.

Thin wrapper on lookup_term with a lab-test alias map. The wrapper lets the
parser (or the model) feed short PDF-text strings like 'LDL', 'HbA1c', 'WBC'
without forcing the caller to spell out the full MedlinePlus term title.

SAFETY-03: returned dict always carries `citation` on success. The system-prompt
rule (REGULATORY.md language-audit checklist, Plan 03) forbids emitting a
definition without an explicit source — this wrapper makes that mechanical
because the citation is selected directly from the row.
"""
from __future__ import annotations

from tools.tools.lookup_term import lookup_term

# Aliases for canonical test names → MedlinePlus term entry. Keys are lowercase;
# the lookup is case-insensitive (.strip().lower() is applied before lookup).
# Value is the canonical name as it appears in the `terms` table.
_LAB_TERM_ALIASES: dict[str, str] = {
    # ----- Lipid panel -----
    "ldl": "LDL cholesterol",
    "ldl-c": "LDL cholesterol",
    "ldl cholesterol": "LDL cholesterol",
    "low-density lipoprotein": "LDL cholesterol",
    "hdl": "HDL cholesterol",
    "hdl-c": "HDL cholesterol",
    "hdl cholesterol": "HDL cholesterol",
    "high-density lipoprotein": "HDL cholesterol",
    "total cholesterol": "Cholesterol",
    "cholesterol": "Cholesterol",
    "triglycerides": "Triglycerides",
    "non-hdl cholesterol": "Non-HDL cholesterol",

    # ----- A1C / glucose -----
    "a1c": "Hemoglobin A1C",
    "hba1c": "Hemoglobin A1C",
    "hemoglobin a1c": "Hemoglobin A1C",
    "glycated hemoglobin": "Hemoglobin A1C",
    "fasting glucose": "Fasting blood glucose",
    "fasting blood glucose": "Fasting blood glucose",
    "glucose": "Blood glucose",

    # ----- CBC -----
    "hgb": "Hemoglobin",
    "hb": "Hemoglobin",
    "hemoglobin": "Hemoglobin",
    "hct": "Hematocrit",
    "hematocrit": "Hematocrit",
    "wbc": "White blood cell count",
    "white blood cell count": "White blood cell count",
    "rbc": "Red blood cell count",
    "red blood cell count": "Red blood cell count",
    "platelets": "Platelet count",
    "platelet count": "Platelet count",
    "plt": "Platelet count",
    "mcv": "Mean corpuscular volume",
    "mch": "Mean corpuscular hemoglobin",
    "mchc": "Mean corpuscular hemoglobin concentration",

    # ----- BMP / CMP -----
    "bun": "Blood urea nitrogen",
    "blood urea nitrogen": "Blood urea nitrogen",
    "creatinine": "Creatinine",
    "egfr": "Estimated glomerular filtration rate",
    "sodium": "Sodium",
    "potassium": "Potassium",
    "chloride": "Chloride",
    "co2": "Carbon dioxide",
    "calcium": "Calcium",
    "albumin": "Albumin",
    "total protein": "Total protein",
    "alt": "ALT (Alanine aminotransferase)",
    "ast": "AST (Aspartate aminotransferase)",
    "alp": "Alkaline phosphatase",
    "total bilirubin": "Bilirubin",
    "bilirubin": "Bilirubin",

    # ----- Endocrine -----
    "tsh": "Thyroid-stimulating hormone",
    "thyroid-stimulating hormone": "Thyroid-stimulating hormone",
}


def explain_lab_test(test_name: str, db_path: str | None = None) -> dict:
    """Return a plain-language definition of what a lab test measures.

    Routes the input through the alias map, then delegates to lookup_term.
    On success returns a dict with keys: test_name (canonical, post-alias),
    plain_language_definition, citation. On miss returns {"error": ...}.
    Never raises.

    SAFETY-03 floor: every success path returns a non-empty `citation`
    selected directly from the row.
    """
    test_name = "" if test_name is None else str(test_name)
    if not test_name.strip():
        return {"error": "Empty test_name provided"}

    canonical = _LAB_TERM_ALIASES.get(test_name.strip().lower(), test_name.strip())

    # Only pass db_path when explicitly provided so lookup_term's default
    # DEFAULT_DB applies in production.
    kwargs = {"db_path": db_path} if db_path is not None else {}
    try:
        result = lookup_term(canonical, **kwargs)
    except Exception as exc:  # pragma: no cover — defensive: lookup_term shouldn't raise
        return {"error": f"No plain-language explanation for '{test_name}' ({exc})"}

    if "error" in result:
        return {"error": f"No plain-language explanation for '{test_name}'"}

    return {
        "test_name": canonical,
        "plain_language_definition": result["plain_language_definition"],
        "citation": result["citation"],
    }
