package com.aegis.health.reportreader

/**
 * Phase 2 - ReportReader: bundled MedlinePlus definitions per canonical lab test name.
 *
 * D-08: Phase 2 reads from this bundle instead of the terms KB table to sidestep
 * the deferred Python lookup_term schema mismatch (Phase 1 decision 14).
 * Phase 4 EXPLAIN-01 may rewire to terms KB once schema is triaged.
 *
 * D-09: Cross-language parity with tools/parsers/lab_report_parser._DEFINITION_DB is
 * enforced by DefinitionDbConsistencyTest (src/test/). Any drift fails CI.
 *
 * SOURCE: generated mechanically from the Python source - do NOT hand-edit.
 * Regenerate via the helper command in .planning/phases/02-kotlin-pre-parse-pipeline-no-model/02-PATTERNS.md LM-4.
 */
object DefinitionDb {
    data class Entry(
        val definition: String,
        val citationUrl: String,
        val citationLabel: String,
    )

    val ENTRIES: Map<String, Entry> = mapOf(
        "total cholesterol" to Entry(
            definition = "Cholesterol is a waxy, fat-like substance in your blood. A total cholesterol test measures the overall amount of cholesterol in your blood, including both LDL and HDL cholesterol.",
            citationUrl = "https://medlineplus.gov/cholesterol.html",
            citationLabel = "MedlinePlus: Cholesterol",
        ),
        "HDL cholesterol" to Entry(
            definition = "HDL stands for high-density lipoprotein. HDL is the \"good\" cholesterol because it helps remove other forms of cholesterol from your blood.",
            citationUrl = "https://medlineplus.gov/hdlthegoodcholesterol.html",
            citationLabel = "MedlinePlus: HDL Cholesterol",
        ),
        "LDL cholesterol" to Entry(
            definition = "LDL stands for low-density lipoprotein. LDL is sometimes called the \"bad\" cholesterol because a high LDL level can lead to plaque buildup in your arteries.",
            citationUrl = "https://medlineplus.gov/ldlthebadcholesterol.html",
            citationLabel = "MedlinePlus: LDL Cholesterol",
        ),
        "VLDL cholesterol" to Entry(
            definition = "VLDL stands for very-low-density lipoprotein. VLDL contains the highest amount of triglycerides among the lipoproteins and is considered a type of \"bad\" cholesterol.",
            citationUrl = "https://medlineplus.gov/lab-tests/vldl-cholesterol/",
            citationLabel = "MedlinePlus: VLDL Cholesterol Test",
        ),
        "triglycerides" to Entry(
            definition = "Triglycerides are a type of fat found in your blood. Your body uses them for energy. High levels can raise your risk of heart disease.",
            citationUrl = "https://medlineplus.gov/lab-tests/triglycerides-test/",
            citationLabel = "MedlinePlus: Triglycerides Test",
        ),
        "non-HDL cholesterol" to Entry(
            definition = "Non-HDL cholesterol is your total cholesterol minus your HDL cholesterol. It includes LDL and other types of \"bad\" cholesterol.",
            citationUrl = "https://medlineplus.gov/lab-tests/cholesterol-levels/",
            citationLabel = "MedlinePlus: Cholesterol Levels",
        ),
        "cholesterol ratio" to Entry(
            definition = "The cholesterol ratio compares total cholesterol to HDL cholesterol. Doctors sometimes use it to estimate heart-disease risk, though most clinical guidelines now rely on the individual cholesterol values directly.",
            citationUrl = "https://medlineplus.gov/lab-tests/cholesterol-levels/",
            citationLabel = "MedlinePlus: Cholesterol Levels",
        ),
        "LDL/HDL ratio" to Entry(
            definition = "The LDL-to-HDL ratio compares \"bad\" LDL cholesterol to \"good\" HDL cholesterol. It is one of several ways to look at heart-disease risk.",
            citationUrl = "https://medlineplus.gov/lab-tests/cholesterol-levels/",
            citationLabel = "MedlinePlus: Cholesterol Levels",
        ),
        "hemoglobin a1c" to Entry(
            definition = "A hemoglobin A1c test measures your average blood sugar level over the past 3 months. It is used to diagnose and monitor type 2 diabetes and prediabetes.",
            citationUrl = "https://medlineplus.gov/a1c.html",
            citationLabel = "MedlinePlus: A1C",
        ),
        "estimated average glucose" to Entry(
            definition = "Estimated average glucose (eAG) translates your hemoglobin A1c result into the same units used for everyday glucose meters. It is a derived value calculated from your A1c.",
            citationUrl = "https://medlineplus.gov/a1c.html",
            citationLabel = "MedlinePlus: A1C",
        ),
        "glucose" to Entry(
            definition = "A blood glucose test measures the amount of glucose (sugar) in your blood. Glucose is your body's main source of energy.",
            citationUrl = "https://medlineplus.gov/bloodglucose.html",
            citationLabel = "MedlinePlus: Blood Glucose",
        ),
        "hemoglobin" to Entry(
            definition = "Hemoglobin is a protein in your red blood cells that carries oxygen from your lungs to the rest of your body. Low hemoglobin can be a sign of anemia.",
            citationUrl = "https://medlineplus.gov/lab-tests/hemoglobin-test/",
            citationLabel = "MedlinePlus: Hemoglobin Test",
        ),
        "hematocrit" to Entry(
            definition = "Hematocrit is the percentage of your blood that is made up of red blood cells. It is part of a complete blood count and helps screen for anemia and other conditions.",
            citationUrl = "https://medlineplus.gov/lab-tests/hematocrit-test/",
            citationLabel = "MedlinePlus: Hematocrit Test",
        ),
        "white blood cell count" to Entry(
            definition = "A white blood cell (WBC) count measures the number of white blood cells in your blood. White blood cells are part of your immune system and help fight infections.",
            citationUrl = "https://medlineplus.gov/lab-tests/white-blood-count-wbc/",
            citationLabel = "MedlinePlus: White Blood Count (WBC)",
        ),
        "red blood cell count" to Entry(
            definition = "A red blood cell (RBC) count measures the number of red blood cells in your blood. Red blood cells carry oxygen from your lungs to the rest of your body.",
            citationUrl = "https://medlineplus.gov/lab-tests/red-blood-cell-count/",
            citationLabel = "MedlinePlus: Red Blood Cell Count",
        ),
        "platelet count" to Entry(
            definition = "Platelets are blood cells that help your body form clots to stop bleeding. A platelet count measures the number of platelets in your blood.",
            citationUrl = "https://medlineplus.gov/lab-tests/platelet-tests/",
            citationLabel = "MedlinePlus: Platelet Tests",
        ),
        "mean corpuscular volume" to Entry(
            definition = "Mean corpuscular volume (MCV) measures the average size of your red blood cells. It is part of a complete blood count and helps diagnose different types of anemia.",
            citationUrl = "https://medlineplus.gov/lab-tests/mcv-blood-test-mean-corpuscular-volume/",
            citationLabel = "MedlinePlus: MCV Blood Test",
        ),
        "mean corpuscular hemoglobin" to Entry(
            definition = "Mean corpuscular hemoglobin (MCH) measures the average amount of hemoglobin in each red blood cell. It is reported on a complete blood count alongside MCV and MCHC.",
            citationUrl = "https://medlineplus.gov/lab-tests/red-blood-cell-indices/",
            citationLabel = "MedlinePlus: Red Blood Cell Indices",
        ),
        "mean corpuscular hemoglobin concentration" to Entry(
            definition = "Mean corpuscular hemoglobin concentration (MCHC) measures the average concentration of hemoglobin inside red blood cells. It is reported on a complete blood count alongside MCV and MCH.",
            citationUrl = "https://medlineplus.gov/lab-tests/red-blood-cell-indices/",
            citationLabel = "MedlinePlus: Red Blood Cell Indices",
        ),
        "neutrophils" to Entry(
            definition = "Neutrophils are the most common type of white blood cell and a key part of your immune response to infections. They are typically reported as part of a complete blood count (CBC) with differential.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-differential-test/",
            citationLabel = "MedlinePlus: Blood Differential Test",
        ),
        "lymphocytes" to Entry(
            definition = "Lymphocytes are a type of white blood cell that helps your immune system fight infections. They include T cells, B cells, and natural killer (NK) cells.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-differential-test/",
            citationLabel = "MedlinePlus: Blood Differential Test",
        ),
        "eosinophils" to Entry(
            definition = "Eosinophils are a type of white blood cell. They help fight off infections and play a role in allergic reactions.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-differential-test/",
            citationLabel = "MedlinePlus: Blood Differential Test",
        ),
        "monocytes" to Entry(
            definition = "Monocytes are a type of white blood cell that helps fight infection and remove damaged tissue. They are part of a complete blood count (CBC) with differential.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-differential-test/",
            citationLabel = "MedlinePlus: Blood Differential Test",
        ),
        "basophils" to Entry(
            definition = "Basophils are the rarest type of white blood cell. They release chemicals such as histamine and play a role in allergic reactions and inflammation.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-differential-test/",
            citationLabel = "MedlinePlus: Blood Differential Test",
        ),
        "blood urea nitrogen" to Entry(
            definition = "A BUN test measures the amount of urea nitrogen in your blood. Urea nitrogen is a waste product made when your body breaks down protein.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-urea-nitrogen-bun-test/",
            citationLabel = "MedlinePlus: BUN Test",
        ),
        "creatinine" to Entry(
            definition = "Creatinine is a waste product made when your muscles use energy. A blood creatinine test helps show how well your kidneys are working.",
            citationUrl = "https://medlineplus.gov/lab-tests/creatinine-test/",
            citationLabel = "MedlinePlus: Creatinine Test",
        ),
        "BUN/creatinine ratio" to Entry(
            definition = "The BUN/creatinine ratio compares the level of urea nitrogen to creatinine in your blood. It helps doctors understand whether kidney problems may be related to dehydration or another cause.",
            citationUrl = "https://medlineplus.gov/lab-tests/blood-urea-nitrogen-bun-test/",
            citationLabel = "MedlinePlus: BUN Test",
        ),
        "eGFR" to Entry(
            definition = "eGFR (estimated glomerular filtration rate) estimates how well your kidneys filter waste from your blood. A lower eGFR can be a sign of kidney disease.",
            citationUrl = "https://medlineplus.gov/lab-tests/glomerular-filtration-rate-gfr-test/",
            citationLabel = "MedlinePlus: GFR Test",
        ),
        "sodium" to Entry(
            definition = "Sodium is an electrolyte that helps balance fluid in your body. A blood sodium test can help diagnose conditions affecting the kidneys, adrenal glands, or hydration.",
            citationUrl = "https://medlineplus.gov/lab-tests/sodium-blood-test/",
            citationLabel = "MedlinePlus: Sodium Blood Test",
        ),
        "potassium" to Entry(
            definition = "Potassium is an electrolyte that helps your nerves and muscles work properly. A blood potassium test can help diagnose problems with the kidneys, heart, or other conditions.",
            citationUrl = "https://medlineplus.gov/lab-tests/potassium-blood-test/",
            citationLabel = "MedlinePlus: Potassium Blood Test",
        ),
        "chloride" to Entry(
            definition = "Chloride is an electrolyte that works with sodium and potassium to keep your body's fluids and acid-base balance in check.",
            citationUrl = "https://medlineplus.gov/lab-tests/chloride-blood-test/",
            citationLabel = "MedlinePlus: Chloride Blood Test",
        ),
        "carbon dioxide" to Entry(
            definition = "A CO2 (carbon dioxide) blood test measures the amount of carbon dioxide in the liquid part of your blood. It helps doctors check the acid-base balance and kidney/lung function.",
            citationUrl = "https://medlineplus.gov/lab-tests/co2-blood-test/",
            citationLabel = "MedlinePlus: CO2 Blood Test",
        ),
        "calcium" to Entry(
            definition = "Calcium is a mineral your body needs to build strong bones and teeth. A blood calcium test measures the calcium level in your blood and can help find problems with the bones, kidneys, or other conditions.",
            citationUrl = "https://medlineplus.gov/calcium.html",
            citationLabel = "MedlinePlus: Calcium",
        ),
        "total protein" to Entry(
            definition = "A total protein test measures the total amount of two types of proteins \u2014 albumin and globulin \u2014 in the liquid part of your blood. It can help diagnose problems with the liver or kidneys.",
            citationUrl = "https://medlineplus.gov/lab-tests/total-protein-and-albumin-globulin-ag-ratio/",
            citationLabel = "MedlinePlus: Total Protein and A/G Ratio",
        ),
        "albumin" to Entry(
            definition = "Albumin is a protein made by the liver. A blood albumin test can help diagnose problems with the liver, kidneys, or nutrition.",
            citationUrl = "https://medlineplus.gov/lab-tests/albumin-blood-test/",
            citationLabel = "MedlinePlus: Albumin Blood Test",
        ),
        "globulin" to Entry(
            definition = "Globulins are a group of proteins in the blood made by the liver and immune system. A globulin test is often part of a total-protein panel and can help check the immune system or liver.",
            citationUrl = "https://medlineplus.gov/lab-tests/total-protein-and-albumin-globulin-ag-ratio/",
            citationLabel = "MedlinePlus: Total Protein and A/G Ratio",
        ),
        "albumin/globulin ratio" to Entry(
            definition = "The albumin/globulin (A/G) ratio compares the amounts of albumin and globulin in your blood. Doctors use it together with the total-protein test to look for liver, kidney, or immune-system problems.",
            citationUrl = "https://medlineplus.gov/lab-tests/total-protein-and-albumin-globulin-ag-ratio/",
            citationLabel = "MedlinePlus: Total Protein and A/G Ratio",
        ),
        "bilirubin" to Entry(
            definition = "Bilirubin is a yellowish substance made when the body breaks down old red blood cells. High levels can cause jaundice and may signal a problem with the liver, gallbladder, or red blood cells.",
            citationUrl = "https://medlineplus.gov/lab-tests/bilirubin-blood-test/",
            citationLabel = "MedlinePlus: Bilirubin Blood Test",
        ),
        "alkaline phosphatase" to Entry(
            definition = "Alkaline phosphatase (ALP) is an enzyme found mostly in the liver and bones. A blood ALP test can help diagnose liver disease or bone problems.",
            citationUrl = "https://medlineplus.gov/lab-tests/alp-alkaline-phosphatase-blood-test/",
            citationLabel = "MedlinePlus: ALP Blood Test",
        ),
        "AST" to Entry(
            definition = "AST (aspartate aminotransferase) is an enzyme found in the liver, heart, and muscles. A blood AST test helps check for liver damage or disease.",
            citationUrl = "https://medlineplus.gov/lab-tests/ast-test/",
            citationLabel = "MedlinePlus: AST Test",
        ),
        "ALT" to Entry(
            definition = "ALT (alanine aminotransferase) is an enzyme found mostly in the liver. A blood ALT test helps check for liver injury or disease.",
            citationUrl = "https://medlineplus.gov/lab-tests/alt-blood-test/",
            citationLabel = "MedlinePlus: ALT Blood Test",
        ),
    )

    fun lookup(canonicalName: String): Entry? = ENTRIES[canonicalName]
}
