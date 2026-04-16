package com.aegis.health

import android.app.Application
import com.aegis.health.db.KBDatabase
import com.aegis.health.inference.GemmaEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AegisApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: KBDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        database = KBDatabase(this)

        appScope.launch {
            database.ensureCopied()
            GemmaEngine.initialize(this@AegisApp)
        }
    }

    companion object {
        lateinit var instance: AegisApp
            private set
    }
}
