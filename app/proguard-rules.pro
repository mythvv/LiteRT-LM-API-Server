# LiteRT LM API App - ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# LiteRT LM SDK
-keep class com.google.ai.edge.litertlm.** { *; }

# Gson
-keepattributes Signature
-keep class dev.jenny.litertlm.models.** { *; }
