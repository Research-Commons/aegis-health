package com.aegis.health.db

import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * SQLCipher-encrypted wrapper around the bundled knowledge base.
 *
 * On first launch the pre-built `aegis_kb.sqlite` is copied from assets
 * to internal storage. All tool functions query through this class.
 */
class KBDatabase(private val context: Context) : KBQueries {

    companion object {
        private const val TAG = "KBDatabase"
        private const val DB_NAME = "aegis_kb.sqlite"
        private const val DB_VERSION = 1
    }

    private var database: SQLiteDatabase? = null

    @Volatile
    private var drugDictCache: Map<String, String>? = null

    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    // ── Lifecycle ───────────────────────────────────────────────────────

    suspend fun ensureCopied() = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) {
            dbFile.parentFile?.mkdirs()
            context.assets.open(DB_NAME).use { input ->
                dbFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "KB copied from assets to ${dbFile.absolutePath}")
        }

        database = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        Log.i(TAG, "KB database opened")
    }

    private fun db(): SQLiteDatabase {
        return database ?: throw IllegalStateException(
            "KBDatabase not initialized — call ensureCopied() first"
        )
    }

    fun close() {
        database?.close()
        database = null
    }

    // ── Query helpers ───────────────────────────────────────────────────

    fun rawQuery(sql: String, args: Array<String> = emptyArray()): Cursor {
        return db().rawQuery(sql, args)
    }

    fun queryDrugByName(name: String): Map<String, String>? {
        val cursor = rawQuery(
            """SELECT d.drug_name AS name, d.rxcui, rl.category, '' AS drug_class
               FROM drugs d
               LEFT JOIN rxnorm_lookup rl ON d.rxcui = rl.rxcui
               WHERE LOWER(d.drug_name) = ?
               LIMIT 1""",
            arrayOf(name.lowercase().trim()),
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToMap(it) else null
        }
    }

    fun queryRxNormLookup(name: String): Map<String, String>? {
        val cursor = rawQuery(
            """SELECT generic_name, rxcui, category
               FROM rxnorm_lookup
               WHERE LOWER(brand_name) = ? OR LOWER(generic_name) = ?
               LIMIT 1""",
            arrayOf(name.lowercase().trim(), name.lowercase().trim()),
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToMap(it) else null
        }
    }

    fun queryInteractions(drugA: String, drugB: String, rxcuiA: String, rxcuiB: String): List<Map<String, String>> {
        val cursor = rawQuery(
            """SELECT severity, description, source AS citation FROM interactions
               WHERE (LOWER(drug_name_1) = ? AND LOWER(drug_name_2) = ?)
                  OR (LOWER(drug_name_1) = ? AND LOWER(drug_name_2) = ?)
                  OR (drug_rxcui_1 = ? AND drug_rxcui_2 = ?)
                  OR (drug_rxcui_1 = ? AND drug_rxcui_2 = ?)""",
            arrayOf(drugA, drugB, drugB, drugA, rxcuiA, rxcuiB, rxcuiB, rxcuiA),
        )
        return cursor.use { cursorToList(it) }
    }

    fun queryContraindications(drugName: String, rxcui: String, condition: String): List<Map<String, String>> {
        // The `contraindications` table is not defined in the current schema —
        // drug×condition rules live in Python-side logic. Mirror the Python
        // tool's graceful-miss behaviour so CheckWarnings doesn't crash.
        return try {
            val cursor = rawQuery(
                """SELECT severity, description, citation FROM contraindications
                   WHERE (LOWER(drug_name) = ? OR rxcui = ?)
                     AND LOWER(condition) = ?""",
                arrayOf(drugName, rxcui, condition.lowercase().trim()),
            )
            cursor.use { cursorToList(it) }
        } catch (_: android.database.sqlite.SQLiteException) {
            emptyList()
        }
    }

    fun queryIngredients(productName: String): List<Map<String, String>> {
        val cursor = rawQuery(
            "SELECT ingredient_name, ingredient_rxcui, strength FROM drug_ingredients WHERE LOWER(parent_name) = ?",
            arrayOf(productName.lowercase().trim()),
        )
        return cursor.use { cursorToList(it) }
    }

    fun queryTerm(term: String): Map<String, String>? {
        val cursor = rawQuery(
            "SELECT term, plain_language_definition, citation FROM terms WHERE LOWER(term) = ? LIMIT 1",
            arrayOf(term.lowercase().trim()),
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToMap(it) else null
        }
    }

    fun queryDrugByRxcui(rxcui: String): Map<String, String>? {
        val cursor = rawQuery(
            """SELECT d.drug_name AS name,
                      d.description AS warnings_summary,
                      d.source AS citation,
                      COALESCE(r.category, 'Rx') AS category
               FROM drugs d
               LEFT JOIN rxnorm_lookup r ON r.rxcui = d.rxcui
               WHERE d.rxcui = ?
               LIMIT 1""",
            arrayOf(rxcui.trim()),
        )
        return cursor.use {
            if (it.moveToFirst()) cursorToMap(it) else null
        }
    }

    /**
     * Lowercased name → canonical generic name. Built once from
     * `rxnorm_lookup` (brand + generic) and `drug_ingredients` (ingredients).
     * This is the allow-list used by DrugNameExtractor to filter raw OCR
     * text down to names the rest of the system can actually reason about.
     */
    fun loadDrugDictionary(): Map<String, String> {
        drugDictCache?.let { return it }
        synchronized(this) {
            drugDictCache?.let { return it }
            val dict = HashMap<String, String>(64_000)

            rawQuery(
                "SELECT brand_name, generic_name FROM rxnorm_lookup WHERE generic_name IS NOT NULL"
            ).use { cur ->
                while (cur.moveToNext()) {
                    val generic = cur.getString(1)?.lowercase()?.trim().orEmpty()
                    if (generic.isEmpty()) continue
                    dict.putIfAbsent(generic, generic)
                    val brand = cur.getString(0)?.lowercase()?.trim().orEmpty()
                    if (brand.isNotEmpty() && brand != generic) {
                        dict.putIfAbsent(brand, generic)
                    }
                }
            }

            rawQuery(
                "SELECT DISTINCT ingredient_name FROM drug_ingredients WHERE ingredient_name IS NOT NULL"
            ).use { cur ->
                while (cur.moveToNext()) {
                    val ing = cur.getString(0)?.lowercase()?.trim().orEmpty()
                    if (ing.isNotEmpty()) dict.putIfAbsent(ing, ing)
                }
            }

            Log.i(TAG, "Drug dictionary loaded: ${dict.size} entries")
            drugDictCache = dict
            return dict
        }
    }

    fun queryGuidelines(age: Int, sex: String, conditions: List<String>?): List<Map<String, String>> {
        // Base demographic match. NULL age bounds mean "any" on that side;
        // NULL or 'all' sex means the recommendation applies to everyone.
        val baseSql = """
            SELECT title, grade, description,
                   population_age_min, population_age_max, population_sex,
                   source
            FROM guidelines
            WHERE grade IN ('A', 'B')
              AND (population_age_min IS NULL OR population_age_min <= ?)
              AND (population_age_max IS NULL OR population_age_max >= ?)
              AND (LOWER(COALESCE(population_sex, 'all')) = ?
                   OR LOWER(COALESCE(population_sex, 'all')) = 'all')
            ORDER BY grade, title
        """
        val rows = mutableListOf<Map<String, String>>()
        val titles = mutableSetOf<String>()
        rawQuery(baseSql, arrayOf(age.toString(), age.toString(), sex.lowercase())).use { cur ->
            val list = cursorToList(cur)
            for (r in list) {
                rows += r
                r["title"]?.let { titles += it }
            }
        }

        // Secondary: substring-match each condition against title or description
        // for any Grade A/B recommendation not already captured by the
        // demographic match.
        if (!conditions.isNullOrEmpty()) {
            val terms = conditions.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (terms.isNotEmpty()) {
                val likeClause = terms.joinToString(" OR ") {
                    "LOWER(title) LIKE ? OR LOWER(description) LIKE ?"
                }
                val args = mutableListOf<String>()
                for (t in terms) { args += "%$t%"; args += "%$t%" }
                val condSql = """
                    SELECT title, grade, description,
                           population_age_min, population_age_max, population_sex,
                           source
                    FROM guidelines
                    WHERE grade IN ('A', 'B') AND ($likeClause)
                    ORDER BY grade, title
                """
                rawQuery(condSql, args.toTypedArray()).use { cur ->
                    for (r in cursorToList(cur)) {
                        val title = r["title"] ?: continue
                        if (title in titles) continue
                        rows += r
                        titles += title
                    }
                }
            }
        }
        return rows
    }

    // ============================================================================
    // Phase 2 — ReportReader: lab range + auto-defer query helpers
    // ============================================================================

    /**
     * Adult / pediatric / sex-stratified reference-range lookup.
     *
     * Mirrors tools/tools/lookup_lab_reference_range.py (Python reference).
     * Pediatric path tried first when age<18. Per the Phase 2 D-08 / INTERPRET-03
     * contract, a pediatric MISS returns null instead of falling through to the
     * adult query — RangeEvaluator (Plan 02-11) uses the distinction to emit
     * defer_reason="kb_no_pediatric" rather than silent adult-fallback. This is
     * an intentional divergence from the Python reference's pediatric-rebind
     * behavior (Phase 1 decision 12).
     *
     * INTERPRET-02: PDF range is primary; this is the KB-fallback path.
     */
    override fun queryLabReferenceRange(testName: String, age: Int?, sex: String?): Map<String, String>? {
        if (age != null && age < 18) {
            val ped = queryPediatricRange(testName, age, sex)
            if (ped != null) return ped
            return null
        }

        val key = testName.lowercase().trim()
        val population = classifyPopulation(age, sex)

        return try {
            val cursor = rawQuery(
                """SELECT test_name, ref_low, ref_high, units, population, citation
                   FROM lab_reference_ranges
                   WHERE LOWER(test_name) = ?
                     AND population IN (?, 'all')
                   ORDER BY CASE population WHEN ? THEN 0 ELSE 1 END
                   LIMIT 1""",
                arrayOf(key, population, population),
            )
            cursor.use {
                if (it.moveToFirst()) cursorToMap(it) else null
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            null
        }
    }

    /**
     * Pediatric-specific range query (INTERPRET-03). Returns null on miss.
     *
     * Schema uses age_low/age_high INTEGER + sex TEXT (NOT age_band per the
     * 02-05-PLAN body — that was a planning-time SQL drift; the production
     * schema in kb/kb/schema.sql:202-213 has the integer-bound shape mirrored
     * here). Query semantics match the validated Python reference in
     * tools/tools/lookup_lab_reference_range.py:50-61.
     */
    override fun queryPediatricRange(testName: String, age: Int?, sex: String?): Map<String, String>? {
        val key = testName.lowercase().trim()
        val sexNorm = sex?.trim()?.lowercase().orEmpty()
        return try {
            val cursor = rawQuery(
                """SELECT test_name, ref_low, ref_high, units, citation
                   FROM reference_ranges_pediatric
                   WHERE LOWER(test_name) = ?
                     AND (age_low IS NULL OR ? = '' OR age_low <= CAST(? AS INTEGER))
                     AND (age_high IS NULL OR ? = '' OR age_high >= CAST(? AS INTEGER))
                     AND (sex = 'all' OR ? = '' OR sex = ?)
                   LIMIT 1""",
                arrayOf(
                    key,
                    age?.toString().orEmpty(), age?.toString().orEmpty(),
                    age?.toString().orEmpty(), age?.toString().orEmpty(),
                    sexNorm, sexNorm,
                ),
            )
            cursor.use {
                if (it.moveToFirst()) cursorToMap(it) else null
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            null
        }
    }

    /**
     * Pregnancy-specific range query (INTERPRET-03). Returns null on miss.
     *
     * Schema uses trimester INTEGER (1|2|3 or NULL for "all trimesters") per
     * kb/kb/schema.sql:218-229. Query mirrors the validated Python reference
     * in tools/tools/lookup_lab_reference_range.py:149-156. The plan body's
     * "trimester_$N" / "pregnancy_all" string keys were planning-time drift.
     */
    override fun queryPregnancyRange(testName: String, trimester: Int?): Map<String, String>? {
        val key = testName.lowercase().trim()
        val triArg = trimester?.toString().orEmpty()
        return try {
            val cursor = rawQuery(
                """SELECT test_name, trimester, ref_low, ref_high, units, citation
                   FROM reference_ranges_pregnancy
                   WHERE LOWER(test_name) = ?
                     AND (trimester = CAST(? AS INTEGER) OR trimester IS NULL)
                   ORDER BY CASE WHEN trimester = CAST(? AS INTEGER) THEN 0 ELSE 1 END
                   LIMIT 1""",
                arrayOf(key, triArg, triArg),
            )
            cursor.use {
                if (it.moveToFirst()) cursorToMap(it) else null
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            null
        }
    }

    /**
     * D-11 / INTERPRET-04: tumor-marker / genetic / pathology-grade auto-defer lookup.
     * Returns the category string ("tumor_marker" | "genetic" | "pathology") on hit,
     * null on miss. Wrapped in try/catch for SQLiteException because old/stale KB
     * builds may predate the auto_defer_tests table (Plan 02-03 lands it).
     */
    override fun queryAutoDefer(canonicalName: String): String? {
        return try {
            val cursor = rawQuery(
                "SELECT category FROM auto_defer_tests WHERE LOWER(canonical_name) = ? LIMIT 1",
                arrayOf(canonicalName.lowercase().trim()),
            )
            cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            null
        }
    }

    /**
     * F-05 / INTERPRET-01 BORDERLINE: clinical_thresholds rows for the given canonical.
     *
     * Returns null on table-missing (LM-2 graceful miss) or empty result. The empty-list
     * vs null distinction is intentionally collapsed at the caller level (RangeEvaluator)
     * since both mean "no threshold available".
     *
     * Schema in kb/kb/schema.sql:174-185 — populated by
     * kb/kb/sources/curated_lab_ranges.py CURATED_CLINICAL_THRESHOLDS list.
     */
    override fun queryClinicalThresholds(canonicalName: String): List<Map<String, String>>? {
        return try {
            val cursor = rawQuery(
                """SELECT test_name, threshold_tier, low_cutoff, high_cutoff, units, citation
                   FROM clinical_thresholds
                   WHERE LOWER(test_name) = ?""",
                arrayOf(canonicalName.lowercase().trim()),
            )
            cursor.use {
                val rows = mutableListOf<Map<String, String>>()
                while (it.moveToNext()) {
                    rows += cursorToMap(it)
                }
                if (rows.isEmpty()) null else rows
            }
        } catch (_: android.database.sqlite.SQLiteException) {
            null
        }
    }

    // ---- Population classification helper (mirrors Python _classify_population) ----

    private fun classifyPopulation(age: Int?, sex: String?): String {
        if (age != null && age < 18) return "pediatric"
        return when (sex?.trim()?.lowercase()) {
            "f", "female" -> "adult_female"
            "m", "male"   -> "adult_male"
            else          -> "adult"
        }
    }

    // ── Cursor utilities ────────────────────────────────────────────────

    private fun cursorToMap(cursor: Cursor): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until cursor.columnCount) {
            map[cursor.getColumnName(i)] = cursor.getString(i) ?: ""
        }
        return map
    }

    private fun cursorToList(cursor: Cursor): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        while (cursor.moveToNext()) {
            results.add(cursorToMap(cursor))
        }
        return results
    }
}
