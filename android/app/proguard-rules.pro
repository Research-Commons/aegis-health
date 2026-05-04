# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.aegis.health.**$$serializer { *; }
-keepclassmembers class com.aegis.health.** {
    *** Companion;
}
-keepclasseswithmembers class com.aegis.health.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# LiteRT-LM
-keep class com.google.ai.edge.litert.** { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
