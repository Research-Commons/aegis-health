package com.aegis.health.reportreader

/**
 * Phase 2 - Stage 3: alias map -> canonical name normalization.
 *
 * LM-4: 140 entries (126 base + 14 Phase 4.1 D-10 British/Indian variants),
 * generated mechanically from tools/parsers/_alias_map.py.
 * DO NOT hand-edit. Regenerate via the helper command in
 * .planning/phases/02-kotlin-pre-parse-pipeline-no-model/02-PATTERNS.md LM-4.
 *
 * Cross-language consistency test (Wave 4 LabRowNormalizerTest) asserts
 * entry count + full key/value parity against the Python source.
 */
object LabRowNormalizer {

    /** INTERPRET-05: row count above this threshold defers the whole report. */
    const val ROW_COUNT_DEFER_THRESHOLD = 25

    val LAB_TERM_ALIASES: Map<String, String> = mapOf(
        "cholesterol, total" to "total cholesterol",
        "total cholesterol" to "total cholesterol",
        "cholesterol total" to "total cholesterol",
        "tc" to "total cholesterol",
        "cholesterol" to "total cholesterol",
        "ldl" to "LDL cholesterol",
        "ldl-c" to "LDL cholesterol",
        "ldl-cholesterol" to "LDL cholesterol",
        "ldl cholesterol" to "LDL cholesterol",
        "ldl chol" to "LDL cholesterol",
        "ldl cholesterol calc" to "LDL cholesterol",
        "ldl-calculated" to "LDL cholesterol",
        "low-density lipoprotein" to "LDL cholesterol",
        "hdl" to "HDL cholesterol",
        "hdl-c" to "HDL cholesterol",
        "hdl cholesterol" to "HDL cholesterol",
        "high-density lipoprotein" to "HDL cholesterol",
        "vldl" to "VLDL cholesterol",
        "vldl cholesterol" to "VLDL cholesterol",
        "very-low-density lipoprotein" to "VLDL cholesterol",
        "triglyceride" to "triglycerides",
        "triglycerides" to "triglycerides",
        "trig" to "triglycerides",
        "non hdl cholesterol" to "non-HDL cholesterol",
        "non-hdl cholesterol" to "non-HDL cholesterol",
        "non-hdl" to "non-HDL cholesterol",
        "non hdl" to "non-HDL cholesterol",
        "chol/hdlc ratio" to "cholesterol ratio",
        "tc / hdl ratio" to "cholesterol ratio",
        "tc/hdl ratio" to "cholesterol ratio",
        "cholesterol/hdl ratio" to "cholesterol ratio",
        "ldl / hdl ratio" to "LDL/HDL ratio",
        "ldl/hdl ratio" to "LDL/HDL ratio",
        "a1c" to "hemoglobin a1c",
        "hba1c" to "hemoglobin a1c",
        "hemoglobin a1c" to "hemoglobin a1c",
        "hemoglobin a1c with eag" to "hemoglobin a1c",
        "glycated hemoglobin" to "hemoglobin a1c",
        "estim. avg glu" to "estimated average glucose",
        "estim avg glu" to "estimated average glucose",
        "estim. avg glu (eag)" to "estimated average glucose",
        "estim avg glu (eag)" to "estimated average glucose",
        "eag" to "estimated average glucose",
        "estimated average glucose" to "estimated average glucose",
        "glucose" to "glucose",
        "glu" to "glucose",
        "fasting glucose" to "glucose",
        "fasting blood glucose" to "glucose",
        "hemoglobin" to "hemoglobin",
        "hgb" to "hemoglobin",
        "hb" to "hemoglobin",
        "hematocrit" to "hematocrit",
        "hematocrit value, hct" to "hematocrit",
        "hct" to "hematocrit",
        "total leukocyte count" to "white blood cell count",
        "white blood cell count" to "white blood cell count",
        "white blood cell" to "white blood cell count",
        "wbc" to "white blood cell count",
        "total rbc count" to "red blood cell count",
        "red blood cell count" to "red blood cell count",
        "red blood cell" to "red blood cell count",
        "rbc" to "red blood cell count",
        "platelet count" to "platelet count",
        "platelets" to "platelet count",
        "plt" to "platelet count",
        "mean corpuscular volume, mcv" to "mean corpuscular volume",
        "mean corpuscular volume" to "mean corpuscular volume",
        "mcv" to "mean corpuscular volume",
        "mean cell haemoglobin, mch" to "mean corpuscular hemoglobin",
        "mean cell haemoglobin" to "mean corpuscular hemoglobin",
        "mean corpuscular hemoglobin" to "mean corpuscular hemoglobin",
        "mch" to "mean corpuscular hemoglobin",
        "mean cell haemoglobin con, mchc" to "mean corpuscular hemoglobin concentration",
        "mean cell haemoglobin con" to "mean corpuscular hemoglobin concentration",
        "mean corpuscular hemoglobin concentration" to "mean corpuscular hemoglobin concentration",
        "mchc" to "mean corpuscular hemoglobin concentration",
        "neutrophils" to "neutrophils",
        "lymphocyte" to "lymphocytes",
        "lymphocytes" to "lymphocytes",
        "eosinophils" to "eosinophils",
        "monocytes" to "monocytes",
        "basophils" to "basophils",
        "urea nitrogen (bun)" to "blood urea nitrogen",
        "blood urea nitrogen" to "blood urea nitrogen",
        "bun" to "blood urea nitrogen",
        "urea nitrogen" to "blood urea nitrogen",
        "creatinine" to "creatinine",
        "cr" to "creatinine",
        "bun/creatinine ratio" to "BUN/creatinine ratio",
        "egfr non-afr. american" to "eGFR",
        "egfr african american" to "eGFR",
        "egfr" to "eGFR",
        "estimated gfr" to "eGFR",
        "sodium" to "sodium",
        "na" to "sodium",
        "potassium" to "potassium",
        "k" to "potassium",
        "chloride" to "chloride",
        "cl" to "chloride",
        "carbon dioxide" to "carbon dioxide",
        "co2" to "carbon dioxide",
        "bicarbonate" to "carbon dioxide",
        "calcium" to "calcium",
        "ca" to "calcium",
        "protein, total" to "total protein",
        "total protein" to "total protein",
        "tp" to "total protein",
        "albumin" to "albumin",
        "alb" to "albumin",
        "globulin" to "globulin",
        "albumin/globulin ratio" to "albumin/globulin ratio",
        "a/g ratio" to "albumin/globulin ratio",
        "bilirubin, total" to "bilirubin",
        "total bilirubin" to "bilirubin",
        "bilirubin" to "bilirubin",
        "tbili" to "bilirubin",
        "alkaline phosphatase" to "alkaline phosphatase",
        "alp" to "alkaline phosphatase",
        "ast" to "AST",
        "sgot" to "AST",
        "ast (aspartate aminotransferase)" to "AST",
        "alt" to "ALT",
        "sgpt" to "ALT",
        "alt (alanine aminotransferase)" to "ALT",
        "tsh" to "thyroid-stimulating hormone",
        "thyroid-stimulating hormone" to "thyroid-stimulating hormone",
        // Phase 4.1 D-10: British / Indian-lab variants -- all canonicals already exist.
        "haemoglobin" to "hemoglobin",
        "haemoglobin a1c" to "hemoglobin a1c",
        "glycated haemoglobin" to "hemoglobin a1c",
        "leucocyte" to "white blood cell count",
        "leucocytes" to "white blood cell count",
        "total leucocyte count" to "white blood cell count",
        "tlc" to "white blood cell count",
        "haematocrit" to "hematocrit",
        "haematocrit value" to "hematocrit",
        "erythrocyte count" to "red blood cell count",
        "random blood sugar" to "glucose",
        "rbs" to "glucose",
        "fasting blood sugar" to "glucose",
        "fbs" to "glucose",
    )

    /** Pre-normalization row from VendorExtractor. */
    data class NormalizedRow(
        val canonicalName: String,
        val raw: ParsedRow,
    )

    /**
     * Mirror Python normalize: lowercase + collapse internal whitespace + strip.
     * Returns canonical_name on hit, null on miss.
     */
    fun normalize(rawName: String?): String? {
        if (rawName.isNullOrBlank()) return null
        val key = rawName.trim().lowercase().split(Regex("""\s+""")).joinToString(" ")
        return LAB_TERM_ALIASES[key]
    }

    /**
     * Convert a list of ParsedRows into NormalizedRows. Unknown rawNames are
     * dropped (mirrors Python parser behavior; not deferred since
     * the raw_name -> canonical_name mapping is a vocabulary lookup, not
     * a safety decision).
     */
    fun normalizeRows(rows: List<ParsedRow>): List<NormalizedRow> =
        rows.mapNotNull { row ->
            normalize(row.rawName)?.let { canonical -> NormalizedRow(canonical, row) }
        }
}
