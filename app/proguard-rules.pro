# Jarvis Assistant ProGuard Rules

# Keep Porcupine
-keep class ai.picovoice.** { *; }

# Keep Vosk
-keep class org.vosk.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.jarvis.assistant.llm.LlamaResponse { *; }
