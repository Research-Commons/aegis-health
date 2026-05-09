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

# LiteRT-LM SDK and JNI bindings
-keep class com.google.ai.edge.litert.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }

# OpenApiTool implementations are invoked by the SDK via reflection.
# Without these, R8 strips getToolDescriptionJsonString / execute and the
# system turn ships with no tool declarations.
-keep class * implements com.google.ai.edge.litertlm.OpenApiTool { *; }
-keep class * implements com.google.ai.edge.litertlm.ToolProvider { *; }
-keep class com.aegis.health.tools.AegisToolDefs { *; }
-keep class com.aegis.health.tools.AegisToolDefs$* { *; }

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
