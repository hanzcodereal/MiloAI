# Aturan R8 untuk Milo AI (versi lite)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, RuntimeVisibleAnnotations

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.miloai.**$$serializer { *; }
-keepclassmembers class com.miloai.** {
    *** Companion;
}
-keepclasseswithmembers class com.miloai.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SnakeYAML (memakai refleksi; java.beans tidak ada di Android)
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**

# OkHttp / Jsoup (dependensi opsional)
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.annotations.**
-dontwarn com.google.re2j.**
