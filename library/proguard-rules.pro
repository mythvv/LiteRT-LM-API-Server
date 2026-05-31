# LiteRT LM API Server - Library ProGuard Rules

# Keep all OpenAI API data models
-keep class dev.jenny.litertlm.models.** { *; }

# Keep LiteRT LM SDK classes
-keep class com.google.ai.edge.litertlm.** { *; }
