# KaavalanNote ProGuard / R8 rules.
#
# v1.2 — release-hygiene pass. Without these keep rules, R8 will
# strip or rename classes that Hilt / Room / kotlinx-serialization /
# supabase-kt / our JNI look up by reflection. The result is a
# release-only crash on first launch.
#
# Test against a release APK (`./gradlew :app:assembleRelease`,
# install, sign in, capture a note, observe sync) before shipping.

# ----- Kotlin metadata -----
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ----- kotlinx-serialization -----
# @Serializable classes and their synthetic companions must be kept.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class <1>.<2> {
    static <1>.<2>$Companion Companion;
}
-keep,includedescriptorclasses class com.kaavalan.note.**$$serializer { *; }
-keepclassmembers class com.kaavalan.note.** {
    *** Companion;
}
-keepclasseswithmembers class com.kaavalan.note.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ----- Hilt / Dagger -----
# Hilt generates classes with "$HiltModules", "_Provide*", "_Factory".
# Keep all of them; without this, every @Inject binding is null.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.lifecycle.HiltViewModel class *
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltModules** { *; }
-keep class **_HiltComponents** { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }
-keep class **DaggerKaavalanApplication_HiltComponents** { *; }

# WorkManager Hilt initializer
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ----- Room -----
# Room generates `<EntityName>Dao_Impl` and `<DatabaseName>_Impl`. Keep
# the generated classes or Room throws "cannot find implementation".
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ----- Compose -----
# Compose runtime / compiler metadata
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class **$* {
    public <init>(...);
}

# ----- JNI -----
# Native methods and their holder classes must keep their names or
# the JNI symbol lookup misses and loadLibrary("sqlcipher") fails
# with NoSuchMethodError at first call.
#
# v2.1.2: the LlamaBridge / WhisperBridge keeps were dropped. The
# llama.cpp + Whisper.cpp stack was removed in v1.6.1 (see
# docs/architecture/ai-strategy.md); those classes have not existed
# for eight releases, so the rules matched nothing.
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class net.zetetic.database.** { *; }

# ----- SQLCipher native -----
-keep class net.zetetic.** { *; }
-keep class sqlcipher.** { *; }
-keep class com.kaavalan.note.data.local.AppDatabase { *; }

# ----- supabase-kt + Ktor -----
# supabase-kt uses kotlinx-serialization for wire format (see above
# keep rules). Ktor uses reflection for some engines. Suppress
# warnings to silence harmless notes.
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }

# ----- Coroutines -----
# ServiceLoader files for default dispatcher
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    public <init>(...);
}
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler {
    public <init>(...);
}

# ----- Keep our entry points -----
-keep class com.kaavalan.note.MainActivity { *; }
-keep class com.kaavalan.note.KaavalanApplication { *; }
-keep class com.kaavalan.note.features.capture.ShareReceiverActivity { *; }
-keep class com.kaavalan.note.features.capture.VoiceCaptureService { *; }
-keep class com.kaavalan.note.features.capture.KaavalanTileService { *; }
-keep class com.kaavalan.note.features.capture.KaavalanCaptureWidget { *; }
# v2.1.2: the old `-keep com.baton.app.data.work.SyncDrainWorker`
# rule named a class that was deleted with the v2.0.0 Supabase
# drop, and every other rule in this file still named the
# pre-v2.1.1 `com.baton.app` package. Dead keep rules are silent
# — R8 does not warn about a rule that matches nothing — so they
# survived eight releases. Workers are now matched structurally
# by the `extends CoroutineWorker` / `extends ListenableWorker`
# rules in the Hilt section above, which cannot go stale when a
# worker is renamed or moved between packages.
# `ProguardRulesTest` fails the build if any fully-qualified
# `com.kaavalan.note.*` name in this file stops resolving.
-keep class com.kaavalan.note.data.brief.BriefNotifier$* { *; }

# ----- Strip log statements in release -----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
# Keep w/e for crash triage.
