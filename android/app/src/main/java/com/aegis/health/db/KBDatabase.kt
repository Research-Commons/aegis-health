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
class KBDatabase(private val context: Context) {

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
