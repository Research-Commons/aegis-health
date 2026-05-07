plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    // KSP version must pair with Kotlin (<kotlin>-<ksp>); bump in lockstep with Kotlin.
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
}
