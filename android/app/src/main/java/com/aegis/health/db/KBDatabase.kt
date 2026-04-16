package com.aegis.health.db

import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SQLiteOpenHelper
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

        // In production, derive from device-bound key or Android Keystore
        private const val DB_PASSPHRASE = "aegis-local-key"
    }

    private var database: SQLiteDatabase? = null

    private val dbFile: File get() = context.getDatabasePath(DB_NAME)

    // ── Lifecycle ───────────────────────────────────────────────────────

    suspend fun ensureCopied() = withContext(Dispatchers.IO) {
        SQLiteDatabase.loadLibs(context)

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
            DB_PASSPHRASE,
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
            "SELECT name, rxcui, category, drug_class FROM drugs WHERE LOWER(name) = ? LIMIT 1",
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
            """SELECT severity, description, citation FROM interactions
               WHERE (LOWER(drug_a) = ? AND LOWER(drug_b) = ?)
                  OR (LOWER(drug_a) = ? AND LOWER(drug_b) = ?)
                  OR (rxcui_a = ? AND rxcui_b = ?)
                  OR (rxcui_a = ? AND rxcui_b = ?)""",
            arrayOf(drugA, drugB, drugB, drugA, rxcuiA, rxcuiB, rxcuiB, rxcuiA),
        )
        return cursor.use { cursorToList(it) }
    }

    fun queryContraindications(drugName: String, rxcui: String, condition: String): List<Map<String, String>> {
        val cursor = rawQuery(
            """SELECT severity, description, citation FROM contraindications
               WHERE (LOWER(drug_name) = ? OR rxcui = ?)
                 AND LOWER(condition) = ?""",
            arrayOf(drugName, rxcui, condition.lowercase().trim()),
        )
        return cursor.use { cursorToList(it) }
    }

    fun queryIngredients(productName: String): List<Map<String, String>> {
        val cursor = rawQuery(
            "SELECT ingredient_name, ingredient_rxcui, strength FROM drug_ingredients WHERE LOWER(product_name) = ?",
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

    fun queryGuidelines(age: Int, sex: String, conditions: List<String>?): List<Map<String, String>> {
        val baseSql = """
            SELECT title, grade, description, population, citation
            FROM guidelines
            WHERE grade IN ('A', 'B')
              AND min_age <= ? AND max_age >= ?
              AND (sex = ? OR sex = 'all')
        """
        val cursor = rawQuery(baseSql, arrayOf(age.toString(), age.toString(), sex.lowercase()))
        return cursor.use { cursorToList(it) }
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
