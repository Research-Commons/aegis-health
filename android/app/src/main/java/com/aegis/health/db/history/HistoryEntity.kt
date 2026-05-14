package com.aegis.health.db.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val title: String,
    val sub: String,
    val severityKey: String,
    val createdAt: Long,
    val payloadJson: String,
) {
    companion object {
        const val KIND_DRUGSAFE = "drugsafe"
        const val KIND_CONSENT = "consent"
        const val KIND_PARTNER = "partner"
        const val KIND_REPORTREADER = "reportreader"

        const val SEV_CRIT = "Crit"
        const val SEV_MOD = "Mod"
        const val SEV_LOW = "Low"
        const val SEV_INFO = "Info"
    }
}
