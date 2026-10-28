# ---- Tink (crypto) -------------------------------------------------------
# Tink resolves primitives reflectively; stripping these breaks encryption at RUNTIME, not at
# build time, which is the worst possible failure mode for a secrets layer.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---- protobuf (used by Tink keysets) -------------------------------------
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# ---- OkHttp / Okio ------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Room ---------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# ---- App entities kept for schema stability ------------------------------
-keep class com.gabut.autopost.data.db.** { *; }